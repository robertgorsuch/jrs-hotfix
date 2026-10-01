package com.jaspersoft.jrshotfix.merge;

import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.baseline.BaselineStore;
import com.jaspersoft.jrshotfix.baseline.FileClass;
import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.merge.MergeDoc.Item;
import com.jaspersoft.jrshotfix.merge.MergeDoc.State;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.PackageStager;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Lists;
import com.jaspersoft.jrshotfix.platform.Sums;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.scan.Scan;
import com.jaspersoft.jrshotfix.text.Conflict;
import com.jaspersoft.jrshotfix.text.Diff3;
import com.jaspersoft.jrshotfix.text.PropertiesMerge;
import com.jaspersoft.jrshotfix.text.Text;
import com.jaspersoft.jrshotfix.text.XmlChecks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The merge workspaces, {@code merges/} under the home (0.2 design, 4.5): preparing one from a
 * package, the vendor's files and the server, and recording what the operator resolves. Invariants:
 * nothing under the installation is written by anything here; a workspace is visible only once its
 * {@code merge.json} exists, which is written last and replaced atomically; the three sides of a
 * file that needs a merge are copied into the workspace, so what was merged can be read again after
 * the server has changed; a file larger than {@link BaselineStore#MAX_PAYLOAD_BYTES} is never
 * merged; a file resolved with a merged text must pass its checks first.
 */
public final class MergeWorkspace {

  /** How a properties key both sides changed is settled when a merge is prepared. */
  public enum OnConflict {
    /** The file waits for the operator. */
    ASK,
    /** The site's value stands, the vendor's is kept beside it as a comment. */
    MINE,
    /** The vendor's value stands, the site's is kept beside it as a comment. */
    THEIRS,
    /** As {@link #ASK}; the command that prepared the merge exits 2. */
    FAIL;

    public static OnConflict of(String name) {
      return valueOf(name.strip().toUpperCase(Locale.ROOT));
    }

    PropertiesMerge.Style style() {
      return switch (this) {
        case ASK, FAIL -> PropertiesMerge.Style.MARKERS;
        case MINE -> PropertiesMerge.Style.MINE;
        case THEIRS -> PropertiesMerge.Style.THEIRS;
      };
    }
  }

  /** What the operator chose for one file. */
  public enum Choice {
    MERGED,
    MINE,
    THEIRS
  }

  public static final String BASE = "base";
  public static final String MINE = "mine";
  public static final String THEIRS = "theirs";
  public static final String MERGED = "merged";

  private static final String DOC = "merge.json";
  private static final String REPORT = "report.txt";
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final Home home;
  private final Clock clock;

  public MergeWorkspace(Home home, Clock clock) {
    this.home = Objects.requireNonNull(home, "home");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Path dir(String id) {
    return home.merges().resolve(id);
  }

  public Path docFile(String id) {
    return dir(id).resolve(DOC);
  }

  public Path reportFile(String id) {
    return dir(id).resolve(REPORT);
  }

  /** Where one side of {@code path} is kept in workspace {@code id}, whether or not it is. */
  public Path side(String id, String path, String side) {
    return dir(id).resolve("files").resolve(path).resolve(side);
  }

  public Optional<MergeDoc> load(String id) {
    if (!PackagePaths.isPlainFileName(id) || !Files.isRegularFile(docFile(id))) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          Json.mapper()
              .readValue(Files.readString(docFile(id), StandardCharsets.UTF_8), MergeDoc.class));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + docFile(id), e);
    }
  }

  /** Every workspace, newest first. */
  public List<MergeDoc> list() {
    if (!Files.isDirectory(home.merges())) {
      return List.of();
    }
    List<MergeDoc> out = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(home.merges())) {
      for (Path dir : dirs.filter(Files::isDirectory).toList()) {
        load(dir.getFileName().toString()).ifPresent(out::add);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + home.merges(), e);
    }
    out.sort(Comparator.comparing(MergeDoc::createdAt).thenComparing(MergeDoc::id).reversed());
    return List.copyOf(out);
  }

  /** Removes one workspace; false when there is none of that id. */
  public boolean discard(String id) throws IOException {
    if (load(id).isEmpty()) {
      return false;
    }
    Trees.deleteRecursively(dir(id));
    return true;
  }

  /** What {@link #prepare} needs to know about the installation. */
  public record Site(
      String webappName, Path webappDir, FileOps files, Predicate<String> knownToTheVendor) {}

  /**
   * Prepares a merge of {@code contents} (read from {@code zip}) into the webapp: judges every file
   * the package ships against {@code view} and the disk, merges what both changed by its class, and
   * writes the workspace. {@code site.knownToTheVendor} says whether a webapp path is any
   * baseline's or any hotfix's: a file a readme glob would delete that is not, is the site's and
   * stays.
   */
  public MergeDoc prepare(
      Path zip, PackageContents contents, BaseView view, Site site, OnConflict onConflict)
      throws IOException {
    String id = newId();
    try {
      return prepare(id, zip, contents, view, site, onConflict);
    } catch (IOException | RuntimeException e) {
      // never visible: there is no merge.json yet
      Trees.deleteRecursively(dir(id));
      throw e;
    }
  }

  private MergeDoc prepare(
      String id,
      Path zip,
      PackageContents contents,
      BaseView view,
      Site site,
      OnConflict onConflict)
      throws IOException {
    String prefix = PackagePaths.WEBAPPS_PREFIX + site.webappName() + "/";
    List<Scan.PackageItem> items =
        Scan.against(view, contents, site.webappName(), site.webappDir(), site.files());
    Map<String, String> wanted = new LinkedHashMap<>();
    for (Scan.PackageItem item : items) {
      if (needsSides(item)) {
        wanted.put(prefix + item.path(), item.path());
      }
    }
    Path filesDir = dir(id).resolve("files");
    Files.createDirectories(filesDir);
    PackageStager.stage(
        zip,
        contents,
        wanted.keySet(),
        filesDir,
        packagePath -> side(id, wanted.get(packagePath), THEIRS),
        new CancellationToken());
    List<Item> records = new ArrayList<>();
    for (Scan.PackageItem item : items) {
      records.add(item(id, item, view, site, onConflict));
    }
    for (PackageContents.Entry e : contents.deletes()) {
      if (e.path().startsWith(prefix)
          && !contents.deletions().contains(e.path())
          && !site.knownToTheVendor().test(e.path().substring(prefix.length()))) {
        String path = e.path().substring(prefix.length());
        records.add(
            new Item(
                path,
                FileClass.of(path).name(),
                "the site's file, matched by a pattern of the readme",
                State.KEPT,
                Optional.empty(),
                Optional.of(site.files().sha256(site.webappDir().resolve(path))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                "no baseline and no hotfix knows this file, so it is not the vendor's leftover;"
                    + " it is not deleted"));
      }
    }
    MergeDoc doc =
        new MergeDoc(
            id,
            contents.id(),
            contents.sha256(),
            view.hotfix().map(h -> h.build()).orElse(view.release().build()),
            view.ids(),
            onConflict.name().toLowerCase(Locale.ROOT),
            clock.instant(),
            records);
    save(doc);
    return doc;
  }

  /** True when the three sides of this file are worth keeping in the workspace. */
  private static boolean needsSides(Scan.PackageItem item) {
    return switch (item.verdict()) {
      case UNTOUCHED, NEW, VENDOR_ONLY, SITE_ONLY, SITE_REMOVED, ALREADY_APPLIED -> false;
      case COLLISION -> item.fileClass().mergeable();
      case REMOVED_COLLISION -> true;
      case INSTALLER -> true;
    };
  }

  private Item item(
      String id, Scan.PackageItem item, BaseView view, Site site, OnConflict onConflict)
      throws IOException {
    Proposal p =
        switch (item.verdict()) {
          case UNTOUCHED, NEW, VENDOR_ONLY, ALREADY_APPLIED -> plain(State.PLAIN, "");
          case SITE_ONLY -> plain(State.KEPT, "changed on this server and not by the hotfix");
          case SITE_REMOVED ->
              plain(
                  State.KEPT,
                  "removed on this server and not changed by the hotfix; it stays removed");
          case REMOVED_COLLISION -> {
            copySides(id, item, view, site);
            yield plain(
                State.CONFLICT,
                "removed on this server and changed by the hotfix: resolve with --mine (it stays"
                    + " removed) or --theirs");
          }
          case COLLISION, INSTALLER -> {
            if (!item.fileClass().mergeable()) {
              yield plain(
                  State.OVERWRITTEN,
                  "scripts, stylesheets and binary files are not merged: the hotfix's copy wins"
                      + " unless resolved with --mine");
            }
            copySides(id, item, view, site);
            yield propose(id, item, onConflict);
          }
        };
    return new Item(
        item.path(),
        item.fileClass().name(),
        item.verdict().label(),
        p.state(),
        item.base(),
        item.mine(),
        Optional.of(item.theirs()),
        p.merged(),
        Optional.empty(),
        Optional.empty(),
        p.checks(),
        p.note());
  }

  private static Proposal plain(State state, String note) {
    return new Proposal(state, Optional.empty(), List.of(), note);
  }

  private void copySides(String id, Scan.PackageItem item, BaseView view, Site site)
      throws IOException {
    Path mine = site.webappDir().resolve(item.path());
    if (Files.isRegularFile(mine)) {
      copy(mine, side(id, item.path(), MINE));
    }
    Optional<Path> base = view.payload(item.path());
    if (base.isPresent()) {
      copy(base.get(), side(id, item.path(), BASE));
    }
  }

  private static void copy(Path from, Path to) throws IOException {
    Files.createDirectories(to.getParent());
    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    Durability.sync(to);
  }

  private record Proposal(State state, Optional<String> merged, List<String> checks, String note) {}

  /** Merges one file by its class and writes the proposal as the workspace's merged file. */
  private Proposal propose(String id, Scan.PackageItem item, OnConflict onConflict)
      throws IOException {
    String path = item.path();
    Optional<byte[]> base = read(side(id, path, BASE));
    Optional<byte[]> mine = read(side(id, path, MINE));
    Optional<byte[]> theirs = read(side(id, path, THEIRS));
    if (mine.isEmpty() || theirs.isEmpty()) {
      return new Proposal(
          State.CONFLICT,
          Optional.empty(),
          List.of(),
          "too large to merge: resolve with --mine, --theirs or a merged file of your own");
    }
    Text theirText = Text.of(theirs.get());
    List<String> baseLines = base.map(b -> Text.of(b).lines()).orElse(List.of());
    List<String> mineLines = Text.of(mine.get()).lines();
    boolean installer = item.verdict() == Scan.Verdict.INSTALLER;
    if (installer && item.fileClass() != FileClass.P) {
      // the installer's XML: there is no merging it by key, the server's stays
      return new Proposal(
          State.KEPT,
          Optional.empty(),
          List.of(),
          "written by the installer for this server; never replaced. The hotfix's copy is in the"
              + " workspace as `theirs`: carry over by hand what it changed");
    }
    List<String> lines;
    State state;
    String note;
    if (item.fileClass() == FileClass.P) {
      PropertiesMerge.Merged m =
          PropertiesMerge.merge3(
              baseLines,
              mineLines,
              theirText.lines(),
              installer ? PropertiesMerge.Style.MINE_SILENT : onConflict.style());
      lines = m.lines();
      boolean marked = !installer && onConflict.style() == PropertiesMerge.Style.MARKERS;
      state = marked && !m.conflicts().isEmpty() ? State.CONFLICT : State.AUTO;
      note =
          (installer ? "written by the installer for this server: its values stand. " : "")
              + keys("site values kept", m.kept())
              + keys("site keys carried over", m.carried())
              + keys("keys removed on this server", m.removed())
              + keys(
                  state == State.CONFLICT
                      ? "changed by both, to resolve"
                      : installer ? "" : "changed by both, settled by --on-conflict " + onConflict,
                  installer ? List.of() : m.conflicts());
    } else {
      Diff3.Result r = Diff3.merge(baseLines, mineLines, theirText.lines());
      lines = r.lines();
      if (!r.clean()) {
        state = State.CONFLICT;
        note = r.conflicts() + " place(s) changed by both, between conflict markers";
      } else if (item.fileClass() == FileClass.X) {
        state = State.REVIEW;
        note = "merged by line without a conflict; an XML configuration file is always confirmed";
      } else {
        state = State.AUTO;
        note = "merged by line without a conflict";
      }
    }
    byte[] bytes = theirText.bytes(lines);
    Path file = side(id, path, MERGED);
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
    Durability.sync(file);
    List<String> checks = List.of();
    if (state == State.REVIEW) {
      checks = XmlChecks.problems(base, mine, theirs, bytes);
      if (!checks.isEmpty()) {
        state = State.CONFLICT;
        note = "merged by line without a conflict, but the result fails its checks";
      }
    }
    if (state != State.AUTO) {
      return new Proposal(state, Optional.empty(), checks, note.strip());
    }
    String hash = Sums.of(bytes).sha256();
    if (hash.equals(item.theirs())) {
      // the merge came out as the package's own file: nothing of the site's to carry
      return new Proposal(State.PLAIN, Optional.empty(), List.of(), "");
    }
    return new Proposal(state, Optional.of(hash), checks, note.strip());
  }

  private static String keys(String what, List<String> keys) {
    if (keys.isEmpty() || what.isEmpty()) {
      return "";
    }
    return what + ": " + Lists.firstAndMore(keys, 10) + ". ";
  }

  /** The bytes of a workspace file; empty when it is absent or too large to merge. */
  private static Optional<byte[]> read(Path file) throws IOException {
    if (!Files.isRegularFile(file) || Files.size(file) > BaselineStore.MAX_PAYLOAD_BYTES) {
      return Optional.empty();
    }
    return Optional.of(Files.readAllBytes(file));
  }

  /**
   * Records the operator's choice for one file. {@link Choice#MERGED} takes {@code mergedFile}, or
   * the workspace's own merged file when none is given, and refuses it (exit 2) while it holds a
   * conflict marker or, for an XML file, fails a check; the record then stays as it was, with the
   * findings.
   */
  public MergeDoc resolve(
      String id, String path, Choice choice, Optional<Path> mergedFile, String by)
      throws IOException {
    MergeDoc doc = load(id).orElseThrow(() -> unknown(id));
    Item record =
        doc.file(path)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        "merge " + id + " has no file " + path,
                        "run `jrs-hotfix merge status " + id + "` for the paths"));
    boolean decidable =
        record.state().blocks()
            || record.state() == State.RESOLVED
            || record.state() == State.KEPT_MINE
            || record.state() == State.TOOK_THEIRS
            || record.state() == State.AUTO
            || record.state() == State.OVERWRITTEN;
    if (!decidable) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          path + " needs no decision (" + record.state() + ": " + record.note() + ")",
          "run `jrs-hotfix merge status " + id + "` for the files that do");
    }
    // a script, stylesheet or binary file is never merged (section 4.1): the operator may keep
    // the site's copy or take the hotfix's, and a merged file is refused
    boolean neverMerged = !FileClass.valueOf(record.fileClass()).mergeable();
    if (neverMerged && choice == Choice.MERGED) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          path + " is a " + record.fileClass() + " file, which is never merged",
          "resolve it with --mine (the site's copy stays, the hotfix's change in it is skipped)"
              + " or --theirs (the hotfix's copy lands)");
    }
    Item changed =
        switch (choice) {
          case MINE ->
              neverMerged
                  ? record.with(
                      State.KEPT_MINE,
                      Optional.empty(),
                      by,
                      clock.instant(),
                      List.of(),
                      "kept by the operator; the hotfix's change in this "
                          + record.fileClass()
                          + " file is not installed")
                  : record.with(State.KEPT_MINE, Optional.empty(), by, clock.instant(), List.of());
          case THEIRS ->
              record.with(State.TOOK_THEIRS, Optional.empty(), by, clock.instant(), List.of());
          case MERGED -> merged(doc, record, mergedFile, by);
        };
    MergeDoc updated = doc.withFile(changed);
    save(updated);
    return updated;
  }

  /** The record resolved with a merged file, once the file has passed its checks. */
  private Item merged(MergeDoc doc, Item record, Optional<Path> mergedFile, String by)
      throws IOException {
    String id = doc.id();
    String path = record.path();
    if (record.mine().isEmpty()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          path + " was removed on this server: there is nothing to merge",
          "resolve it with --mine (it stays removed) or --theirs");
    }
    Path own = side(id, path, MERGED);
    Path source = mergedFile.map(p -> p.toAbsolutePath().normalize()).orElse(own);
    if (!Files.isRegularFile(source)) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          source + " does not exist",
          "give the merged file with `--merged <file>`");
    }
    if (Files.size(source) > BaselineStore.MAX_PAYLOAD_BYTES) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          source + " is larger than a merged file may be",
          "resolve it with --mine or --theirs");
    }
    byte[] bytes = Files.readAllBytes(source);
    List<String> findings = new ArrayList<>();
    if (Conflict.hasMarkers(Text.of(bytes).lines())) {
      findings.add("a conflict marker is left in it");
    } else if (FileClass.valueOf(record.fileClass()) == FileClass.X) {
      findings.addAll(
          XmlChecks.problems(
              read(side(id, path, BASE)),
              read(side(id, path, MINE)),
              read(side(id, path, THEIRS)),
              bytes));
    }
    if (!findings.isEmpty()) {
      save(
          doc.withFile(
              record.with(record.state(), record.merged(), by, clock.instant(), findings)));
      throw new HotfixException(
          HotfixException.PRECHECK,
          source + " cannot be installed as " + path + ": " + String.join("; ", findings),
          "edit the file, then run the same command again");
    }
    if (!source.equals(own)) {
      copy(source, own);
    }
    return record.with(
        State.RESOLVED, Optional.of(Sums.of(bytes).sha256()), by, clock.instant(), List.of());
  }

  private static HotfixException unknown(String id) {
    return new HotfixException(
        HotfixException.PRECHECK, "unknown merge " + id, "run `jrs-hotfix merge status`");
  }

  private void save(MergeDoc doc) throws IOException {
    Path file = docFile(doc.id());
    Files.createDirectories(file.getParent());
    Path report = reportFile(doc.id());
    Files.writeString(
        report,
        String.join(System.lineSeparator(), report(doc)) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Durability.writeAtomically(file, Json.writePretty(doc));
  }

  /** The summary of a merge as it is printed and kept in {@code report.txt}. */
  public static List<String> report(MergeDoc doc) {
    List<String> out = new ArrayList<>();
    out.add("merge " + doc.id() + " for " + doc.hotfixId());
    out.add("  baseline  " + String.join(" + ", doc.baselines()));
    Map<State, Integer> counts = new java.util.EnumMap<>(State.class);
    doc.files().forEach(r -> counts.merge(r.state(), 1, Integer::sum));
    out.add(
        "  files     "
            + doc.files().size()
            + ": "
            + counts.getOrDefault(State.PLAIN, 0)
            + " as the package has them, "
            + (counts.getOrDefault(State.KEPT, 0) + counts.getOrDefault(State.KEPT_MINE, 0))
            + " kept as the site has them, "
            + (counts.getOrDefault(State.AUTO, 0) + counts.getOrDefault(State.RESOLVED, 0))
            + " merged, "
            + counts.getOrDefault(State.OVERWRITTEN, 0)
            + " replaced although the site changed them, "
            + doc.blocking().size()
            + " waiting for you");
    for (Item r : doc.files()) {
      if (r.state() == State.PLAIN) {
        continue;
      }
      out.add(String.format(Locale.ROOT, "  %-11s %s  %s", r.state(), r.fileClass(), r.path()));
      if (!r.note().isEmpty()) {
        out.add("              " + r.note());
      }
      for (String check : r.checks()) {
        out.add("              ! " + check);
      }
    }
    return out;
  }

  private String newId() {
    int suffix = ThreadLocalRandom.current().nextInt(0x10000);
    return "m-" + STAMP.format(clock.instant()) + "-" + HexFormat.of().toHexDigits((short) suffix);
  }
}

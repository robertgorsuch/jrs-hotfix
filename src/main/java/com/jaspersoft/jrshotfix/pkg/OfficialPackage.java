package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.pkg.PackageLayout.Shape;
import com.jaspersoft.jrshotfix.pkg.PackageReadme.Header;
import com.jaspersoft.jrshotfix.pkg.PackageReadme.Notes;
import com.jaspersoft.jrshotfix.pkg.PackageReadme.Readme;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Lists;
import com.jaspersoft.jrshotfix.platform.Sums;
import com.jaspersoft.jrshotfix.platform.Zips;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads an official Jaspersoft cumulative hotfix package into the change list it would make on this
 * installation. Invariants: the package is read as a stream, once, and nothing is written, so no
 * payload is held in memory or copied; every add or replace carries the SHA-256 of its payload and
 * the package carries the SHA-256 of the whole file; an entry is {@code replace} only when the file
 * exists on this server now and {@code add} otherwise; deletes come from the readme's "Deleted
 * files" list and its "Important" globs, expanded against this installation and never covering a
 * file the package itself lays down; a site-written XML file the server has is kept, not replaced;
 * an older version of a library the package brings is reported, never deleted; the readme's manual
 * steps are carried verbatim and whole, a section both inner readmes hold only once; nothing on the
 * server is touched here.
 *
 * <p>The layout of the archive is {@link PackageLayout}'s, the readmes are {@link PackageReadme}'s
 * and the deletions and warnings that complete the change list are {@link PackageRules}'.
 */
public final class OfficialPackage {

  /** Keys of a merged settings file named in its note. Names only: a value may be a secret. */
  private static final int MAX_NAMED_KEYS = 10;

  public static final String NEITHER_SHAPE_SHORT =
      " is not an official Jaspersoft hotfix package (readme.txt beside jasperserver[-pro].zip,"
          + " js-install.zip or an unpacked jasperserver[-pro]/ tree)";

  private OfficialPackage() {}

  private static final String AS_DOWNLOADED =
      "point jrs-hotfix at the hotfix ZIP as it was downloaded";

  /** The layout of {@code zip}, or the refusal of a file that is no readable archive. */
  private static Shape readableShape(Path zip) {
    return PackageLayout.shape(zip)
        .orElseThrow(
            () ->
                new HotfixException(
                    HotfixException.PRECHECK,
                    zip + " is not a readable hotfix package",
                    AS_DOWNLOADED));
  }

  /** The refusal of a package without its outer readme. */
  private static HotfixException noReadme(Path zip) {
    return new HotfixException(
        HotfixException.PRECHECK,
        "no readme.txt in " + zip,
        AS_DOWNLOADED + ", not at an unpacked copy");
  }

  /** The refusal of a package without a payload. */
  private static HotfixException noPayload(Path zip) {
    return new HotfixException(
        HotfixException.PRECHECK, "no jasperserver or js-install archive in " + zip, AS_DOWNLOADED);
  }

  /** True when {@code zip} is an official package. */
  public static boolean looksOfficial(Path zip) {
    return PackageLayout.shape(zip).map(Shape::official).orElse(false);
  }

  /**
   * Reads {@code source} into the change list it would make here, mapping webapp entries onto
   * {@code webappName} and installation entries onto the install directory. Streams the file once
   * and writes nothing.
   */
  public static PackageContents read(
      Path source, PackagePaths paths, String webappName, FileOps files) throws IOException {
    return read(source, paths, webappName, files, SiteDecisions.NONE);
  }

  /**
   * As {@link #read(Path, PackagePaths, String, FileOps)}, with what a prepared merge decided about
   * the files under the webapp: a kept file gets no entry, a merged one lands as the merged file,
   * and the reader's own rules for the installer-written files are not applied.
   */
  public static PackageContents read(
      Path source, PackagePaths paths, String webappName, FileOps files, SiteDecisions decisions)
      throws IOException {
    return read(source, paths, webappName, files, decisions, Superseded.REPORT_ONLY);
  }

  /**
   * What the reader does with a library under {@code WEB-INF/lib} that is an older version of one
   * the package brings and that no readme names (0.2 design, section 6). {@code vendors} says
   * whether a webapp path is known to be the vendor's, from a baseline or a ledger entry: only such
   * a library is deleted, so one the site added never is. With {@code delete} false, or for a
   * library not known, the library is reported and left.
   */
  public record Superseded(Predicate<String> vendors, boolean delete) {
    /** Nothing is known to be the vendor's: every such library is a warning. */
    public static final Superseded REPORT_ONLY = new Superseded(p -> false, true);

    public Superseded {
      Objects.requireNonNull(vendors, "vendors");
    }
  }

  /**
   * As {@link #read(Path, PackagePaths, String, FileOps, SiteDecisions)}, with what to do about
   * superseded libraries.
   */
  public static PackageContents read(
      Path source,
      PackagePaths paths,
      String webappName,
      FileOps files,
      SiteDecisions decisions,
      Superseded superseded)
      throws IOException {
    try {
      return readChecked(source, paths, webappName, decisions, superseded);
    } catch (IllegalArgumentException e) {
      // an InvalidPathException among them: a name this file system cannot hold
      throw unusable(e.getMessage(), Optional.of(e));
    }
  }

  /** The package holds a path jrs-hotfix cannot use: unsupported input, exit 6. */
  private static HotfixException unusable(String what, Optional<Throwable> cause) {
    String message = "the package holds an unusable path: " + printable(what);
    String remediation = "obtain the package again; it is not the layout jrs-hotfix knows";
    return cause
        .map(c -> new HotfixException(HotfixException.UNSUPPORTED, message, remediation, c))
        .orElseGet(() -> new HotfixException(HotfixException.UNSUPPORTED, message, remediation));
  }

  /** {@code s} with every control character shown as {@code ?}, fit for one line of output. */
  static String printable(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      out.append(c < 0x20 || c == 0x7f ? '?' : c);
    }
    return out.toString();
  }

  private static PackageContents readChecked(
      Path source,
      PackagePaths paths,
      String webappName,
      SiteDecisions decisions,
      Superseded policy)
      throws IOException {
    Shape shape = readableShape(source);
    if (shape.readme().isEmpty()) {
      throw noReadme(source);
    }
    if (!shape.payload()) {
      throw noPayload(source);
    }
    String webappPrefix = PackagePaths.WEBAPPS_PREFIX + webappName + "/";
    Header header = null;
    Readme treeReadme = null;
    List<PackageContents.Entry> entries = new ArrayList<>();
    List<PackageContents.Kept> kept = new ArrayList<>();
    List<PackageContents.VendorFile> vendorFiles = new ArrayList<>();
    List<String> listed = new ArrayList<>();
    Set<String> added = new LinkedHashSet<>();
    List<Readme> readmes = new ArrayList<>();
    Notes notes = new Notes();
    Payload payload =
        new Payload(paths, entries, kept, vendorFiles, added, notes, decisions, webappPrefix);
    MessageDigest whole = Sums.newDigest();
    try (InputStream in = new DigestInputStream(Files.newInputStream(source), whole);
        ZipInputStream outer = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = Zips.nextFile(outer)) != null) {
        String name = Zips.name(entry);
        switch (shape.kind(name)) {
          case README -> header = Header.parse(PackageReadme.readLines(outer));
          case WEBAPP_ZIP -> readmes.add(inner(outer, name, webappPrefix, payload));
          case INSTALL_ZIP -> readmes.add(inner(outer, name, "", payload));
          case WEBAPP_FILE -> {
            String under = shape.underWebapp(name);
            if (under.equalsIgnoreCase(PackageLayout.README)) {
              treeReadme = Readme.parse(webappPrefix, PackageReadme.readLines(outer));
            } else {
              payload.hash(outer, webappPrefix + under, Optional.empty(), entry.getName());
            }
          }
          case IGNORE -> {}
        }
      }
      // ZipInputStream stops at the central directory and returns -1 from then on, so drain the
      // raw stream under it: the whole-file digest must cover every byte of the file.
      in.transferTo(OutputStream.nullOutputStream());
    }
    if (header == null) {
      throw noReadme(source);
    }
    if (entries.isEmpty() && kept.isEmpty()) {
      throw noPayload(source);
    }
    if (treeReadme != null) {
      readmes.add(treeReadme);
    }
    for (Readme r : readmes) {
      List<String> said = new ArrayList<>();
      entries.addAll(PackageRules.deletions(r, added, paths, said, decisions));
      said.forEach(notes::say);
      listed.addAll(r.deleted());
      listed.addAll(r.globs());
      notes.conditions(r.conditions());
      notes.manual(r.manual());
    }
    if (!decisions.active()) {
      // with a merge, what happens to each changed file is said by the merge, not guessed here
      PackageRules.configNotes(entries).forEach(notes::say);
    }
    List<String> supersededPaths = new ArrayList<>();
    List<String> conflicts = new ArrayList<>();
    for (String note :
        PackageRules.superseded(entries, paths, webappPrefix, policy, supersededPaths, conflicts)) {
      notes.say(note);
    }
    return new PackageContents(
        header.id(),
        header.release(),
        header.edition(),
        header.build(),
        header.title(),
        HexFormat.of().formatHex(whole.digest()),
        entries,
        kept,
        vendorFiles,
        listed.stream()
            .filter(p -> PackagePaths.pathProblems(p.replace('*', '_')).isEmpty())
            .toList(),
        supersededPaths,
        conflicts,
        notes.lines());
  }

  /**
   * Hashes every file of one inner archive into {@code entries} and returns its parsed readme. The
   * outer stream is wrapped, not closed, so the outer walk continues after it.
   */
  private static Readme inner(InputStream source, String outerName, String prefix, Payload payload)
      throws IOException {
    Readme readme = Readme.empty();
    ZipInputStream zip = new ZipInputStream(source);
    ZipEntry entry;
    while ((entry = Zips.nextFile(zip)) != null) {
      String name = Zips.name(entry);
      if (name.equalsIgnoreCase(PackageLayout.README)) {
        readme = Readme.parse(prefix, PackageReadme.readLines(zip));
        continue;
      }
      payload.hash(zip, prefix + name, Optional.of(outerName), entry.getName());
    }
    return readme;
  }

  /** Where the files of the package are collected while it is read. */
  private record Payload(
      PackagePaths paths,
      List<PackageContents.Entry> entries,
      List<PackageContents.Kept> kept,
      List<PackageContents.VendorFile> vendorFiles,
      Set<String> added,
      Notes notes,
      SiteDecisions decisions,
      String webappPrefix) {

    /**
     * Streams one file of the package through a digest, writing nothing, and adds its entry: {@code
     * replace} when the file exists here now, else {@code add}. A settings file this server has
     * values of its own in ({@link SiteSettings}) is planned as the merged file, and a site-written
     * XML file the server has gets no entry at all: it stays, and the package's copy goes into the
     * notes.
     */
    void hash(InputStream in, String path, Optional<String> source, String entryName)
        throws IOException {
      if (!PackagePaths.pathProblems(path).isEmpty()) {
        throw unusable(entryName, Optional.empty());
      }
      Path target = paths.resolve(path);
      Action action = Files.isRegularFile(target) ? Action.REPLACE : Action.ADD;
      Sums.Sink sink = new Sums.Sink(OutputStream.nullOutputStream());
      Optional<SiteDecisions.Decision> decision = decisions.of(path);
      if (decisions.active() && decision.isEmpty() && path.startsWith(webappPrefix)) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the merge was not prepared for this package: it says nothing about " + path,
            "prepare it again with `jrs-hotfix merge prepare <package.zip>`");
      }
      Optional<SiteSettings.Merged> merged = Optional.empty();
      Optional<byte[]> theirs = Optional.empty();
      // the reader's own rules for the installer's files hold only where no merge decided
      boolean own = decision.isEmpty() && action == Action.REPLACE;
      boolean stays = own && SiteSettings.keptAsItIs(path);
      if (stays || (own && SiteSettings.holdsSiteValues(path))) {
        byte[] head = in.readNBytes(SiteSettings.MAX_BYTES + 1);
        sink.write(head, 0, head.length);
        if (head.length <= SiteSettings.MAX_BYTES) {
          theirs = Optional.of(head);
          if (!stays) {
            merged = SiteSettings.merge(target, head);
          }
        }
      }
      in.transferTo(sink);
      Sums sums = sink.sums();
      String payload = sums.sha256();
      vendorFiles.add(
          new PackageContents.VendorFile(
              path, payload, sums.textSha256(), sums.size(), source, entryName));
      // a file that stays is "laid down" too: no readme deletion may remove it
      added.add(path);
      if (stays) {
        keep(path, target, payload, theirs);
        return;
      }
      if (decision.isPresent()) {
        SiteDecisions.Decision d = decision.get();
        switch (d.kind()) {
          case KEEP -> kept.add(new PackageContents.Kept(path, payload, d.reason()));
          case PLAIN ->
              entries.add(
                  new PackageContents.Entry(path, action, Optional.of(payload), source, entryName));
          case MERGED ->
              entries.add(
                  new PackageContents.Entry(
                      path,
                      action,
                      d.mergedSha256(),
                      source,
                      entryName,
                      Optional.of(payload),
                      d.mergedFile()));
        }
        return;
      }
      if (merged.isEmpty()) {
        entries.add(
            new PackageContents.Entry(path, action, Optional.of(payload), source, entryName));
        return;
      }
      SiteSettings.Merged m = merged.get();
      entries.add(
          new PackageContents.Entry(
              path, action, Optional.of(m.sha256()), source, entryName, Optional.of(payload)));
      notes.say(
          path
              + " holds values written for this server and is merged, not replaced. Keys whose"
              + " value here is kept where the package ships another: "
              + keys(m.kept())
              + ". Keys only this server has, carried over: "
              + keys(m.carried())
              + ". Compare a kept value with the package's if a fix depends on it; the file as it"
              + " was is in the snapshot");
    }

    /**
     * The server's site-written XML file stays. When the package's copy differs from it, the copy
     * is shown in the notes, since what the vendor changed in it must be carried over by hand.
     */
    private void keep(String path, Path target, String payload, Optional<byte[]> theirs)
        throws IOException {
      kept.add(
          new PackageContents.Kept(
              path, payload, "written by the installer for this server; never replaced"));
      if (payload.equals(Sums.of(target).sha256())) {
        return;
      }
      String sentence =
          path
              + " holds this server's database connection and is not replaced. The package ships"
              + " another copy of it; carry over by hand what the hotfix changed in it";
      if (theirs.isEmpty()) {
        notes.say(sentence + " (the copy is too large to show here; it is in the package)");
        return;
      }
      notes.sayAndQuote(sentence + ". The package's copy:", SiteSettings.lines(theirs.get()));
    }

    private static String keys(List<String> keys) {
      if (keys.isEmpty()) {
        return "none";
      }
      return Lists.firstAndMore(keys, MAX_NAMED_KEYS);
    }
  }
}

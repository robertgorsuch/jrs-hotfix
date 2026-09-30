package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.platform.FileOps;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads an official Jaspersoft cumulative hotfix package into the change list it would make on this
 * installation. Invariants: the package is read as a stream, once, and nothing is written, so no
 * payload is held in memory or copied; every add or replace carries the SHA-256 of its payload and
 * the package carries the SHA-256 of the whole file; an entry is {@code replace} only when the file
 * exists on this server now and {@code add} otherwise; deletes come from the readme's "Deleted
 * files" list and its "Important" globs, expanded against this installation and never covering a
 * file the package itself lays down; the readme's manual steps are carried verbatim and whole, a
 * section both inner readmes hold only once; nothing on the server is touched here.
 */
public final class OfficialPackage {

  /**
   * The inner archive whose paths are relative to the installed webapp, by base name, with or
   * without a version or build suffix ({@code jasperserver-pro-10.0.0-hotfix.zip}); support's
   * packages have used both (jrsctl field test 2, H1).
   */
  private static final Pattern WEBAPP_ZIP =
      Pattern.compile("(?i)^jasperserver(-pro)?(-[0-9][^/]*)?\\.zip$");

  /** The inner archive whose paths are relative to the installation (buildomatic and samples). */
  private static final Pattern INSTALL_ZIP =
      Pattern.compile("(?i)^js-install(-[0-9][^/]*)?\\.zip$");

  /** The webapp shipped unpacked instead of as an inner archive. */
  private static final Pattern WEBAPP_DIR = Pattern.compile("(?i)^jasperserver(-pro)?$");

  /** The top-level entry of a jrsctl bundle; an archive holding it is not an official package. */
  private static final String BUNDLE_MANIFEST = "manifest.json";

  private static final String README = "readme.txt";

  // Case-insensitive, and "Product:" as well as "Product Name:": readmes differ in capitalisation.
  private static final Pattern PRODUCT = Pattern.compile("(?i)^Product(?: Name)?:\\s*(.+?)\\s*$");
  private static final Pattern RELEASE = Pattern.compile("(?i)^Release Version:\\s*([0-9.]+)\\s*$");
  private static final Pattern BUILD =
      Pattern.compile("(?i)^Build version:\\s*\\[?([0-9_]+)]?\\s*$");
  private static final Pattern BUILD_ID = Pattern.compile("^([0-9]{8})_([0-9]{4})$");

  /** A line of the readme that names one file, with or without a {@code *} in its last segment. */
  private static final Pattern LISTED_PATH = Pattern.compile("^[A-Za-z0-9._*/-]+$");

  public static final String NEITHER_SHAPE_SHORT =
      " is not an official Jaspersoft hotfix package (readme.txt beside jasperserver[-pro].zip,"
          + " js-install.zip or an unpacked jasperserver[-pro]/ tree)";

  private OfficialPackage() {}

  /** What {@code hotfix record} stores about a package applied by hand. */
  public record Described(String id, String title, String release, String edition, String build) {}

  /**
   * The identity the package's outer readme gives it, without reading the payload: the same id,
   * title, release, edition and build {@link #read} derives, so a hotfix recorded by hand and one
   * applied through jrs-hotfix share an id and cannot both be in the ledger.
   */
  public static Described describe(Path zip) throws IOException {
    try {
      return describeChecked(zip);
    } catch (IllegalArgumentException e) {
      throw unusable(e.getMessage(), Optional.of(e));
    }
  }

  private static Described describeChecked(Path zip) throws IOException {
    Shape shape =
        shape(zip)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        zip + " is not a readable hotfix package",
                        "point jrs-hotfix at the hotfix ZIP as it was downloaded"));
    if (shape.readme().isEmpty() || !shape.payload()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          zip + NEITHER_SHAPE_SHORT,
          "point jrs-hotfix at the hotfix ZIP as support published it");
    }
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream outer = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = outer.getNextEntry()) != null) {
        if (!entry.isDirectory() && shape.kind(entry.getName().replace('\\', '/')) == Kind.README) {
          Header header = Header.parse(readLines(outer));
          return new Described(
              header.id(), header.title(), header.release(), header.edition(), header.build());
        }
      }
    }
    throw new HotfixException(
        HotfixException.PRECHECK,
        "no readme.txt in " + zip,
        "point jrs-hotfix at the hotfix ZIP as it was downloaded, not at an unpacked copy");
  }

  /** True when {@code zip} is an official package. */
  public static boolean looksOfficial(Path zip) {
    return shape(zip).map(Shape::official).orElse(false);
  }

  /** What one entry of the outer archive is to the reader. */
  enum Kind {
    /** The package readme, with the release and build. */
    README,
    /** An inner archive whose paths are relative to the webapp. */
    WEBAPP_ZIP,
    /** An inner archive whose paths are relative to the installation. */
    INSTALL_ZIP,
    /** A file of the webapp shipped unpacked under {@code jasperserver[-pro]/}. */
    WEBAPP_FILE,
    /** Anything else, left alone. */
    IGNORE
  }

  /**
   * How the outer archive is laid out, judged on base names so the case of the readme, one
   * directory of prefix around the package and a version in an inner archive's name all read as the
   * same package. Invariants: {@code root} is the directory of the outer readme, empty or ending in
   * {@code /}; {@link #official()} is true exactly when a readme and at least one payload were
   * seen; an entry named {@code manifest.json} at the top makes the archive a jrsctl bundle, never
   * a package.
   */
  record Shape(String root, Optional<String> readme, boolean payload) {

    boolean official() {
      return readme.isPresent() && payload;
    }

    Kind kind(String name) {
      if (readme.isPresent() && name.equals(readme.get())) {
        return Kind.README;
      }
      if (!name.startsWith(root)) {
        return Kind.IGNORE;
      }
      String rel = name.substring(root.length());
      int slash = rel.indexOf('/');
      if (slash < 0) {
        if (WEBAPP_ZIP.matcher(rel).matches()) {
          return Kind.WEBAPP_ZIP;
        }
        return INSTALL_ZIP.matcher(rel).matches() ? Kind.INSTALL_ZIP : Kind.IGNORE;
      }
      return WEBAPP_DIR.matcher(rel.substring(0, slash)).matches() && slash < rel.length() - 1
          ? Kind.WEBAPP_FILE
          : Kind.IGNORE;
    }

    /** For a {@link Kind#WEBAPP_FILE}: its path under the unpacked webapp directory. */
    String underWebapp(String name) {
      String rel = name.substring(root.length());
      return rel.substring(rel.indexOf('/') + 1);
    }
  }

  /** The layout of {@code zip}; empty when it is not a readable archive or is a jrsctl bundle. */
  static Optional<Shape> shape(Path zip) {
    if (!Files.isRegularFile(zip)) {
      return Optional.empty();
    }
    List<String> names = new ArrayList<>();
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream z = new ZipInputStream(in)) {
      ZipEntry e;
      while ((e = z.getNextEntry()) != null) {
        String name = e.getName().replace('\\', '/');
        if (name.equals(BUNDLE_MANIFEST)) {
          return Optional.empty();
        }
        if (!e.isDirectory()) {
          names.add(name);
        }
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    Optional<String> readme =
        names.stream()
            .filter(n -> baseName(n).equalsIgnoreCase(README))
            .min(
                Comparator.comparingInt((String n) -> n.length() - n.replace("/", "").length())
                    .thenComparing(n -> n));
    String root = readme.map(r -> r.substring(0, r.lastIndexOf('/') + 1)).orElse("");
    Shape probe = new Shape(root, readme, true);
    boolean payload =
        names.stream().map(probe::kind).anyMatch(k -> k != Kind.README && k != Kind.IGNORE);
    return Optional.of(new Shape(root, readme, payload));
  }

  private static String baseName(String name) {
    return name.substring(name.lastIndexOf('/') + 1);
  }

  /**
   * Reads {@code source} into the change list it would make here, mapping webapp entries onto
   * {@code webappName} and installation entries onto the install directory. Streams the file once
   * and writes nothing.
   */
  public static PackageContents read(
      Path source, PackagePaths paths, String webappName, FileOps files) throws IOException {
    try {
      return readChecked(source, paths, webappName);
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
  private static String printable(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      out.append(c < 0x20 || c == 0x7f ? '?' : c);
    }
    return out.toString();
  }

  private static PackageContents readChecked(Path source, PackagePaths paths, String webappName)
      throws IOException {
    Shape shape =
        shape(source)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        source + " is not a readable hotfix package",
                        "point jrs-hotfix at the hotfix ZIP as it was downloaded"));
    if (shape.readme().isEmpty()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "no readme.txt in " + source,
          "point jrs-hotfix at the hotfix ZIP as it was downloaded, not at an unpacked copy");
    }
    if (!shape.payload()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "no jasperserver or js-install archive in " + source,
          "point jrs-hotfix at the hotfix ZIP as it was downloaded");
    }
    String webappPrefix = PackagePaths.WEBAPPS_PREFIX + webappName + "/";
    Header header = null;
    Readme treeReadme = null;
    List<PackageContents.Entry> entries = new ArrayList<>();
    Set<String> added = new LinkedHashSet<>();
    List<Readme> readmes = new ArrayList<>();
    Notes notes = new Notes();
    MessageDigest whole = sha256();
    try (InputStream in = new DigestInputStream(Files.newInputStream(source), whole);
        ZipInputStream outer = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = outer.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName().replace('\\', '/');
        switch (shape.kind(name)) {
          case README -> header = Header.parse(readLines(outer));
          case WEBAPP_ZIP -> readmes.add(inner(outer, name, webappPrefix, paths, entries, added));
          case INSTALL_ZIP -> readmes.add(inner(outer, name, "", paths, entries, added));
          case WEBAPP_FILE -> {
            String under = shape.underWebapp(name);
            if (under.equalsIgnoreCase(README)) {
              treeReadme = Readme.parse(webappPrefix, readLines(outer));
            } else {
              entries.add(
                  hashEntry(
                      outer,
                      webappPrefix + under,
                      Optional.empty(),
                      entry.getName(),
                      paths,
                      added));
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
      throw new HotfixException(
          HotfixException.PRECHECK,
          "no readme.txt in " + source,
          "point jrs-hotfix at the hotfix ZIP as it was downloaded, not at an unpacked copy");
    }
    if (entries.isEmpty()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "no jasperserver or js-install archive in " + source,
          "point jrs-hotfix at the hotfix ZIP as it was downloaded");
    }
    if (treeReadme != null) {
      readmes.add(treeReadme);
    }
    for (Readme r : readmes) {
      List<String> said = new ArrayList<>();
      entries.addAll(deletions(r, added, paths, said));
      said.forEach(notes::say);
      notes.conditions(r.conditions());
      notes.manual(r.manual());
    }
    configNotes(entries).forEach(notes::say);
    return new PackageContents(
        header.id(),
        header.release(),
        header.edition(),
        header.build(),
        header.title(),
        HexFormat.of().formatHex(whole.digest()),
        entries,
        notes.lines());
  }

  /**
   * The notes of one package, in the order they were given. Invariants: a sentence of jrs-hotfix's
   * own is said once; a line of the readme is never dropped because an equal line came before it in
   * the same section (SQL repeats its lines); a section both inner readmes hold, blank lines aside,
   * is carried once.
   */
  private static final class Notes {
    private final List<PackageContents.Note> lines = new ArrayList<>();
    private final Set<String> said = new HashSet<>();
    private final Set<String> conditionsSeen = new HashSet<>();
    private final Set<List<String>> manualSeen = new HashSet<>();

    void say(String sentence) {
      if (said.add(sentence)) {
        lines.add(PackageContents.Note.said(sentence));
      }
    }

    private void quote(List<String> readmeLines) {
      readmeLines.forEach(line -> lines.add(PackageContents.Note.quoted(line)));
    }

    /** The Important section's sentences; one an earlier readme gave is not repeated. */
    void conditions(List<String> conditions) {
      List<String> fresh = conditions.stream().filter(c -> !conditionsSeen.contains(c)).toList();
      if (fresh.isEmpty()) {
        return;
      }
      conditionsSeen.addAll(fresh);
      say(
          "the package readme's Important section names conditions to check by hand (an earlier"
              + " build, source map files); read readme.txt in the package");
      quote(fresh);
    }

    /** The Additional Notes section, whole and in the readme's own lines. */
    void manual(List<String> manual) {
      List<String> text =
          manual.stream().map(String::strip).filter(line -> !line.isEmpty()).toList();
      if (text.isEmpty() || !manualSeen.add(text)) {
        return;
      }
      say(
          "the package readme's Additional Notes section describes manual steps, such as SQL for"
              + " some databases and optional properties; jrs-hotfix runs none of them");
      quote(manual);
    }

    List<PackageContents.Note> lines() {
      return List.copyOf(lines);
    }
  }

  /**
   * Hashes every file of one inner archive into {@code entries} and returns its parsed readme. The
   * outer stream is wrapped, not closed, so the outer walk continues after it.
   */
  private static Readme inner(
      InputStream source,
      String outerName,
      String prefix,
      PackagePaths paths,
      List<PackageContents.Entry> entries,
      Set<String> added)
      throws IOException {
    Readme readme = Readme.empty();
    ZipInputStream zip = new ZipInputStream(source);
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      if (entry.isDirectory()) {
        continue;
      }
      String name = entry.getName().replace('\\', '/');
      if (name.equalsIgnoreCase(README)) {
        readme = Readme.parse(prefix, readLines(zip));
        continue;
      }
      entries.add(
          hashEntry(zip, prefix + name, Optional.of(outerName), entry.getName(), paths, added));
    }
    return readme;
  }

  /**
   * Streams one file of the package through a digest, writing nothing, and returns its entry:
   * {@code replace} when the file exists here now, else {@code add}.
   */
  private static PackageContents.Entry hashEntry(
      InputStream in,
      String path,
      Optional<String> source,
      String entryName,
      PackagePaths paths,
      Set<String> added)
      throws IOException {
    if (!PackagePaths.pathProblems(path).isEmpty()) {
      throw unusable(entryName, Optional.empty());
    }
    MessageDigest md = sha256();
    try (OutputStream digest = new DigestOutputStream(OutputStream.nullOutputStream(), md)) {
      in.transferTo(digest);
    }
    Action action = Files.isRegularFile(paths.resolve(path)) ? Action.REPLACE : Action.ADD;
    added.add(path);
    return new PackageContents.Entry(
        path, action, Optional.of(HexFormat.of().formatHex(md.digest())), source, entryName);
  }

  /**
   * The readme's deletions as entries: the listed files, and the "Important" globs expanded against
   * this installation. Only files that are here now are listed, and never one the package itself
   * lays down.
   */
  private static List<PackageContents.Entry> deletions(
      Readme readme, Set<String> added, PackagePaths paths, List<String> notes) {
    List<PackageContents.Entry> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    // compared as resolved paths too: WindowsPath equality ignores case, as the file system does,
    // so a readme deletion spelt differently from a payload path never removes that payload
    Set<Path> laidDown = new HashSet<>();
    for (String a : added) {
      laidDown.add(paths.resolve(a).toAbsolutePath().normalize());
    }
    Set<Path> seenTargets = new HashSet<>();
    for (String path : readme.deleted()) {
      List<String> problems = PackagePaths.pathProblems(path);
      if (!problems.isEmpty()) {
        notes.add(skipped(path, problems));
        continue;
      }
      Path target = paths.resolve(path);
      if (!added.contains(path)
          && !laidDown.contains(target)
          && seen.add(path)
          && seenTargets.add(target)
          && Files.isRegularFile(target)) {
        out.add(deletion(path));
      }
    }
    int fromGlobs = 0;
    for (String glob : readme.globs()) {
      List<String> problems = PackagePaths.pathProblems(glob.replace('*', '_'));
      if (!problems.isEmpty()) {
        notes.add(skipped(glob, problems));
        continue;
      }
      for (String path : expand(glob, paths)) {
        Path target = paths.resolve(path);
        if (!added.contains(path)
            && !laidDown.contains(target)
            && seen.add(path)
            && seenTargets.add(target)) {
          out.add(deletion(path));
          fromGlobs++;
        }
      }
    }
    if (fromGlobs > 0) {
      notes.add(
          fromGlobs
              + " file(s) left by an earlier hotfix are deleted, as the readme's Important section"
              + " requires; they are in the snapshot and a rollback puts them back");
    }
    return out;
  }

  /** The note for a readme deletion jrs-hotfix will not act on. */
  private static String skipped(String path, List<String> problems) {
    return "the package readme lists "
        + printable(path)
        + " for deletion; skipped, it is not a usable path ("
        + String.join("; ", problems)
        + "): delete it by hand if it applies";
  }

  private static PackageContents.Entry deletion(String path) {
    return new PackageContents.Entry(path, Action.DELETE, Optional.empty(), Optional.empty(), path);
  }

  /** Files of {@code glob}'s directory whose names match it, as package paths. */
  private static List<String> expand(String glob, PackagePaths paths) {
    int slash = glob.lastIndexOf('/');
    if (slash < 0) {
      return List.of();
    }
    String dir = glob.substring(0, slash);
    String name = glob.substring(slash + 1);
    Path directory = paths.resolve(dir + "/" + name.replace('*', '_')).getParent();
    if (directory == null || !Files.isDirectory(directory)) {
      return List.of();
    }
    Pattern pattern =
        Pattern.compile(
            Stream.of(name.split("\\*", -1))
                .map(Pattern::quote)
                .reduce((a, b) -> a + ".*" + b)
                .orElse(""));
    List<String> out = new ArrayList<>();
    try (Stream<Path> list = Files.list(directory)) {
      list.filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .filter(f -> pattern.matcher(f).matches())
          .filter(f -> PackagePaths.pathProblems(dir + "/" + f).isEmpty())
          .sorted()
          .forEach(f -> out.add(dir + "/" + f));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + directory, e);
    }
    return out;
  }

  /** Warnings about files that usually hold site settings and are about to be replaced. */
  private static List<String> configNotes(List<PackageContents.Entry> entries) {
    List<String> paths =
        entries.stream()
            .filter(e -> e.action() == Action.REPLACE)
            .map(PackageContents.Entry::path)
            .filter(OfficialPackage::isSettingsFile)
            .sorted()
            .toList();
    if (paths.isEmpty()) {
      return List.of();
    }
    List<String> shown = paths.size() > 8 ? paths.subList(0, 8) : paths;
    String more =
        paths.size() > shown.size() ? " and " + (paths.size() - shown.size()) + " more" : "";
    return List.of(
        "settings you changed in these files are overwritten and must be applied again: "
            + String.join(", ", shown)
            + more);
  }

  /** True for the configuration files a site edits, as opposed to code the hotfix ships. */
  private static boolean isSettingsFile(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return (lower.endsWith(".xml") || lower.endsWith(".properties"))
        && !lower.contains("/web-inf/lib/");
  }

  private static List<String> readLines(InputStream in) throws IOException {
    List<String> lines = new ArrayList<>();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
    String line;
    while ((line = reader.readLine()) != null) {
      // indentation is kept: the readme's SQL and numbered steps go into the notes as they are
      lines.add(line.stripTrailing());
    }
    return lines;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
    }
  }

  /** What the outer readme says about the package as a whole. */
  private record Header(String release, String edition, String build) {

    static Header parse(List<String> lines) {
      String release = "";
      String edition = "PRO";
      String build = "";
      for (String raw : lines) {
        String line = raw.strip();
        Matcher m = RELEASE.matcher(line);
        if (m.matches()) {
          release = m.group(1);
        }
        m = BUILD.matcher(line);
        if (m.matches()) {
          build = m.group(1);
        }
        m = PRODUCT.matcher(line);
        if (m.matches()) {
          edition = m.group(1).toLowerCase(Locale.ROOT).contains("pro") ? "PRO" : "CE";
        }
      }
      if (release.isEmpty() || build.isEmpty()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the package readme names no release and build version",
            "point jrs-hotfix at an official cumulative hotfix ZIP");
      }
      return new Header(release, edition, build);
    }

    /** Derived id, for example {@code JRSHF-10.0.0-20260730-0457}. */
    String id() {
      Matcher m = BUILD_ID.matcher(build);
      if (!m.matches()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the package build version is not a date and time: " + build,
            "point jrs-hotfix at an official cumulative hotfix ZIP");
      }
      return "JRSHF-" + release + "-" + m.group(1) + "-" + m.group(2);
    }

    String title() {
      return "JasperReports Server "
          + (edition.equals("PRO") ? "Pro " : "")
          + release
          + " cumulative hotfix "
          + build;
    }
  }

  /**
   * The parts of an inner readme jrs-hotfix acts on: what to delete, and what to do by hand. {@code
   * conditions} are the sentences of the Important section; {@code manual} is the Additional Notes
   * section as the readme has it, indentation and inner blank lines included, every line of it.
   */
  private record Readme(
      List<String> deleted, List<String> globs, List<String> conditions, List<String> manual) {

    static Readme empty() {
      return new Readme(List.of(), List.of(), List.of(), List.of());
    }

    static Readme parse(String prefix, List<String> lines) {
      List<String> deleted = new ArrayList<>();
      List<String> globs = new ArrayList<>();
      List<String> manual = new ArrayList<>();
      List<String> conditions = new ArrayList<>();
      Section section = Section.NONE;
      for (String raw : lines) {
        String line = raw.strip();
        Section heading = Section.of(line);
        if (heading != null) {
          section = heading;
          continue;
        }
        if (line.startsWith("=====")) {
          section = Section.NONE;
          continue;
        }
        if (section == Section.NOTES) {
          manual.add(raw);
          continue;
        }
        if (line.isEmpty()) {
          continue;
        }
        boolean path = LISTED_PATH.matcher(line).matches();
        switch (section) {
          case DELETED -> {
            if (path && !line.contains("*")) {
              deleted.add(prefix + line);
            }
          }
          case IMPORTANT -> {
            if (path && line.contains("*")) {
              globs.add(prefix + line);
            } else {
              conditions.add(line);
            }
          }
          case NOTES, NONE -> {}
        }
      }
      return new Readme(deleted, globs, conditions, withoutOuterBlankLines(manual));
    }

    private static List<String> withoutOuterBlankLines(List<String> lines) {
      int from = 0;
      int to = lines.size();
      while (from < to && lines.get(from).isBlank()) {
        from++;
      }
      while (to > from && lines.get(to - 1).isBlank()) {
        to--;
      }
      return List.copyOf(lines.subList(from, to));
    }

    private enum Section {
      NONE,
      DELETED,
      IMPORTANT,
      NOTES;

      static Section of(String line) {
        return switch (line) {
          case "Deleted files:" -> DELETED;
          case "IMPORTANT" -> IMPORTANT;
          case "Additional Notes:" -> NOTES;
          case "Added files:", "Modified files:", "Installation", "Uninstallation" -> NONE;
          default -> null;
        };
      }
    }
  }
}

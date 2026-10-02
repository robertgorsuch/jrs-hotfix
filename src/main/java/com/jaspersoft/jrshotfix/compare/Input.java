package com.jaspersoft.jrshotfix.compare;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.platform.Zips;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One thing {@code compare} reads (0.7 design, section 3): a WAR, a webapp directory (exploded or
 * deployed), a distribution (its directory or ZIP, both areas), a hotfix package (its files), or
 * {@code server}, the webapp and installation of this home's settings. Invariants: every input is
 * read as directories, one per area it holds; an archive is unpacked under a temporary directory of
 * its own, which {@link #close} deletes; nothing an input names is ever written; an input that is
 * none of these is refused as unsupported (exit 6), one that is absent or unreadable as a precheck
 * (exit 2).
 */
public final class Input implements AutoCloseable {

  /** The word that names this home's server as an input. */
  public static final String SERVER = "server";

  private static final String WAR = "jasperserver-pro.war";
  private static final Pattern WEBAPP_ZIP =
      Pattern.compile("(?i)(.*/)?jasperserver(-pro)?[^/]*\\.zip");
  private static final Pattern INSTALL_ZIP = Pattern.compile("(?i)(.*/)?js-install[^/]*\\.zip");

  private final String label;
  private final Map<Area, Path> roots;
  private final Optional<Path> temporary;

  private Input(String label, Map<Area, Path> roots, Optional<Path> temporary) {
    this.label = label;
    this.roots = roots;
    this.temporary = temporary;
  }

  /** How the input is named in the report. */
  public String label() {
    return label;
  }

  /** The areas this input holds. */
  public java.util.Set<Area> areas() {
    return roots.keySet();
  }

  /** The directory of {@code area}; for the installation, the one that holds buildomatic. */
  public Path root(Area area) {
    return roots.get(area);
  }

  /**
   * Every file of {@code area}, as a path of that area, sorted; the installation's are those under
   * {@link Area#INSTALLATION_DIRS} only.
   */
  public List<String> files(Area area) throws IOException {
    Path root = roots.get(area);
    if (root == null) {
      return List.of();
    }
    if (area == Area.WEBAPP) {
      return walk(root, "");
    }
    List<String> all = new ArrayList<>();
    for (String dir : Area.INSTALLATION_DIRS) {
      all.addAll(walk(root.resolve(dir), dir + "/"));
    }
    return all;
  }

  /** The file of {@code area} at {@code path}; it may not exist. */
  public Path file(Area area, String path) {
    return roots.get(area).resolve(path);
  }

  /**
   * Opens {@code arg}. {@code server} is the settings {@code arg} names when it is {@link #SERVER};
   * {@code temp} is where archives are unpacked.
   */
  public static Input open(String arg, Optional<Settings> server, Path temp) {
    if (arg.equals(SERVER)) {
      Settings s =
          server.orElseThrow(
              () ->
                  new HotfixException(
                      HotfixException.PRECHECK,
                      "there are no settings, so there is no server to compare",
                      "run `jrs-hotfix settings detect`, or name a WAR or a directory instead"));
      Map<Area, Path> roots = new EnumMap<>(Area.class);
      roots.put(Area.WEBAPP, s.webappDir());
      if (holdsInstallation(s.installDir())) {
        roots.put(Area.INSTALLATION, s.installDir());
      }
      return new Input(SERVER, roots, Optional.empty());
    }
    Path path = Path.of(arg).toAbsolutePath().normalize();
    if (!Files.exists(path)) {
      throw new HotfixException(
          HotfixException.PRECHECK, path + " does not exist", "check the path and run again");
    }
    try {
      if (Files.isDirectory(path)) {
        return directory(arg, path, Optional.empty(), temp);
      }
      return archive(arg, path, temp);
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot read " + path + ": " + e.getMessage(),
          "check the file and the free space under " + temp,
          e);
    }
  }

  private static Input directory(String label, Path dir, Optional<Path> temporary, Path temp)
      throws IOException {
    Map<Area, Path> roots = new EnumMap<>(Area.class);
    if (Files.isDirectory(dir.resolve("WEB-INF"))) {
      roots.put(Area.WEBAPP, dir);
      return new Input(label, roots, temporary);
    }
    if (!holdsInstallation(dir)) {
      throw unsupported(dir, "it holds neither WEB-INF nor buildomatic or samples");
    }
    roots.put(Area.INSTALLATION, dir);
    Path war = dir.resolve(WAR);
    Optional<Path> own = temporary;
    if (Files.isRegularFile(war)) {
      Path into = own.orElseGet(() -> newTemp(temp)).resolve("webapp");
      extract(war, into);
      roots.put(Area.WEBAPP, into);
      own = Optional.of(into.getParent());
    }
    return new Input(label, roots, own);
  }

  private static Input archive(String label, Path file, Path temp) throws IOException {
    Path into = newTemp(temp);
    try {
      if (OfficialPackage.looksOfficial(file)) {
        return hotfixPackage(label, file, into);
      }
      List<String> names = names(file);
      if (names.stream().anyMatch(n -> n.startsWith("WEB-INF/"))) {
        Path webapp = into.resolve("webapp");
        extract(file, webapp);
        return new Input(label, new EnumMap<>(Map.of(Area.WEBAPP, webapp)), Optional.of(into));
      }
      Optional<String> top =
          names.stream()
              .filter(n -> n.endsWith("/" + WAR) || n.equals(WAR))
              .map(n -> n.substring(0, n.length() - WAR.length()))
              .findFirst();
      if (top.isPresent()) {
        Path dist = into.resolve("distribution");
        extract(file, dist);
        return directory(label, dist.resolve(top.get()), Optional.of(into), temp);
      }
      throw unsupported(file, "it is neither a WAR, a distribution nor an official hotfix package");
    } catch (IOException | RuntimeException e) {
      Trees.deleteRecursively(into);
      throw e;
    }
  }

  /**
   * A package's files: its webapp archive into one area, its installation archive into the other.
   */
  private static Input hotfixPackage(String label, Path file, Path into) throws IOException {
    Path outer = into.resolve("package");
    extract(file, outer);
    Map<Area, Path> roots = new EnumMap<>(Area.class);
    for (String name : walk(outer, "")) {
      if (WEBAPP_ZIP.matcher(name).matches()) {
        extract(outer.resolve(name), into.resolve("webapp"));
        roots.put(Area.WEBAPP, into.resolve("webapp"));
      } else if (INSTALL_ZIP.matcher(name).matches()) {
        extract(outer.resolve(name), into.resolve("installation"));
        roots.put(Area.INSTALLATION, into.resolve("installation"));
      }
    }
    if (roots.isEmpty()) {
      throw unsupported(file, "it holds no jasperserver-pro.zip or js-install.zip");
    }
    return new Input(label, roots, Optional.of(into));
  }

  /** The names of an archive's files, with {@code /}. */
  private static List<String> names(Path zip) throws IOException {
    List<String> names = new ArrayList<>();
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream z = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = Zips.nextFile(z)) != null) {
        names.add(Zips.name(entry));
      }
    }
    return names;
  }

  /** Unpacks every file of {@code zip} under {@code into}, refusing a name that would escape it. */
  private static void extract(Path zip, Path into) throws IOException {
    Path root = into.toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream z = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = Zips.nextFile(z)) != null) {
        String name = Zips.name(entry);
        Path target = root.resolve(name).normalize();
        if (!PackagePaths.pathProblems(name).isEmpty() || !target.startsWith(root)) {
          throw unsupported(zip, "it holds an unusable entry name");
        }
        Files.createDirectories(target.getParent());
        Files.copy(z, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static boolean holdsInstallation(Path dir) {
    return Area.INSTALLATION_DIRS.stream().anyMatch(d -> Files.isDirectory(dir.resolve(d)));
  }

  private static List<String> walk(Path root, String prefix) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .map(f -> prefix + root.relativize(f).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }

  private static Path newTemp(Path temp) {
    try {
      Files.createDirectories(temp);
      return Files.createTempDirectory(temp, "compare-");
    } catch (IOException e) {
      throw new java.io.UncheckedIOException("cannot create a directory under " + temp, e);
    }
  }

  private static HotfixException unsupported(Path input, String why) {
    return new HotfixException(
        HotfixException.UNSUPPORTED,
        input + " cannot be compared: " + why,
        "name a WAR, a webapp directory, a distribution (its ZIP or directory), an official hotfix"
            + " package, or `server`");
  }

  @Override
  public void close() {
    if (temporary.isPresent()) {
      try {
        Trees.deleteRecursively(temporary.get());
      } catch (IOException e) {
        // a temporary copy left behind is harmless; the next compare makes its own
      }
    }
  }
}

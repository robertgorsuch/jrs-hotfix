package com.jaspersoft.jrshotfix.scan;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.baseline.BaselineManifest.BaseFile;
import com.jaspersoft.jrshotfix.baseline.FileClass;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.SiteSettings;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Sums;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compares an area on disk, the webapp or the installation, with the vendor's files (0.2 design,
 * section 3; 0.7 design, section 1) and, given a package, says for each file the package ships
 * whether the site's change and the vendor's collide. Invariants: read-only; paths are relative to
 * the area, with {@code /}, and the installation is read under its {@link Area#INSTALLATION_DIRS}
 * only; files of a text class are equal when they differ in line ends only; a file the installer
 * fills in for one site is never counted as a customization; a file written at run time, or built
 * output the vendor does not ship, is counted and not listed.
 */
public final class Scan {

  private static final Pattern EXTERNAL_AUTH =
      Pattern.compile("(?i)WEB-INF/applicationContext-externalAuth[^/]*\\.xml");

  /** Where a running server writes under its own webapp. */
  private static final List<String> RUNTIME = List.of("web-inf/logs/");

  private Scan() {}

  /** How one file on disk stands against the vendor's. */
  public enum State {
    /** In both, different: the site edited a vendor file. */
    CHANGED,
    /** Only on disk: the site added it. */
    ADDED,
    /** Only in the base: the site deleted a vendor file. */
    REMOVED,
    /** A file the installer writes with this site's values; listed, never a customization. */
    INSTALLER,
    /** A deployed external-authentication context, the site's own, made from a sample. */
    EXTERNAL_AUTH
  }

  /** One file that differs from the vendor's. */
  public record Item(String path, FileClass fileClass, State state) {}

  /**
   * What the scan found: the items, the number of files written at run time or built that are not
   * listed, and the number of files equal to the vendor's.
   */
  public record Report(String baseline, List<Item> items, int generated, int unchanged) {
    public Report {
      items = List.copyOf(items);
    }

    public long count(State state) {
      return items.stream().filter(i -> i.state() == state).count();
    }

    /** True when no vendor file was changed, added to or removed. */
    public boolean vanilla() {
      return count(State.CHANGED) + count(State.ADDED) + count(State.REMOVED) == 0;
    }

    /** The answer to "is this server customized", one line. */
    public String verdict() {
      return vanilla()
          ? "vanilla: no vendor file was changed"
          : "customized: "
              + count(State.CHANGED)
              + " changed, "
              + count(State.ADDED)
              + " added, "
              + count(State.REMOVED)
              + " removed";
    }

    public List<Item> of(State state) {
      return items.stream().filter(i -> i.state() == state).toList();
    }
  }

  /** Every file of {@code view}'s area under {@code root} (the webapp or the installation). */
  public static Report of(BaseView view, Path root, FileOps files) {
    Area area = view.area();
    List<Item> items = new ArrayList<>();
    Set<String> onDisk = new HashSet<>();
    int generated = 0;
    int unchanged = 0;
    // by the path as text, so the order is the same on every file system
    List<String> all = area == Area.WEBAPP ? walk(root, "") : installation(root);
    for (String path : all) {
      Path file = root.resolve(path);
      onDisk.add(path);
      FileClass cls = area.fileClass(path);
      Optional<BaseFile> base = view.file(path);
      if (base.isEmpty()) {
        if (EXTERNAL_AUTH.matcher(path).matches()) {
          items.add(new Item(path, cls, State.EXTERNAL_AUTH));
        } else if (installer(view, path)) {
          // written whole by the installer, so the vendor's webapp has no copy of it
          items.add(new Item(path, cls, State.INSTALLER));
        } else if (generated(area, cls, path)) {
          generated++;
        } else {
          items.add(new Item(path, cls, State.ADDED));
        }
        continue;
      }
      if (same(cls, base.get(), file, files)) {
        unchanged++;
      } else {
        items.add(new Item(path, cls, installer(view, path) ? State.INSTALLER : State.CHANGED));
      }
    }
    for (String path : view.paths()) {
      if (!onDisk.contains(path)) {
        items.add(new Item(path, area.fileClass(path), State.REMOVED));
      }
    }
    return new Report(view.describe(), items, generated, unchanged);
  }

  /** The files under {@code root}, each as {@code prefix} + its path below root, sorted. */
  private static List<String> walk(Path root, String prefix) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .map(f -> prefix + root.relativize(f).toString().replace('\\', '/'))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + root, e);
    }
  }

  /** The files under the installation's directories a package writes, as installation paths. */
  private static List<String> installation(Path installDir) {
    List<String> all = new ArrayList<>();
    for (String dir : Area.INSTALLATION_DIRS) {
      all.addAll(walk(installDir.resolve(dir), dir + "/"));
    }
    return all;
  }

  /** True when a file the vendor has no copy of is built or written at run time, not the site's. */
  private static boolean generated(Area area, FileClass cls, String path) {
    if (area == Area.INSTALLATION) {
      return area.generated(path);
    }
    String p = path.toLowerCase(Locale.ROOT);
    return cls == FileClass.G || RUNTIME.stream().anyMatch(p::startsWith);
  }

  /** True when the installer writes {@code path} for one site, by the baseline or by name. */
  public static boolean installer(BaseView view, String path) {
    if (view.area() == Area.INSTALLATION) {
      return view.installer(path) || Area.INSTALLATION.siteFile(path);
    }
    return view.installer(path)
        || SiteSettings.holdsSiteValuesInWebapp(path)
        || SiteSettings.keptAsItIsInWebapp(path);
  }

  /** True when the file on disk is the vendor's, line ends aside for a text class. */
  static boolean same(FileClass cls, BaseFile base, Path file, FileOps files) {
    try {
      if (Files.size(file) == base.size() && files.sha256(file).equals(base.sha256())) {
        return true;
      }
      return cls.text()
          && base.textSha256().isPresent()
          && Sums.of(file).textSha256().equals(base.textSha256().get());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot hash " + file, e);
    }
  }

  /** As {@link #same(FileClass, BaseFile, Path, FileOps)}, for a file already hashed. */
  static boolean same(FileClass cls, BaseFile base, Sums mine) {
    if (mine.size() == base.size() && mine.sha256().equals(base.sha256())) {
      return true;
    }
    return cls.text()
        && base.textSha256().isPresent()
        && mine.textSha256().equals(base.textSha256().get());
  }

  /** What a package's copy of one file means for the file on disk. */
  public enum Verdict {
    /** Neither the site nor the vendor changed it. */
    UNTOUCHED("untouched", "none"),
    /** The package brings a file neither the base nor the server has. */
    NEW("new in the package", "add"),
    /** Only the vendor changed it. */
    VENDOR_ONLY("vendor change only", "replace"),
    /** Only the site changed it; the package ships the base's copy. */
    SITE_ONLY("site change only", "keep"),
    /** The site removed it and the package ships the base's copy. */
    SITE_REMOVED("removed by the site", "stays removed"),
    /** The file on disk is already the package's. */
    ALREADY_APPLIED("already applied", "none"),
    /** Both changed it: merged by class, or the package's copy wins for built and binary files. */
    COLLISION("collision", "merge"),
    /** The site removed it and the vendor changed it. */
    REMOVED_COLLISION("collision", "operator decides: stay removed, or take the package's"),
    /** A file the installer writes for this site: merged by key, or kept. */
    INSTALLER("installer-written", "site values kept");

    private final String label;
    private final String action;

    Verdict(String label, String action) {
      this.label = label;
      this.action = action;
    }

    public String label() {
      return label;
    }

    public String action() {
      return action;
    }
  }

  /**
   * One file the package ships, judged against the base and the disk. {@code path} is relative to
   * {@code area}; the hashes are those of the bytes; {@code base} and {@code mine} are empty when
   * there is no such file.
   */
  public record PackageItem(
      String path,
      FileClass fileClass,
      Verdict verdict,
      Optional<String> base,
      Optional<String> mine,
      String theirs,
      Area area) {

    /** The path as the package writes it, for an area whose root is {@code webappPrefix}'s. */
    public String packagePath(String webappPrefix) {
      return area == Area.WEBAPP ? webappPrefix + path : path;
    }

    /** True when the site's change is lost because this class of file is never merged. */
    public boolean overwritten() {
      return verdict == Verdict.COLLISION && !fileClass.mergeable();
    }

    /** True when the operator or a merge must decide before the package can be applied. */
    public boolean needsMerge() {
      return verdict == Verdict.REMOVED_COLLISION
          || (verdict == Verdict.COLLISION && fileClass.mergeable());
    }

    /** What the plan does with this file. */
    public String action() {
      return overwritten() ? "replace (the site's change is lost)" : verdict.action();
    }
  }

  /** Every file {@code contents} ships under the webapp, judged. */
  public static List<PackageItem> against(
      BaseView view, PackageContents contents, String webappName, Path webappDir, FileOps files) {
    return against(view, contents, webappName, webappDir, Optional.empty(), files);
  }

  /**
   * Every file {@code contents} ships under the webapp and, given {@code installDir} and a baseline
   * that knows the installation, under {@code buildomatic/} and {@code samples/}, judged; the
   * webapp's files first.
   */
  public static List<PackageItem> against(
      BaseView view,
      PackageContents contents,
      String webappName,
      Path webappDir,
      Optional<Path> installDir,
      FileOps files) {
    String prefix = PackagePaths.WEBAPPS_PREFIX + webappName + "/";
    BaseView installation = view.installation();
    // a WAR target's installation is a scratch directory with neither buildomatic nor samples
    boolean install =
        installDir.isPresent()
            && installation.covered()
            && Area.INSTALLATION_DIRS.stream()
                .anyMatch(d -> Files.isDirectory(installDir.get().resolve(d)));
    List<PackageItem> out = new ArrayList<>();
    List<PackageItem> installed = new ArrayList<>();
    for (PackageContents.VendorFile theirs : contents.vendorFiles()) {
      if (theirs.path().startsWith(prefix)) {
        String path = theirs.path().substring(prefix.length());
        out.add(judge(view, path, theirs, webappDir.resolve(path)));
      } else if (install && installationPath(theirs.path())) {
        installed.add(
            judge(installation, theirs.path(), theirs, installDir.get().resolve(theirs.path())));
      }
    }
    out.addAll(installed);
    return List.copyOf(out);
  }

  /** True when a package path is under one of the installation's directories a package writes. */
  public static boolean installationPath(String packagePath) {
    return Area.INSTALLATION_DIRS.stream().anyMatch(d -> packagePath.startsWith(d + "/"));
  }

  private static PackageItem judge(
      BaseView view, String path, PackageContents.VendorFile theirs, Path file) {
    FileClass cls = view.area().fileClass(path);
    Optional<BaseFile> base = view.file(path);
    Optional<String> baseHash = base.map(BaseFile::sha256);
    boolean theirsIsBase =
        base.isPresent()
            && (base.get().sha256().equals(theirs.sha256())
                || (cls.text()
                    && base.get().textSha256().equals(Optional.of(theirs.textSha256()))));
    if (!Files.isRegularFile(file)) {
      Verdict v =
          base.isEmpty()
              ? Verdict.NEW
              : theirsIsBase ? Verdict.SITE_REMOVED : Verdict.REMOVED_COLLISION;
      return new PackageItem(
          path, cls, v, baseHash, Optional.empty(), theirs.sha256(), view.area());
    }
    Sums mine;
    try {
      mine = Sums.of(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot hash " + file, e);
    }
    boolean mineIsTheirs =
        mine.sha256().equals(theirs.sha256())
            || (cls.text() && mine.textSha256().equals(theirs.textSha256()));
    boolean mineIsBase = base.isPresent() && same(cls, base.get(), mine);
    Verdict v;
    if (installer(view, path)) {
      v = mineIsTheirs ? Verdict.ALREADY_APPLIED : Verdict.INSTALLER;
    } else if (mineIsBase) {
      v = theirsIsBase ? Verdict.UNTOUCHED : Verdict.VENDOR_ONLY;
    } else if (mineIsTheirs) {
      v = Verdict.ALREADY_APPLIED;
    } else if (theirsIsBase) {
      v = Verdict.SITE_ONLY;
    } else {
      v = Verdict.COLLISION;
    }
    return new PackageItem(
        path, cls, v, baseHash, Optional.of(mine.sha256()), theirs.sha256(), view.area());
  }

  /**
   * The deployed external-authentication contexts and the samples of them the package changes,
   * which the operator must carry over by hand (0.2 design, 4.6); empty when there is nothing to
   * say.
   */
  public static Optional<String> externalAuthWarning(PackageContents contents, Path webappDir) {
    List<String> samples =
        contents.vendorFiles().stream()
            .map(PackageContents.VendorFile::path)
            .filter(p -> p.contains("samples/externalAuth-sample-config/"))
            .map(p -> p.substring(p.lastIndexOf('/') + 1))
            .sorted()
            .toList();
    List<String> deployed = externalAuth(webappDir);
    if (samples.isEmpty() || deployed.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "this server has external authentication deployed ("
            + String.join(", ", deployed)
            + ") and the package changes the samples it was made from ("
            + String.join(", ", samples)
            + "); port the changes into the deployed file by hand, as the readme says");
  }

  /** The deployed {@code WEB-INF/applicationContext-externalAuth*.xml} files, by name. */
  public static List<String> externalAuth(Path webappDir) {
    Path webInf = webappDir.resolve("WEB-INF");
    if (!Files.isDirectory(webInf)) {
      return List.of();
    }
    try (Stream<Path> list = Files.list(webInf)) {
      return list.filter(Files::isRegularFile)
          .map(p -> "WEB-INF/" + p.getFileName())
          .filter(p -> EXTERNAL_AUTH.matcher(p).matches())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + webInf, e);
    }
  }
}

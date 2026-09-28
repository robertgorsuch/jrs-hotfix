package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps package paths onto the server's file system: paths starting with {@code webapps/} are
 * relative to the Tomcat directory, everything else to the install directory. Invariants: both base
 * directories are absolute and normalised; a resolved path always stays inside its base (a path
 * with {@code ..}, a drive letter or a colon is refused); the WEB-INF test is purely textual, so it
 * needs no live layout.
 */
public record PackagePaths(Path installDir, Path tomcatDir) {

  public static final String WEBAPPS_PREFIX = "webapps/";
  private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:");

  public PackagePaths {
    installDir = Objects.requireNonNull(installDir, "installDir").toAbsolutePath().normalize();
    tomcatDir = Objects.requireNonNull(tomcatDir, "tomcatDir").toAbsolutePath().normalize();
  }

  /** Absolute target of a package path. */
  public Path resolve(String packagePath) {
    List<String> problems = pathProblems(packagePath);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(packagePath + ": " + String.join("; ", problems));
    }
    Path base = packagePath.startsWith(WEBAPPS_PREFIX) ? tomcatDir : installDir;
    Path target = base.resolve(packagePath).normalize();
    if (!target.startsWith(base)) {
      throw new IllegalArgumentException(packagePath + " escapes " + base);
    }
    return target;
  }

  /** Deepest directory containing both bases; the snapshot base for a hotfix. */
  public Path commonBase() {
    return commonAncestor(List.of(installDir, tomcatDir))
        .orElseThrow(
            () ->
                new HotfixException(
                    HotfixException.PRECHECK,
                    "install directory "
                        + installDir
                        + " and Tomcat directory "
                        + tomcatDir
                        + " share no common root",
                    "keep Tomcat under the install directory or on the same volume"));
  }

  /** True when the package path lies under {@code WEB-INF/lib} or {@code WEB-INF/classes}. */
  public static boolean requiresServiceStop(String packagePath) {
    // Case-insensitive on purpose (jrsctl review finding 1.15): Tomcat serves WEB-INF whatever its
    // spelling on a case-folding file system, and a package must not dodge the stop by case.
    String p = ("/" + packagePath.replace('\\', '/')).toLowerCase(Locale.ROOT);
    return p.contains("/web-inf/lib/") || p.contains("/web-inf/classes/");
  }

  /** Why {@code packagePath} is not acceptable; empty when it is. */
  public static List<String> pathProblems(String packagePath) {
    if (packagePath == null || packagePath.isBlank()) {
      return List.of("path is blank");
    }
    String p = packagePath.replace('\\', '/');
    if (p.startsWith("/")) {
      return List.of("path must be relative");
    }
    if (DRIVE.matcher(p).find() || p.contains(":")) {
      return List.of("path must not carry a drive letter or colon");
    }
    for (String segment : segments(p)) {
      if (segment.equals("..")) {
        return List.of("path must not contain '..'");
      }
      if (segment.isEmpty() || segment.equals(".")) {
        return List.of("path must not contain empty or '.' segments");
      }
    }
    if (p.endsWith("/")) {
      return List.of("path must name a file, not a directory");
    }
    return List.of();
  }

  /** True when {@code name} is a plain file name: no separator, not {@code .} or {@code ..}. */
  public static boolean isPlainFileName(String name) {
    return name != null
        && !name.isBlank()
        && !name.contains("/")
        && !name.contains("\\")
        && !name.equals(".")
        && !name.equals("..");
  }

  /** Deepest common ancestor of absolute paths; empty when they share no root. */
  public static Optional<Path> commonAncestor(List<Path> paths) {
    if (paths.isEmpty()) {
      return Optional.empty();
    }
    Path common = paths.get(0).toAbsolutePath().normalize();
    for (Path p : paths) {
      Path abs = p.toAbsolutePath().normalize();
      while (!abs.startsWith(common)) {
        Path parent = common.getParent();
        if (parent == null) {
          return Optional.empty();
        }
        common = parent;
      }
    }
    return Optional.of(common);
  }

  /** Lower-cased forward-slash form used for overlap comparisons within one OS. */
  public static String key(Path path) {
    return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  private static List<String> segments(String path) {
    List<String> out = new java.util.ArrayList<>();
    int start = 0;
    for (int i = 0; i <= path.length(); i++) {
      if (i == path.length() || path.charAt(i) == '/') {
        out.add(path.substring(start, i));
        start = i + 1;
      }
    }
    return out;
  }
}

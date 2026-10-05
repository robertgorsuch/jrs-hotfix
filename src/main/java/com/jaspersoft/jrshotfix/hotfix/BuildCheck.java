package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Compares the build the webapp states about itself with the package's, before a package is applied
 * (0.6 design, section 1): the packages are cumulative, so the build says what is installed.
 * Invariants: read-only; a problem refuses the apply (exit 2) and a warning does not; builds
 * compare as text, which is their order in time ({@code yyyymmdd_hhmm}); a package older than the
 * stated build is refused unless the operator allowed it, and then said as a warning; the same
 * build is always refused.
 */
record BuildCheck(List<String> problems, List<String> warnings) {

  /**
   * How the refusal of an older package ends, so the apply can recognize it and offer to allow it.
   */
  static final String TAKES_BACK = "applying it would take the server back";

  /** The file the stamps are read from, under the webapp. */
  private static final String BUILD_FILE = "WEB-INF/internal/jasperserver-pro.properties";

  BuildCheck {
    problems = List.copyOf(problems);
    warnings = List.copyOf(warnings);
  }

  static BuildCheck of(Path webappDir, PackageContents c) {
    return of(webappDir, c, false);
  }

  /**
   * As {@link #of(Path, PackageContents)}; with {@code allowOlder} an older package is a warning.
   */
  static BuildCheck of(Path webappDir, PackageContents c, boolean allowOlder) {
    Optional<InstalledBuild> stated = InstalledBuild.ofWebapp(webappDir);
    if (stated.isEmpty()) {
      return new BuildCheck(
          List.of(),
          List.of(
              "the webapp states no build ("
                  + BUILD_FILE
                  + " is absent or unreadable), so a hotfix applied outside jrs-hotfix would not be"
                  + " noticed"));
    }
    String installed = stated.get().build();
    int order = installed.compareTo(c.build());
    if (order == 0) {
      return new BuildCheck(
          List.of(c.id() + " is already installed (the webapp states build " + installed + ")"),
          List.of());
    }
    if (order > 0) {
      String older =
          "the webapp states build " + installed + ", newer than this package's " + c.build();
      if (allowOlder) {
        return new BuildCheck(
            List.of(),
            List.of(
                older
                    + ": applying it takes the server back, as allowed. Files only the newer build"
                    + " has stay, and a library it brought may stay beside this package's older"
                    + " one; when the newer build was applied with jrs-hotfix, `rollback` is the"
                    + " cleaner way back"));
      }
      return new BuildCheck(
          List.of(
              older
                  + ": "
                  + TAKES_BACK
                  + " (answer y at a terminal, or pass --allow-older, to apply it anyway)"),
          List.of());
    }
    return new BuildCheck(List.of(), List.of());
  }
}

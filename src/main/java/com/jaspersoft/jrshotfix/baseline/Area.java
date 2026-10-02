package com.jaspersoft.jrshotfix.baseline;

import java.util.List;
import java.util.Locale;

/**
 * The two places a hotfix package writes (0.7 design, section 1): the webapp, from {@code
 * jasperserver-pro.zip}, and the installation, from {@code js-install.zip}. Invariants: a path of
 * an area is relative to that area's root, with {@code /}; an installation path is the package's
 * own path, so it starts with one of {@link #INSTALLATION_DIRS}; the rules below are the only place
 * that knows how a path of either area is classified.
 */
public enum Area {
  WEBAPP,
  INSTALLATION;

  /** The top directories of the installation a package writes into. */
  public static final List<String> INSTALLATION_DIRS = List.of("buildomatic", "samples");

  /**
   * Installation files buildomatic writes for one site: the site's own settings, never shipped by
   * the vendor, so never a customization. Provisional until measured on a real 10.0.0 distribution
   * (0.7 design, open point 1).
   */
  private static final List<String> INSTALLATION_SITE_FILES =
      List.of("buildomatic/default_master.properties");

  /**
   * Installation directories buildomatic fills from the site's settings: built output, counted and
   * never merged. Provisional as above.
   */
  private static final List<String> INSTALLATION_GENERATED =
      List.of("buildomatic/build_conf/", "buildomatic/logs/");

  /** How a file of this area is compared and merged. */
  public FileClass fileClass(String path) {
    return this == WEBAPP ? FileClass.of(path) : FileClass.ofInstallation(path);
  }

  /** True when the file is the site's own settings, written for this site; never compared. */
  public boolean siteFile(String path) {
    return this == INSTALLATION && INSTALLATION_SITE_FILES.contains(lower(path));
  }

  /** True when the file is built from the site's settings or written at run time. */
  public boolean generated(String path) {
    String p = lower(path);
    return this == INSTALLATION && INSTALLATION_GENERATED.stream().anyMatch(p::startsWith);
  }

  /** The name the area is shown and stored under. */
  public String label() {
    return this == WEBAPP ? "webapp" : "installation";
  }

  /** The area of a stored label; an absent or unknown one is the webapp, as before 0.7. */
  public static Area of(String label) {
    return "installation".equals(label) ? INSTALLATION : WEBAPP;
  }

  private static String lower(String path) {
    return path.replace('\\', '/').toLowerCase(Locale.ROOT);
  }
}

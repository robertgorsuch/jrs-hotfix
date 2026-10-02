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
   * Installation files written for one site, by the site or by the installer, with its values in
   * them: never a customization, and never replaced by a package. A path ending in {@code /} is a
   * directory and everything under it. Measured on a 10.0.0 installation (0.7 design, open point
   * 1): {@code default_master.properties}; the keystore's location; the install path in {@code
   * js-import-export.sh}; the sample data sources and XMLA connections with this site's database
   * address, port and passwords.
   */
  private static final List<String> INSTALLATION_SITE_FILES =
      List.of(
          "buildomatic/default_master.properties",
          "buildomatic/keystore.init.properties",
          "buildomatic/bin/js-import-export.sh",
          "buildomatic/install_resources/export/js-catalog/");

  /**
   * Installation files buildomatic or the installer builds from the site's settings, or writes at
   * run time: counted and never merged. As above, with {@code /} for a directory; measured as the
   * site files are, {@code iecp.jar} being a manifest-only jar written at install and {@code
   * js-mvn} the Maven launcher {@code js-ant} writes with this site's paths.
   */
  private static final List<String> INSTALLATION_GENERATED =
      List.of(
          "buildomatic/build_conf/",
          "buildomatic/logs/",
          "buildomatic/js-mvn",
          "buildomatic/conf_source/iepro/lib/iecp.jar");

  /** How a file of this area is compared and merged. */
  public FileClass fileClass(String path) {
    return this == WEBAPP ? FileClass.of(path) : FileClass.ofInstallation(path);
  }

  /** True when the file is the site's own settings, written for this site; never compared. */
  public boolean siteFile(String path) {
    return this == INSTALLATION && listed(INSTALLATION_SITE_FILES, lower(path));
  }

  /** True when the file is built from the site's settings or written at run time. */
  public boolean generated(String path) {
    return this == INSTALLATION && listed(INSTALLATION_GENERATED, lower(path));
  }

  /** The name the area is shown and stored under. */
  public String label() {
    return this == WEBAPP ? "webapp" : "installation";
  }

  /** The area of a stored label; an absent or unknown one is the webapp, as before 0.7. */
  public static Area of(String label) {
    return "installation".equals(label) ? INSTALLATION : WEBAPP;
  }

  /** Whether {@code path} is one of {@code entries}, or under one that ends in {@code /}. */
  private static boolean listed(List<String> entries, String path) {
    return entries.stream().anyMatch(e -> e.endsWith("/") ? path.startsWith(e) : path.equals(e));
  }

  private static String lower(String path) {
    return path.replace('\\', '/').toLowerCase(Locale.ROOT);
  }
}

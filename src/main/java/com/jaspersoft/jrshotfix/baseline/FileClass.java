package com.jaspersoft.jrshotfix.baseline;

import java.util.Locale;

/**
 * How a file of the webapp is compared and merged, decided by its path alone (0.2 design, 4.1).
 * Invariants: the path is relative to the webapp, with {@code /}; the classes are tried in the
 * order X, P, G, T, B and the first match wins; matching ignores case.
 */
public enum FileClass {
  /** Reviewed XML: Spring contexts, {@code web.xml}, everything else XML under WEB-INF. */
  X(true, true),
  /** Properties files, merged by key. */
  P(true, true),
  /** Built output: scripts and stylesheets. Never merged; the package's copy wins. */
  G(true, false),
  /** Text templates: JSPs, tags, HTML. Merged by line. */
  T(true, true),
  /** Everything else, binary. Never merged; the package's copy wins. */
  B(false, false);

  private final boolean text;
  private final boolean mergeable;

  FileClass(boolean text, boolean mergeable) {
    this.text = text;
    this.mergeable = mergeable;
  }

  /** True when line ends do not count in a comparison. */
  public boolean text() {
    return text;
  }

  /** True when a change of the site and a change of the vendor can be merged. */
  public boolean mergeable() {
    return mergeable;
  }

  public static FileClass of(String webappPath) {
    String p = webappPath.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (p.endsWith(".xml") && (p.startsWith("web-inf/") || p.startsWith("meta-inf/"))) {
      return X;
    }
    if (p.endsWith(".properties")) {
      return P;
    }
    if (p.startsWith("scripts/")
        || p.startsWith("optimized-scripts/")
        || p.endsWith(".js")
        || p.endsWith(".js.map")
        || p.endsWith(".css")) {
      return G;
    }
    if (p.endsWith(".jsp")
        || p.endsWith(".jspf")
        || p.endsWith(".tag")
        || p.endsWith(".tld")
        || p.endsWith(".html")) {
      return T;
    }
    return B;
  }

  /**
   * The class of a file of the installation ({@code buildomatic/}, {@code samples/}), by its path
   * relative to the installation (0.7 design, section 1): XML (Ant builds, configuration templates)
   * is reviewed, properties are merged by key, the scripts and SQL a site edits are text merged by
   * line, everything else is binary. Nothing here is built output the vendor would regenerate.
   */
  public static FileClass ofInstallation(String installationPath) {
    String p = installationPath.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (p.endsWith(".xml") || p.endsWith(".xsl") || p.endsWith(".xsd")) {
      return X;
    }
    if (p.endsWith(".properties")) {
      return P;
    }
    if (p.endsWith(".sh")
        || p.endsWith(".bat")
        || p.endsWith(".cmd")
        || p.endsWith(".sql")
        || p.endsWith(".txt")
        || p.endsWith(".groovy")
        || p.endsWith(".template")
        || p.endsWith(".conf")
        || p.endsWith(".policy")
        || p.endsWith(".json")) {
      return T;
    }
    return B;
  }
}

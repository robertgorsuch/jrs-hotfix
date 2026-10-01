package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.platform.Zips;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * How an official package's outer archive is laid out: where its readme is and which entries are
 * payload. Invariants: judged on names alone, without reading a payload; nothing is written.
 */
final class PackageLayout {

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

  static final String README = "readme.txt";

  private PackageLayout() {}

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
      while ((e = Zips.nextFile(z)) != null) {
        String name = Zips.name(e);
        if (name.equals(BUNDLE_MANIFEST)) {
          return Optional.empty();
        }
        names.add(name);
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
}

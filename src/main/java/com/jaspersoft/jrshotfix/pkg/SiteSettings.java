package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.platform.Sums;
import com.jaspersoft.jrshotfix.text.PropertiesMerge;
import com.jaspersoft.jrshotfix.text.Text;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The webapp's properties files the installer fills in for one site (the scheduler's public
 * address, the database connection, the keystore location). On every server they differ from the
 * vendor's copy, so a hotfix that ships one must not simply replace it: what lands is the package's
 * file with this server's values ({@link PropertiesMerge}). Invariants: only the files listed here
 * are merged, and only when the server has the file; a file larger than {@link #MAX_BYTES} is never
 * merged, so what is held in memory is bounded (these files are a few kilobytes; the bound is what
 * allows reading one whole, which the payload never is); bytes are read and written as ISO-8859-1,
 * which maps every byte to itself, in the package's line ends; the site's XML files ({@link
 * #keptAsItIs}) are not merged at all, the server's stays; nothing is written here.
 */
public final class SiteSettings {

  /** A settings file larger than this is replaced as any other file, with the usual warning. */
  public static final int MAX_BYTES = 1 << 20;

  private static final Set<String> FILES =
      Set.of(
          "web-inf/js.quartz.properties",
          "web-inf/js.jdbc.properties",
          "web-inf/classes/hibernate.properties",
          "web-inf/classes/keystore.init.properties");

  /**
   * The webapp's XML files the installer writes for one site: the container's context with the
   * database connection, and the data source definitions beside it. There is no merging XML by key,
   * so a hotfix never replaces one the server has.
   */
  private static final Pattern SITE_XML = Pattern.compile("meta-inf/(context|[^/]+-jdbc)\\.xml");

  private SiteSettings() {}

  /** True when {@code packagePath} is a site-written XML file under a webapp, never replaced. */
  public static boolean keptAsItIs(String packagePath) {
    return underWebapp(packagePath).filter(p -> SITE_XML.matcher(p).matches()).isPresent();
  }

  /** The lower-cased path under the webapp; empty for a path outside one. */
  private static Optional<String> underWebapp(String packagePath) {
    if (!packagePath.startsWith(PackagePaths.WEBAPPS_PREFIX)) {
      return Optional.empty();
    }
    int webapp = packagePath.indexOf('/', PackagePaths.WEBAPPS_PREFIX.length());
    return webapp > 0
        ? Optional.of(packagePath.substring(webapp + 1).toLowerCase(Locale.ROOT))
        : Optional.empty();
  }

  /** True when {@code packagePath} is one of these files under a webapp. */
  public static boolean holdsSiteValues(String packagePath) {
    return underWebapp(packagePath).filter(FILES::contains).isPresent();
  }

  /**
   * What lands in place of the package's file: its text, one character per byte, the hash of those
   * bytes, and what was kept of this server's file, by key name only (a value may be a password).
   */
  public record Merged(String text, String sha256, List<String> kept, List<String> carried) {
    public Merged {
      kept = List.copyOf(kept);
      carried = List.copyOf(carried);
    }

    /** The file as it is written. */
    public byte[] bytes() {
      return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The text is the server's settings, which may hold a secret: it is never printed. */
    @Override
    public String toString() {
      return "Merged[" + sha256 + ", kept=" + kept + ", carried=" + carried + "]";
    }
  }

  /** The whole of {@code in} when it is at most {@link #MAX_BYTES} long, else empty. */
  public static Optional<byte[]> bounded(InputStream in) throws IOException {
    byte[] bytes = in.readNBytes(MAX_BYTES + 1);
    return bytes.length > MAX_BYTES ? Optional.empty() : Optional.of(bytes);
  }

  /**
   * The server's file {@code mine} merged into the package's {@code theirs}; empty when the server
   * has nothing to keep (the package's file lands as it is), or when the server's file is absent or
   * too large to merge.
   */
  public static Optional<Merged> merge(Path mine, byte[] theirs) throws IOException {
    if (!Files.isRegularFile(mine) || Files.size(mine) > MAX_BYTES) {
      return Optional.empty();
    }
    Optional<byte[]> site;
    try (InputStream in = Files.newInputStream(mine)) {
      site = bounded(in);
    }
    return site.flatMap(bytes -> merge(bytes, theirs));
  }

  static Optional<Merged> merge(byte[] mine, byte[] theirs) {
    PropertiesMerge.Merged result = PropertiesMerge.merge(lines(mine), lines(theirs));
    if (result.kept().isEmpty() && result.carried().isEmpty()) {
      return Optional.empty();
    }
    byte[] bytes = Text.of(theirs).bytes(result.lines());
    return Optional.of(
        new Merged(
            new String(bytes, StandardCharsets.ISO_8859_1),
            Sums.of(bytes).sha256(),
            result.kept(),
            result.carried()));
  }

  /**
   * The lines of a settings file, one character per byte, ended by LF or CR LF. Unlike {@link
   * Text#of}, a CR at the very end with no LF after it stays in the last line: it is part of the
   * value there, as it always was.
   */
  static List<String> lines(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.ISO_8859_1);
    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
    // the text after the last line end is a line only when there is some
    if (lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    return lines;
  }
}

package com.jaspersoft.jrshotfix.pkg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds official-looking packages and fake installations for tests. */
public final class Packages {
  public static final String OUTER_README =
      """
      JasperReports Server Pro hotfix
      Product: JasperReports Server Pro
      Release version: 10.0.0
      Build version: [20260730_0457]
      Installation
      1. Stop the server
      """;
  public static final String WEBAPP_README =
      """
      Added files:
      WEB-INF/lib/new-1.0.jar
      Modified files:
      WEB-INF/lib/foo-1.2.3.jar
      Deleted files:
      WEB-INF/lib/bar-0.9.jar
      WEB-INF/lib/never-installed-1.0.jar
      IMPORTANT
      If an earlier hotfix is installed delete
      WEB-INF/lib/foo-1.*.jar
      Additional Notes:
      For PostgreSQL run the SQL in js-install/sql/postgresql.sql
      """;
  public static final String LIB = "WEB-INF/lib/";

  private Packages() {}

  public static byte[] zipBytes(Map<String, String> files, String readme) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
      if (readme != null) {
        zos.putNextEntry(new ZipEntry("readme.txt"));
        zos.write(readme.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      for (Map.Entry<String, String> e : files.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }
    return buffer.toByteArray();
  }

  public static Path zip(Path file, Map<String, byte[]> entries) throws IOException {
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zos = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return file;
  }

  /** readme.txt + jasperserver-pro.zip + js-install.zip, standard names. */
  public static Path standard(Path file) throws IOException {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        zipBytes(
            Map.of(LIB + "foo-1.2.3.jar", "patched foo", LIB + "new-1.0.jar", "brand new"),
            WEBAPP_README));
    outer.put(
        "js-install.zip", zipBytes(Map.of("buildomatic/lib/tool-2.0.jar", "patched tool"), null));
    return zip(file, outer);
  }

  /**
   * A later hotfix for the same release: it replaces {@code foo-1.2.3.jar} again and deletes
   * nothing, so it owns a file the standard package also owns.
   */
  public static Path later(Path file) throws IOException {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put(
        "readme.txt",
        OUTER_README
            .replace("[20260730_0457]", "[20260830_0100]")
            .getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        zipBytes(
            Map.of(LIB + "foo-1.2.3.jar", "later foo"),
            """
            Modified files:
            WEB-INF/lib/foo-1.2.3.jar
            """));
    return zip(file, outer);
  }

  /** The id {@link #later} builds to. */
  public static String laterId() {
    return "JRSHF-10.0.0-20260830-0100";
  }

  /** A fake installation: tomcat under installDir, webapp with the jars a package expects. */
  public static PackagePaths install(Path installDir) throws IOException {
    Path tomcat = installDir.resolve("apache-tomcat");
    Path lib = tomcat.resolve("webapps/jasperserver-pro/" + LIB);
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("jasperserver-api-10.0.0.jar"), "api");
    Files.writeString(lib.resolve("foo-1.2.3.jar"), "old foo");
    Files.writeString(lib.resolve("foo-1.0.0.jar"), "older foo left by an earlier hotfix");
    Files.writeString(lib.resolve("bar-0.9.jar"), "bar");
    Files.createDirectories(installDir.resolve("buildomatic/lib"));
    Files.writeString(installDir.resolve("buildomatic/lib/tool-2.0.jar"), "old tool");
    return new PackagePaths(installDir, tomcat);
  }
}

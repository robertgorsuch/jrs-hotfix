package com.jaspersoft.jrshotfix.home;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The build a webapp states about itself in {@code WEB-INF/internal/jasperserver-pro.properties}.
 * Every cumulative hotfix ships that file, so the build is the hotfix level of the files on disk,
 * whoever put them there, and equals the tail of the hotfix id {@code JRSHF-<release>-<date>-
 * <time>}. Invariants: read-only; never throws, an absent or unreadable file or a stamp that is not
 * a date and a time is empty; {@code date} is eight digits and {@code time} four. The Community
 * edition states no build in its own file, so its webapp reads as empty.
 */
public record InstalledBuild(String release, String date, String time) {

  private static final String FILE = "WEB-INF/internal/jasperserver-pro.properties";
  private static final Pattern DATE = Pattern.compile("[0-9]{8}");
  private static final Pattern TIME = Pattern.compile("[0-9]{4}");

  /** The build as a package readme writes it, for example {@code 20260730_0457}. */
  public String build() {
    return date + "_" + time;
  }

  /** The build {@code webappDir} states; empty when it states none. */
  public static Optional<InstalledBuild> ofWebapp(Path webappDir) {
    Path file = webappDir.resolve(FILE);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    String release = "";
    String date = "";
    String time = "";
    try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      String line;
      while ((line = in.readLine()) != null) {
        // the vendor's build indents the two stamps
        String text = line.strip();
        int eq = text.indexOf('=');
        if (eq < 0 || text.startsWith("#")) {
          continue;
        }
        String value = text.substring(eq + 1).strip();
        switch (text.substring(0, eq).strip()) {
          case "PRO_VERSION" -> release = value;
          case "BUILD_DATE_STAMP" -> date = value;
          case "BUILD_TIME_STAMP" -> time = value;
          default -> {}
        }
      }
    } catch (IOException | UncheckedIOException e) {
      return Optional.empty();
    }
    if (release.isEmpty() || !DATE.matcher(date).matches() || !TIME.matcher(time).matches()) {
      return Optional.empty();
    }
    return Optional.of(new InstalledBuild(release, date, time));
  }

  /**
   * One phrase for the header and {@code list}: the release the jar names state, the edition the
   * webapp name states, and the build when the webapp states one, for example {@code 10.0.0 PRO,
   * build 20260730_0457}.
   */
  public static String describe(Path webappDir) {
    Path name = webappDir.getFileName();
    boolean pro = name != null && name.toString().endsWith("-pro");
    return JrsVersion.ofWebapp(webappDir).orElse("unknown")
        + (pro ? " PRO" : " CE")
        + ofWebapp(webappDir).map(b -> ", build " + b.build()).orElse("");
  }
}

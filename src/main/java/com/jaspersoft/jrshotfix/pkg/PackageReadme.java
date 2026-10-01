package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the readmes of an official package say: the outer readme's release and build ({@link
 * Header}), an inner readme's deletions and manual steps ({@link Readme}), and the notes they turn
 * into ({@link Notes}). Invariants: a readme is read as ISO-8859-1, line by line; nothing is
 * written.
 */
final class PackageReadme {

  // Case-insensitive, and "Product:" as well as "Product Name:": readmes differ in capitalisation.
  private static final Pattern PRODUCT = Pattern.compile("(?i)^Product(?: Name)?:\\s*(.+?)\\s*$");
  private static final Pattern RELEASE = Pattern.compile("(?i)^Release Version:\\s*([0-9.]+)\\s*$");
  private static final Pattern BUILD =
      Pattern.compile("(?i)^Build version:\\s*\\[?([0-9_]+)]?\\s*$");
  private static final Pattern BUILD_ID = Pattern.compile("^([0-9]{8})_([0-9]{4})$");

  /** A line of the readme that names one file, with or without a {@code *} in its last segment. */
  private static final Pattern LISTED_PATH = Pattern.compile("^[A-Za-z0-9._*/-]+$");

  private PackageReadme() {}

  static List<String> readLines(InputStream in) throws IOException {
    List<String> lines = new ArrayList<>();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
    String line;
    while ((line = reader.readLine()) != null) {
      // indentation is kept: the readme's SQL and numbered steps go into the notes as they are
      lines.add(line.stripTrailing());
    }
    return lines;
  }

  /**
   * The notes of one package, in the order they were given. Invariants: a sentence of jrs-hotfix's
   * own is said once; a line of the readme is never dropped because an equal line came before it in
   * the same section (SQL repeats its lines); a section both inner readmes hold, blank lines aside,
   * is carried once.
   */
  static final class Notes {
    private final List<PackageContents.Note> lines = new ArrayList<>();
    private final Set<String> said = new HashSet<>();
    private final Set<String> conditionsSeen = new HashSet<>();
    private final Set<List<String>> manualSeen = new HashSet<>();

    void say(String sentence) {
      if (said.add(sentence)) {
        lines.add(PackageContents.Note.said(sentence));
      }
    }

    /** A sentence of jrs-hotfix's own, then lines of a file of the package as the file has them. */
    void sayAndQuote(String sentence, List<String> fileLines) {
      say(sentence);
      quote(fileLines);
    }

    private void quote(List<String> readmeLines) {
      readmeLines.forEach(line -> lines.add(PackageContents.Note.quoted(line)));
    }

    /** The Important section's sentences; one an earlier readme gave is not repeated. */
    void conditions(List<String> conditions) {
      List<String> fresh = conditions.stream().filter(c -> !conditionsSeen.contains(c)).toList();
      if (fresh.isEmpty()) {
        return;
      }
      conditionsSeen.addAll(fresh);
      say(
          "the package readme's Important section names conditions to check by hand (an earlier"
              + " build, source map files); read readme.txt in the package");
      quote(fresh);
    }

    /** The Additional Notes section, whole and in the readme's own lines. */
    void manual(List<String> manual) {
      List<String> text =
          manual.stream().map(String::strip).filter(line -> !line.isEmpty()).toList();
      if (text.isEmpty() || !manualSeen.add(text)) {
        return;
      }
      say(
          "the package readme's Additional Notes section describes manual steps, such as SQL for"
              + " some databases and optional properties; jrs-hotfix runs none of them");
      quote(manual);
    }

    List<PackageContents.Note> lines() {
      return List.copyOf(lines);
    }
  }

  /** What the outer readme says about the package as a whole. */
  record Header(String release, String edition, String build) {

    static Header parse(List<String> lines) {
      String release = "";
      String edition = "PRO";
      String build = "";
      for (String raw : lines) {
        String line = raw.strip();
        Matcher m = RELEASE.matcher(line);
        if (m.matches()) {
          release = m.group(1);
        }
        m = BUILD.matcher(line);
        if (m.matches()) {
          build = m.group(1);
        }
        m = PRODUCT.matcher(line);
        if (m.matches()) {
          edition = m.group(1).toLowerCase(Locale.ROOT).contains("pro") ? "PRO" : "CE";
        }
      }
      if (release.isEmpty() || build.isEmpty()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the package readme names no release and build version",
            "point jrs-hotfix at an official cumulative hotfix ZIP");
      }
      return new Header(release, edition, build);
    }

    /** Derived id, for example {@code JRSHF-10.0.0-20260730-0457}. */
    String id() {
      Matcher m = BUILD_ID.matcher(build);
      if (!m.matches()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the package build version is not a date and time: " + build,
            "point jrs-hotfix at an official cumulative hotfix ZIP");
      }
      return "JRSHF-" + release + "-" + m.group(1) + "-" + m.group(2);
    }

    String title() {
      return "JasperReports Server "
          + (edition.equals("PRO") ? "Pro " : "")
          + release
          + " cumulative hotfix "
          + build;
    }
  }

  /**
   * The parts of an inner readme jrs-hotfix acts on: what to delete, and what to do by hand. {@code
   * conditions} are the sentences of the Important section; {@code manual} is the Additional Notes
   * section as the readme has it, indentation and inner blank lines included, every line of it.
   */
  record Readme(
      List<String> deleted, List<String> globs, List<String> conditions, List<String> manual) {

    static Readme empty() {
      return new Readme(List.of(), List.of(), List.of(), List.of());
    }

    static Readme parse(String prefix, List<String> lines) {
      List<String> deleted = new ArrayList<>();
      List<String> globs = new ArrayList<>();
      List<String> manual = new ArrayList<>();
      List<String> conditions = new ArrayList<>();
      Section section = Section.NONE;
      for (String raw : lines) {
        String line = raw.strip();
        Section heading = Section.of(line);
        if (heading != null) {
          section = heading;
          continue;
        }
        if (line.startsWith("=====")) {
          section = Section.NONE;
          continue;
        }
        if (section == Section.NOTES) {
          manual.add(raw);
          continue;
        }
        if (line.isEmpty()) {
          continue;
        }
        boolean path = LISTED_PATH.matcher(line).matches();
        switch (section) {
          case DELETED -> {
            if (path && !line.contains("*")) {
              deleted.add(prefix + line);
            }
          }
          case IMPORTANT -> {
            if (path && line.contains("*")) {
              globs.add(prefix + line);
            } else {
              conditions.add(line);
            }
          }
          case NOTES, NONE -> {}
        }
      }
      return new Readme(deleted, globs, conditions, withoutOuterBlankLines(manual));
    }

    private static List<String> withoutOuterBlankLines(List<String> lines) {
      int from = 0;
      int to = lines.size();
      while (from < to && lines.get(from).isBlank()) {
        from++;
      }
      while (to > from && lines.get(to - 1).isBlank()) {
        to--;
      }
      return List.copyOf(lines.subList(from, to));
    }

    private enum Section {
      NONE,
      DELETED,
      IMPORTANT,
      NOTES;

      static Section of(String line) {
        return switch (line) {
          case "Deleted files:" -> DELETED;
          case "IMPORTANT" -> IMPORTANT;
          case "Additional Notes:" -> NOTES;
          case "Added files:", "Modified files:", "Installation", "Uninstallation" -> NONE;
          default -> null;
        };
      }
    }
  }
}

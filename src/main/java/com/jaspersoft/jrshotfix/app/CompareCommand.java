package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.compare.Compare;
import com.jaspersoft.jrshotfix.compare.Input;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.redact.Redactor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix compare <a> <b> [<c>]}: what differs between two webapps or installations, or
 * what each of the second and third changed from the first and where they meet (0.7 design, section
 * 3). Invariants: nothing an input names is written; the home is opened only when an input is
 * {@code server}; exit 0 when the inputs are the same, 7 when they differ or a three-way comparison
 * has conflicts, 2 when an input or {@code --out} cannot be used, 6 when an input is not a
 * JasperReports Server webapp, distribution or package.
 */
@Command(
    name = "compare",
    mixinStandardHelpOptions = true,
    description =
        "Compare two WARs, webapp directories, distributions or packages, or three to see what the"
            + " second and the third each changed from the first; changes nothing.",
    footer = {"", "Example:", "  jrs-hotfix compare jasperserver-pro.war server"})
final class CompareCommand extends AppCommand {

  @Parameters(
      arity = "2..3",
      paramLabel = "<input>",
      description =
          "A WAR, a webapp directory, a distribution (its ZIP or directory), an official hotfix"
              + " package, or `server` for this home's server. With three: base, mine, theirs.")
  List<String> inputs;

  @Option(
      names = "--out",
      paramLabel = "<dir>",
      description = "With three inputs: write the merged result into this new or empty directory.")
  Path out;

  @Option(
      names = "--show",
      paramLabel = "<path>",
      description = "Print the differences of one file instead of the report.")
  String show;

  @Override
  public Integer call() {
    if (out != null && inputs.size() != 3) {
      return ExitCodes.fail(
          err(),
          ExitCodes.USAGE,
          "--out writes a three-way result: it needs three inputs",
          Optional.empty());
    }
    if (out != null && !empty(out)) {
      return ExitCodes.fail(
          err(),
          ExitCodes.PRECHECK_FAILED,
          out + " exists and is not empty",
          Optional.of("name a new or empty directory for --out"));
    }
    Optional<Settings> server =
        inputs.contains(Input.SERVER) ? open().settings() : Optional.empty();
    Path temp = Path.of(System.getProperty("java.io.tmpdir"), "jrs-hotfix-compare");
    List<Input> opened = new ArrayList<>();
    try {
      for (String arg : inputs) {
        opened.add(Input.open(arg, server, temp));
      }
      return show == null ? report(opened) : show(opened);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      opened.forEach(Input::close);
    }
  }

  private int show(List<Input> opened) throws IOException {
    List<String> lines = Compare.show(show, opened);
    print(lines);
    return lines.isEmpty() ? ExitCodes.SUCCESS : ExitCodes.DIFFERENT;
  }

  private int report(List<Input> opened) throws IOException {
    boolean three = opened.size() == 3;
    Compare.Report r =
        three
            ? Compare.threeWay(opened.get(0), opened.get(1), opened.get(2))
            : Compare.twoWay(opened.get(0), opened.get(1));
    List<String> lines = new ArrayList<>();
    if (three) {
      lines.add(
          "base "
              + opened.get(0).label()
              + ", mine "
              + opened.get(1).label()
              + ", theirs "
              + opened.get(2).label());
    } else {
      lines.add(opened.get(0).label() + " against " + opened.get(1).label());
    }
    for (Area area : r.compared()) {
      List<Compare.Item> items = r.items().stream().filter(i -> i.area() == area).toList();
      lines.add("");
      lines.add(area.label() + ": " + (items.isEmpty() ? "the same" : items.size() + " file(s)"));
      TextTable table = table();
      for (Compare.Item i : items) {
        table.row(i.kind().label(), i.fileClass().name(), i.path(), i.note());
      }
      table.lines().forEach(l -> lines.add("  " + l));
    }
    for (Area area : r.skipped()) {
      lines.add("");
      lines.add(area.label() + ": not compared, not every input has it");
    }
    lines.add("");
    lines.add(summary(r, three));
    if (out != null) {
      Compare.write(r, opened.get(0), opened.get(1), opened.get(2), out);
      lines.add("the result is in " + out + ", one directory per area");
    }
    print(lines);
    return r.differ() ? ExitCodes.DIFFERENT : ExitCodes.SUCCESS;
  }

  private static String summary(Compare.Report r, boolean three) {
    String counts =
        three
            ? r.count(Compare.Kind.ONLY_MINE)
                + " in mine only, "
                + r.count(Compare.Kind.ONLY_THEIRS)
                + " in theirs only, "
                + r.count(Compare.Kind.BOTH_ALIKE)
                + " alike, "
                + r.count(Compare.Kind.MERGED)
                + " merged, "
                + r.count(Compare.Kind.CONFLICT)
                + " conflict(s)"
            : r.count(Compare.Kind.DIFFERS)
                + " differ, "
                + r.count(Compare.Kind.ONLY_FIRST)
                + " only in the first, "
                + r.count(Compare.Kind.ONLY_SECOND)
                + " only in the second";
    return counts
        + "; "
        + r.same()
        + " the same; "
        + r.generated()
        + " built or written at run time, not compared. Classes: X reviewed XML, P properties, T"
        + " pages and text, G scripts and stylesheets, B binary";
  }

  private void print(List<String> lines) {
    PrintWriter o = out();
    for (String line : lines) {
      o.println(Redactor.global().redact(line.stripTrailing()));
    }
    o.flush();
  }

  private static boolean empty(Path dir) {
    if (!Files.exists(dir)) {
      return true;
    }
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.findAny().isEmpty();
    } catch (IOException e) {
      return false;
    }
  }
}

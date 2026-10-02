package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.hotfix.HotfixRuntime;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.scan.Scan;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix verify <package.zip> [--war <file.war>]}: what the package is, whether it
 * applies here, and, when a baseline fits, where the site and the hotfix changed the same file.
 * Invariants: writes nothing on the server (with {@code --war}, the WAR's unpacked copy in the
 * home); exit 0 when it applies, 2 when it is readable but does not apply (or cannot be read), 6
 * when the file is not an official hotfix package at all; the reasons go to standard error.
 */
@Command(
    name = "verify",
    mixinStandardHelpOptions = true,
    description =
        "Check a hotfix package against this installation and, with a baseline, against what"
            + " this site changed; changes nothing.",
    footer = {"", "Example:", "  jrs-hotfix verify <zip>"})
final class VerifyCommand extends AppCommand {

  @Parameters(index = "0", paramLabel = "<package.zip>", description = "The hotfix ZIP.")
  Path file;

  @Option(
      names = "--war",
      paramLabel = "<file.war>",
      description =
          "Verify against this WAR instead of the server. The home is --home, else jrs-hotfix"
              + " beside the WAR.")
  Path war;

  @Override
  public Integer call() {
    Bootstrap boot = war == null ? open() : open().forWar(war);
    HotfixPlans plans = boot.plans();
    HotfixPlans.VerifyReport r = plans.verify(file);
    PrintWriter out = out();
    if (r.readable()) {
      out.println(r.id() + "  " + r.title());
      out.println("  release  " + r.release() + " " + r.edition());
      out.println(
          "  files    "
              + r.adds().size()
              + " to add, "
              + r.replaces().size()
              + " to replace, "
              + r.deletes().size()
              + " to delete");
      section(out, "add", r.adds());
      section(out, "replace", r.replaces());
      section(out, "delete", r.deletes());
      section(out, "notes", r.notes());
      for (String line : againstTheSite(plans)) {
        out.println(boot.redactor().redact(line.stripTrailing()));
      }
      out.println(r.applicable() ? "applies to this installation" : "does not apply here");
      out.flush();
    }
    PrintWriter err = err();
    for (String problem : r.problems()) {
      err.println(boot.redactor().redact("error: " + problem));
    }
    err.flush();
    if (r.ok()) {
      return ExitCodes.SUCCESS;
    }
    boolean notAPackage =
        !r.readable() && Files.isRegularFile(file) && !OfficialPackage.looksOfficial(file);
    return notAPackage ? ExitCodes.UNSUPPORTED : ExitCodes.PRECHECK_FAILED;
  }

  private static void section(PrintWriter out, String heading, List<String> items) {
    if (items.isEmpty()) {
      return;
    }
    out.println(heading);
    for (String item : items) {
      out.println("  " + item);
    }
  }

  /**
   * Every file the package ships under the webapp, judged against the vendor's and the site's (what
   * {@code scan --package} printed before 0.6): the files where they meet, then the counts. Empty
   * when no baseline fits this webapp, so a server without one sees what it always saw.
   */
  private List<String> againstTheSite(HotfixPlans plans) {
    HotfixRuntime rt = plans.runtime();
    BaseView.Resolution resolution = rt.baseView();
    if (resolution.view().isEmpty()) {
      return List.of();
    }
    PackageContents contents = plans.readPackage(file);
    List<Scan.PackageItem> items =
        Scan.against(
            resolution.view().get(),
            contents,
            rt.settings().webappName(),
            rt.settings().webappDir(),
            Optional.of(rt.settings().installDir()),
            rt.files());
    long underWebapp = items.stream().filter(i -> i.area() == Area.WEBAPP).count();
    List<String> lines = new ArrayList<>();
    lines.add(
        "against this site ("
            + underWebapp
            + " files under the webapp"
            + (items.size() > underWebapp
                ? ", " + (items.size() - underWebapp) + " under the installation"
                : "")
            + ")");
    Map<Scan.Verdict, Integer> counts = new EnumMap<>(Scan.Verdict.class);
    TextTable table = new TextTable();
    boolean any = false;
    for (Scan.PackageItem item : items) {
      counts.merge(item.verdict(), 1, Integer::sum);
      switch (item.verdict()) {
        case UNTOUCHED, NEW, VENDOR_ONLY, ALREADY_APPLIED -> {}
        case SITE_ONLY, SITE_REMOVED, COLLISION, REMOVED_COLLISION, INSTALLER -> {
          if (!any) {
            table.row("PATH", "CLASS", "VERDICT", "ACTION");
            any = true;
          }
          table.row(item.path(), item.fileClass().name(), item.verdict().label(), item.action());
        }
      }
    }
    table.lines().forEach(l -> lines.add("  " + l));
    lines.add(
        "  "
            + counts.getOrDefault(Scan.Verdict.VENDOR_ONLY, 0)
            + " replaced (vendor change only), "
            + counts.getOrDefault(Scan.Verdict.NEW, 0)
            + " added, "
            + (counts.getOrDefault(Scan.Verdict.UNTOUCHED, 0)
                + counts.getOrDefault(Scan.Verdict.ALREADY_APPLIED, 0))
            + " already as the package has them");
    long merges = items.stream().filter(Scan.PackageItem::needsMerge).count();
    long lost = items.stream().filter(Scan.PackageItem::overwritten).count();
    if (merges > 0) {
      lines.add(
          "  "
              + merges
              + " file(s) changed by both the site and the hotfix need a merge before this"
              + " package can be applied");
    }
    if (lost > 0) {
      lines.add(
          "  "
              + lost
              + " script, stylesheet or binary file(s) the site changed are replaced by the"
              + " package's: carry the site's change over by hand afterwards");
    }
    Scan.externalAuthWarning(contents, rt.settings().webappDir())
        .ifPresent(w -> lines.add("  ! " + w));
    return lines;
  }
}

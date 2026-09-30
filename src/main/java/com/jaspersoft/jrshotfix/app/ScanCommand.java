package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.hotfix.HotfixRuntime;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.scan.Scan;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jrs-hotfix scan [--package <package.zip>]}: whether this server is customized, and what a
 * package would meet. Invariants: read-only; the first line after the baseline is the answer; exit
 * 0 whether or not the server is customized, 2 when there is no baseline that fits.
 */
@Command(
    name = "scan",
    mixinStandardHelpOptions = true,
    description =
        "Compare the webapp with the vendor's files and list what this site changed; with"
            + " --package, say where a hotfix and the site changed the same file. Changes"
            + " nothing.",
    footer = {"", "Example:", "  jrs-hotfix scan --package <zip>"})
final class ScanCommand extends AppCommand {

  @Option(
      names = "--package",
      paramLabel = "<package.zip>",
      description = "A hotfix ZIP: also judge every file it ships against the site's changes.")
  Path packageFile;

  @Option(
      names = "--war",
      paramLabel = "<file.war>",
      description =
          "Scan this WAR instead of the server. The home is --home, else jrs-hotfix beside the"
              + " WAR; the baseline must be in it.")
  Path war;

  @Override
  public Integer call() {
    Bootstrap boot = war == null ? open() : open().forWar(war);
    HotfixPlans plans = boot.plans();
    HotfixRuntime rt = plans.runtime();
    BaseView.Resolution resolution = rt.baseView();
    if (resolution.view().isEmpty()) {
      return ExitCodes.fail(
          err(),
          ExitCodes.PRECHECK_FAILED,
          resolution.problem(),
          Optional.of(resolution.remediation()));
    }
    BaseView view = resolution.view().get();
    Path webapp = rt.settings().webappDir();
    Scan.Report report = Scan.of(view, webapp, rt.files());
    List<String> lines = new ArrayList<>();
    lines.add("baseline: " + report.baseline());
    lines.add(report.verdict());
    TextTable table = new TextTable();
    for (Scan.State state :
        List.of(Scan.State.CHANGED, Scan.State.ADDED, Scan.State.REMOVED, Scan.State.INSTALLER)) {
      for (Scan.Item item : report.of(state)) {
        table.row(state.name(), item.fileClass().name(), item.path());
      }
    }
    lines.addAll(table.lines());
    List<Scan.Item> externalAuth = report.of(Scan.State.EXTERNAL_AUTH);
    if (!externalAuth.isEmpty()) {
      lines.add("External authentication (the site's own files, made from the samples)");
      externalAuth.forEach(i -> lines.add("  " + i.path()));
    }
    lines.add(
        "GENERATED  "
            + report.generated()
            + " files written at run time or built, not the vendor's (logs, scripts,"
            + " stylesheets)");
    lines.add(
        "classes: X reviewed XML, P properties, T pages, G scripts and stylesheets, B binary;"
            + " INSTALLER files hold this server's values and are not customizations");
    if (packageFile != null) {
      PackageContents contents = plans.readPackage(packageFile);
      against(lines, rt, view, contents);
    }
    PrintWriter out = out();
    for (String line : lines) {
      out.println(boot.redactor().redact(line.stripTrailing()));
    }
    out.flush();
    return ExitCodes.SUCCESS;
  }

  /** The package's view: the files where the site and the vendor meet, then the counts. */
  private static void against(
      List<String> lines, HotfixRuntime rt, BaseView view, PackageContents contents) {
    List<Scan.PackageItem> items =
        Scan.against(
            view, contents, rt.settings().webappName(), rt.settings().webappDir(), rt.files());
    lines.add("");
    lines.add("Against " + contents.id() + " (" + items.size() + " files under the webapp)");
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
    lines.addAll(table.lines());
    lines.add(
        counts.getOrDefault(Scan.Verdict.VENDOR_ONLY, 0)
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
          merges
              + " file(s) changed by both the site and the hotfix need a merge before this"
              + " package can be applied");
    }
    if (lost > 0) {
      lines.add(
          lost
              + " script, stylesheet or binary file(s) the site changed are replaced by the"
              + " package's: carry the site's change over by hand afterwards");
    }
    Scan.externalAuthWarning(contents, rt.settings().webappDir())
        .ifPresent(w -> lines.add("! " + w));
  }
}

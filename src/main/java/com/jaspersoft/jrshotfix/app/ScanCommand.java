package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.hotfix.HotfixRuntime;
import com.jaspersoft.jrshotfix.scan.Scan;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jrs-hotfix scan [--war <file.war>]}: whether this server is customized, and how. What a
 * package would meet is {@code verify}'s since 0.6. Invariants: read-only; the first line after the
 * baseline is the answer; exit 0 whether or not the server is customized, 2 when there is no
 * baseline that fits.
 */
@Command(
    name = "scan",
    mixinStandardHelpOptions = true,
    description =
        "Compare the webapp with the vendor's files and list what this site changed. Changes"
            + " nothing; `verify <package.zip>` says where a hotfix meets those changes.",
    footer = {"", "Example:", "  jrs-hotfix scan"})
final class ScanCommand extends AppCommand {

  @Option(
      names = "--war",
      paramLabel = "<file.war | dir>",
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
    lines.addAll(table(report));
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
    BaseView installation = view.installation();
    Path installDir = rt.settings().installDir();
    boolean hasInstallation =
        Area.INSTALLATION_DIRS.stream().anyMatch(d -> Files.isDirectory(installDir.resolve(d)));
    if (installation.covered() && hasInstallation) {
      Scan.Report install = Scan.of(installation, installDir, rt.files());
      lines.add("");
      lines.add(
          "installation (" + String.join(", ", Area.INSTALLATION_DIRS) + "): " + install.verdict());
      lines.addAll(table(install));
      lines.add(
          "GENERATED  "
              + install.generated()
              + " files buildomatic builds from this site's settings, not the vendor's");
    } else if (hasInstallation) {
      lines.add(
          "installation: not compared; the baseline knows the webapp only. `jrs-hotfix baseline"
              + " add <the vendor's distribution>` adds buildomatic and samples");
    }
    lines.add(
        "classes: X reviewed XML, P properties, T pages and text, G scripts and stylesheets, B"
            + " binary; INSTALLER files hold this server's values and are not customizations");
    PrintWriter out = out();
    for (String line : lines) {
      out.println(boot.redactor().redact(line.stripTrailing()));
    }
    out.flush();
    return ExitCodes.SUCCESS;
  }

  /** The files of a report that differ from the vendor's, one row each. */
  private static List<String> table(Scan.Report report) {
    TextTable table = new TextTable();
    for (Scan.State state :
        List.of(Scan.State.CHANGED, Scan.State.ADDED, Scan.State.REMOVED, Scan.State.INSTALLER)) {
      for (Scan.Item item : report.of(state)) {
        table.row(state.name(), item.fileClass().name(), item.path());
      }
    }
    return table.lines();
  }
}

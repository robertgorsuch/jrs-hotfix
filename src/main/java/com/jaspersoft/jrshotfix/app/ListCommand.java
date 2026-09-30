package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import picocli.CommandLine.Command;

/**
 * {@code jrs-hotfix list}: what the webapp says it is, release, edition and build, then the ledger,
 * installed and rolled back alike. The build is the hotfix level of the files on disk, so a build
 * no entry has means a hotfix applied outside this tool. Invariant: read-only.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "List the hotfixes this tool installed or recorded.",
    footer = {"", "Example:", "  jrs-hotfix list"})
final class ListCommand extends AppCommand {

  @Override
  public Integer call() {
    Bootstrap boot = open();
    List<LedgerEntry> entries = boot.plans().list();
    PrintWriter out = out();
    boot.settings()
        .ifPresent(s -> out.println("on this server: " + InstalledBuild.describe(s.webappDir())));
    if (entries.isEmpty()) {
      out.println("no hotfixes recorded");
      out.flush();
      return ExitCodes.SUCCESS;
    }
    TextTable table = new TextTable(Terminal.width(Env.vars()));
    table.row("ID", "STATE", "ORIGIN", "RELEASE", "BUILD", "INSTALLED", "RUN");
    for (LedgerEntry e : entries) {
      table.row(
          e.id(),
          e.state().name(),
          e.origin().name().toLowerCase(Locale.ROOT),
          e.release() + " " + e.edition(),
          e.build(),
          e.installedAt().toString(),
          e.runId());
    }
    for (String line : table.lines()) {
      out.println(line);
    }
    out.flush();
    return ExitCodes.SUCCESS;
  }
}

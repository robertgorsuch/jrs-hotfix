package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.state.LedgerEntry;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import picocli.CommandLine.Command;

/** {@code jrs-hotfix list}: the ledger, installed and rolled back alike. Invariant: read-only. */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "List the hotfixes this tool installed or recorded.")
final class ListCommand extends AppCommand {

  @Override
  public Integer call() {
    Bootstrap boot = open();
    List<LedgerEntry> entries = boot.plans().list();
    PrintWriter out = out();
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

package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.state.LedgerEntry;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix record <package.zip>}: adds a hotfix that was applied by hand to the ledger.
 * Invariant: touches nothing on the server; the entry cannot be rolled back by this tool.
 */
@Command(
    name = "record",
    mixinStandardHelpOptions = true,
    description =
        "Record a hotfix applied by hand, so the ledger lists it; changes nothing on the server.")
final class RecordCommand extends AppCommand {

  @Parameters(index = "0", paramLabel = "<package.zip>", description = "The hotfix ZIP.")
  Path file;

  @Override
  public Integer call() {
    Bootstrap boot = open();
    return executor(boot)
        .mutate(
            "record",
            () -> {
              LedgerEntry entry = boot.plans().record(file);
              out()
                  .println(
                      "recorded "
                          + entry.id()
                          + " ("
                          + entry.files().size()
                          + " files); nothing was changed on the server");
              out().flush();
              return ExitCodes.SUCCESS;
            });
  }
}

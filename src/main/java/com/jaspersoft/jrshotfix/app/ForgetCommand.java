package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.state.LedgerEntry;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix forget <id>}: takes a hotfix out of the ledger because the webapp no longer
 * holds it, after a redeploy from a WAR or a removal by hand. Invariants: touches nothing on the
 * server; asks at a terminal unless {@code --yes}, and refuses under {@code --non-interactive}
 * without it; the snapshot and the baseline of the entry stay.
 */
@Command(
    name = "forget",
    mixinStandardHelpOptions = true,
    description =
        "Take a hotfix out of the ledger because the webapp no longer holds it (redeployed from"
            + " a WAR, or removed by hand). Changes nothing on the server.",
    footer = {"", "Example:", "  jrs-hotfix forget JRSHF-10.0.0-20260730-0457"})
final class ForgetCommand extends AppCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "The hotfix id, as `list` shows it.")
  String id;

  @Override
  public Integer call() {
    Bootstrap boot = open();
    return executor(boot).mutate("forget", () -> forget(boot));
  }

  private int forget(Bootstrap boot) {
    if (!global().yes()) {
      if (!boot.interactive()) {
        return ExitCodes.fail(
            err(),
            ExitCodes.PRECHECK_FAILED,
            "forget needs a confirmation",
            Optional.of("run it at a terminal, or with --yes"));
      }
      out()
          .println(
              "This takes "
                  + id
                  + " out of the ledger. Do it only when the webapp no longer holds the hotfix: a"
                  + " redeploy from a WAR, or a removal by hand. The snapshot stays until `runs"
                  + " prune`.");
      if (!Prompter.yes(out(), "Forget " + id + "? [y/N] ", false)) {
        return ExitCodes.fail(
            err(),
            ExitCodes.PRECHECK_FAILED,
            "not confirmed; nothing has changed",
            Optional.empty());
      }
    }
    LedgerEntry entry = boot.plans().forget(id);
    out()
        .println(
            "forgot "
                + entry.id()
                + " (was "
                + entry.state()
                + ", "
                + entry.files().size()
                + " files); nothing was changed on the server");
    out().flush();
    return ExitCodes.SUCCESS;
  }
}

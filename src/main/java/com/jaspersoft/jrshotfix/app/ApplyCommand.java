package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix apply <package.zip>}. Invariants: at a terminal without {@code --yes} the
 * operator confirms the package checksum against the support portal before anything is planned, and
 * a "no" is exit 2 with nothing touched; {@code --yes} skips only the questions, never a check;
 * whether the checksum was confirmed or skipped is written to the run log.
 */
@Command(
    name = "apply",
    mixinStandardHelpOptions = true,
    description = "Apply an official hotfix package: snapshot, stop, swap, start, record.")
final class ApplyCommand extends AppCommand {

  @Parameters(index = "0", paramLabel = "<package.zip>", description = "The hotfix ZIP.")
  Path file;

  @Option(names = "--plan", description = "Show the plan and stop; nothing is changed.")
  boolean plan;

  @Override
  public Integer call() {
    Bootstrap boot = open();
    boolean confirmed = false;
    if (!global.yes() && !plan && boot.interactive() && Files.isRegularFile(file)) {
      String sha;
      try {
        sha = boot.platform().files().sha256(file);
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot read " + file + ": " + e.getMessage(),
            "check the file, then run again",
            e);
      }
      out().println("package " + file.getFileName() + "  sha256 " + sha);
      confirmed = Confirm.ask(out(), "Does this checksum match the support portal? [y/N] ");
      if (!confirmed) {
        return ExitCodes.fail(
            err(),
            ExitCodes.PRECHECK_FAILED,
            "checksum not confirmed; nothing has changed",
            Optional.of("download the package again from the support portal"));
      }
    }
    HotfixPlans.ApplyArgs args = new HotfixPlans.ApplyArgs(file, confirmed);
    Plan p = boot.plans().planApply(args);
    List<String> audit =
        global.yes()
            ? List.of(HotfixPlans.AUDIT_CHECKSUM_CONFIRMED + " skipped with --yes")
            : List.of(HotfixPlans.AUDIT_CHECKSUM_CONFIRMED + " confirmed by the operator");
    return executor(boot)
        .execute(p, HotfixPlans.APPLY, HotfixPlans.applyArgsJson(args), plan, audit);
  }
}

package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix rollback <id>}: puts back the files a hotfix replaced or deleted from its
 * snapshot. Invariant: a hotfix whose files a later installed hotfix also owns is refused unless
 * {@code --cascade} rolls the later ones back first.
 */
@Command(
    name = "rollback",
    mixinStandardHelpOptions = true,
    description = "Roll an installed hotfix back from its snapshot.")
final class RollbackCommand extends AppCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "The hotfix id, as `list` shows it.")
  String id;

  @Option(
      names = "--cascade",
      description = "Also roll back later hotfixes that own some of the same files, newest first.")
  boolean cascade;

  @Option(names = "--plan", description = "Show the plan and stop; nothing is changed.")
  boolean plan;

  @Override
  public Integer call() {
    Bootstrap boot = open();
    HotfixPlans.RollbackArgs args = new HotfixPlans.RollbackArgs(id, cascade);
    Plan p = boot.plans().planRollback(args);
    return executor(boot)
        .execute(p, HotfixPlans.ROLLBACK, HotfixPlans.rollbackArgsJson(args), plan);
  }
}

package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jrs-hotfix rollback}: undoes the latest apply, putting back the files it replaced or
 * deleted from its snapshot (0.6 design, section 3). Invariant: there is one level of undo; once it
 * is used up, or replaced by the next apply, there is nothing to roll back.
 */
@Command(
    name = "rollback",
    mixinStandardHelpOptions = true,
    description = "Undo the latest hotfix applied with jrs-hotfix.",
    footer = {"", "Example:", "  jrs-hotfix rollback --plan"})
final class RollbackCommand extends AppCommand {

  @Option(names = "--plan", description = "Show the plan and stop; nothing is changed.")
  boolean plan;

  @Override
  public Integer call() {
    Bootstrap boot = open();
    HotfixPlans plans = boot.plans();
    Plan rollback = plans.planRollback();
    // the stored arguments name the undo the plan uses up, so recovery rebuilds exactly this plan
    String undoRunId = plans.undo().orElseThrow().runId();
    return executor(boot)
        .execute(
            rollback,
            HotfixPlans.ROLLBACK,
            HotfixPlans.rollbackArgsJson(new HotfixPlans.RollbackArgs(undoRunId)),
            plan);
  }
}

package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.RunRecord;
import com.jaspersoft.jrshotfix.engine.Transition;
import com.jaspersoft.jrshotfix.state.RunPlans;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-hotfix runs}: the run history, recovery of an interrupted run, and retention.
 * Invariants: {@code list} and {@code show} are read-only; {@code resume} and {@code rollback} act
 * only on a pending run and only after its rebuilt plan matches what was stored; {@code prune}
 * never removes a pending run or the snapshot of an installed hotfix.
 */
@Command(
    name = "runs",
    mixinStandardHelpOptions = true,
    description = "Show runs, recover an interrupted run, prune old runs and snapshots.",
    subcommands = {
      RunsCommand.ListRuns.class,
      RunsCommand.Show.class,
      RunsCommand.Resume.class,
      RunsCommand.Rollback.class,
      RunsCommand.Prune.class
    })
final class RunsCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Mixin GlobalOptions global;

  @Override
  public Integer call() {
    spec.commandLine().usage(spec.commandLine().getErr());
    return ExitCodes.USAGE;
  }

  /** {@code runs list}. */
  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      description = "List every run.",
      footer = {"", "Example:", "  jrs-hotfix runs list"})
  static final class ListRuns extends AppCommand {
    @Override
    public Integer call() {
      RunService runs = new RunService(open());
      List<RunRecord> all = runs.journal().runs();
      PrintWriter out = out();
      if (all.isEmpty()) {
        out.println("no runs yet");
        out.flush();
        return ExitCodes.SUCCESS;
      }
      TextTable table = new TextTable(Terminal.width(Env.vars()));
      table.row("ID", "OPERATION", "STARTED", "ENDED", "STATE", "EXIT");
      for (RunRecord r : all) {
        table.row(
            r.runId(),
            r.operation(),
            r.startedAt().toString(),
            r.endedAt().map(Object::toString).orElse("-"),
            r.terminalState().map(Enum::name).orElse("PENDING"),
            r.exitCode().map(String::valueOf).orElse("-"));
      }
      for (String line : table.lines()) {
        out.println(line);
      }
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code runs show <id>}. */
  @Command(
      name = "show",
      mixinStandardHelpOptions = true,
      description = "Show one run: its record, its step transitions and its stored plan.",
      footer = {"", "Example:", "  jrs-hotfix runs show <id>"})
  static final class Show extends AppCommand {
    @Parameters(index = "0", paramLabel = "<id>", description = "The run id.")
    String runId;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      RunService runs = new RunService(boot);
      Optional<RunRecord> run = runs.journal().run(runId);
      if (run.isEmpty()) {
        return ExitCodes.fail(
            err(),
            ExitCodes.PRECHECK_FAILED,
            "unknown run " + runId,
            Optional.of("run `jrs-hotfix runs list`"));
      }
      RunRecord r = run.get();
      PrintWriter out = out();
      TextTable head = new TextTable();
      head.row("run", r.runId());
      head.row("operation", r.operation());
      head.row("plan", r.planId().orElse("-"));
      head.row("started", r.startedAt().toString());
      head.row("ended", r.endedAt().map(Object::toString).orElse("-"));
      head.row("state", r.terminalState().map(Enum::name).orElse("PENDING"));
      head.row("exit", r.exitCode().map(String::valueOf).orElse("-"));
      head.row("log", boot.home().logFile(r.runId()).toString());
      for (String line : head.lines()) {
        out.println(line);
      }
      List<Transition> transitions = runs.journal().transitions(runId);
      out.println("transitions");
      TextTable t = new TextTable(Terminal.width(Env.vars()));
      for (Transition tr : transitions) {
        t.row(
            "  " + tr.seq(),
            tr.ts().toString(),
            tr.stepId(),
            tr.fromState().map(s -> s + " -> ").orElse("") + tr.toState(),
            tr.detail().orElse(""));
      }
      for (String line : t.lines()) {
        out.println(boot.redactor().redact(line));
      }
      Optional<RunPlans.Stored> stored = runs.plans().load(runId);
      out.println(
          "plan steps  "
              + stored.map(s -> String.join(", ", s.stepIds())).orElse("(no stored plan)"));
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code runs resume <id>}. */
  @Command(
      name = "resume",
      mixinStandardHelpOptions = true,
      description = "Continue an interrupted run from the step it stopped at.",
      footer = {"", "Example:", "  jrs-hotfix runs resume <id>"})
  static final class Resume extends AppCommand {
    @Parameters(index = "0", paramLabel = "<id>", description = "The pending run id.")
    String runId;

    @Override
    public Integer call() {
      return executor(open()).recover(runId, true);
    }
  }

  /** {@code runs rollback <id>}. */
  @Command(
      name = "rollback",
      mixinStandardHelpOptions = true,
      description = "Undo an interrupted run: compensate every step it completed, newest first.",
      footer = {"", "Example:", "  jrs-hotfix runs rollback <id>"})
  static final class Rollback extends AppCommand {
    @Parameters(index = "0", paramLabel = "<id>", description = "The pending run id.")
    String runId;

    @Override
    public Integer call() {
      return executor(open()).recover(runId, false);
    }
  }

  /** {@code runs prune [--older-than <days>]}. */
  @Command(
      name = "prune",
      mixinStandardHelpOptions = true,
      description =
          "Remove ended runs and snapshots older than the cut-off; the snapshot of an installed"
              + " hotfix and anything of a pending run are kept.",
      footer = {"", "Example:", "  jrs-hotfix runs prune --older-than 30"})
  static final class Prune extends AppCommand {
    @Option(
        names = "--older-than",
        paramLabel = "<days>",
        description = "Age cut-off in days (default: ${DEFAULT-VALUE}).")
    int days = 30;

    @Override
    public Integer call() {
      if (days < 0) {
        return ExitCodes.fail(
            err(), ExitCodes.USAGE, "--older-than must not be negative", Optional.empty());
      }
      Bootstrap boot = open();
      // refused while any run is pending: a pending rollback's chain needs other runs' snapshots
      return executor(boot).mutate("prune", () -> prune(boot));
    }

    private int prune(Bootstrap boot) {
      RunService.PruneResult r = new RunService(boot).prune(Duration.ofDays(days));
      PrintWriter out = out();
      out.println("runs removed        " + list(r.runsRemoved()));
      out.println("snapshots removed   " + list(r.snapshotsRemoved()));
      out.println("ledger entries removed  " + list(r.ledgerEntriesRemoved()));
      out.flush();
      return ExitCodes.SUCCESS;
    }

    private static String list(List<String> items) {
      return items.isEmpty() ? "none" : String.join(", ", items);
    }
  }
}

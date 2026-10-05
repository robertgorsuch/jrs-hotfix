package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.RunRecord;
import com.jaspersoft.jrshotfix.engine.Transition;
import com.jaspersoft.jrshotfix.state.RunPlans;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix runs}: the run history, recovery of an interrupted run, and retention.
 * Invariants: {@code list} and {@code show} are read-only; {@code resume} and {@code undo} act only
 * on a pending run and only after its rebuilt plan matches what was stored ({@code rollback} is the
 * hidden name {@code undo} had before 0.6); {@code abandon} closes a pending run without resuming
 * or undoing it, changing nothing else, and is shown as {@code ABANDONED}; {@code prune} never
 * removes a pending run or the undo of the latest apply, and keeps a failed run and its snapshot
 * unless {@code --include-failed} is given.
 */
@Command(
    name = "runs",
    mixinStandardHelpOptions = true,
    description = "Show runs, finish, undo or close an interrupted run, prune old runs.",
    subcommands = {
      RunsCommand.ListRuns.class,
      RunsCommand.Show.class,
      RunsCommand.Resume.class,
      RunsCommand.Undo.class,
      RunsCommand.Abandon.class,
      RunsCommand.RollbackAlias.class,
      RunsCommand.Prune.class
    })
final class RunsCommand extends GroupCommand {

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
      TextTable table = table();
      table.row("ID", "OPERATION", "STARTED", "ENDED", "STATE", "EXIT");
      for (RunRecord r : all) {
        table.row(
            r.runId(),
            r.operation(),
            r.startedAt().toString(),
            r.endedAt().map(Object::toString).orElse("-"),
            state(runs, r),
            r.exitCode().map(String::valueOf).orElse("-"));
      }
      table.printTo(out);
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
      out.println("home: " + boot.home().root());
      TextTable head = new TextTable();
      head.row("run", r.runId());
      head.row("operation", r.operation());
      head.row("plan", r.planId().orElse("-"));
      head.row("started", r.startedAt().toString());
      head.row("ended", r.endedAt().map(Object::toString).orElse("-"));
      head.row("state", state(runs, r));
      head.row("exit", r.exitCode().map(String::valueOf).orElse("-"));
      head.row("log", boot.home().logFile(r.runId()).toString());
      head.printTo(out);
      List<Transition> transitions = runs.journal().transitions(runId);
      out.println("transitions");
      TextTable t = table();
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

  /** {@code runs undo <id>}. */
  @Command(
      name = "undo",
      mixinStandardHelpOptions = true,
      description = "Undo an interrupted run: compensate every step it completed, newest first.",
      footer = {"", "Example:", "  jrs-hotfix runs undo <id>"})
  static final class Undo extends AppCommand {
    @Parameters(index = "0", paramLabel = "<id>", description = "The pending run id.")
    String runId;

    @Override
    public Integer call() {
      return executor(open()).recover(runId, false);
    }
  }

  /** {@code runs abandon <id>}. */
  @Command(
      name = "abandon",
      mixinStandardHelpOptions = true,
      description =
          "Close an interrupted run without finishing or undoing it, for a server put right"
              + " another way; nothing else is changed, and the run's snapshot is kept.",
      footer = {"", "Example:", "  jrs-hotfix runs abandon <id>"})
  static final class Abandon extends AppCommand {
    @Parameters(index = "0", paramLabel = "<id>", description = "The pending run id.")
    String runId;

    @Override
    public Integer call() {
      return executor(open()).abandon(runId);
    }
  }

  /** A run's state as the operator reads it: {@code ABANDONED} for a run closed as it was. */
  static String state(RunService runs, RunRecord r) {
    if (runs.journal().abandoned(r.runId())) {
      return "ABANDONED";
    }
    return r.terminalState().map(Enum::name).orElse("PENDING");
  }

  /**
   * {@code runs rollback <id>}: the name of {@link Undo} before 0.6, kept hidden so that a script
   * written for 0.5 still works; {@code jrs-hotfix rollback} undoes a hotfix, this undoes a run.
   */
  @Command(
      name = "rollback",
      hidden = true,
      mixinStandardHelpOptions = true,
      description = "The name of `runs undo` before 0.6.",
      footer = {"", "Example:", "  jrs-hotfix runs rollback <id>"})
  static final class RollbackAlias extends AppCommand {
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
          "Remove ended runs and unused merges older than the cut-off, and hotfix baselines"
              + " older than the newest two; the undo of the latest apply, anything of a pending"
              + " run and, without --include-failed, anything of a failed run are kept.",
      footer = {"", "Example:", "  jrs-hotfix runs prune --older-than 30"})
  static final class Prune extends AppCommand {
    @Option(
        names = "--older-than",
        paramLabel = "<days>",
        description = "Age cut-off in days (default: ${DEFAULT-VALUE}).")
    int days = 30;

    @Option(
        names = "--include-failed",
        description =
            "Also remove failed runs (exit 4) and their snapshots; by default they are kept, since"
                + " the files may still have to be restored from them.")
    boolean includeFailed;

    @Override
    public Integer call() {
      if (days < 0) {
        return ExitCodes.fail(
            err(), ExitCodes.USAGE, "--older-than must not be negative", Optional.empty());
      }
      Bootstrap boot = open();
      // refused while any run is pending: its recovery needs its own run directory
      return executor(boot).mutate("prune", () -> prune(boot));
    }

    private int prune(Bootstrap boot) {
      RunService.PruneResult r = new RunService(boot).prune(Duration.ofDays(days), includeFailed);
      PrintWriter out = out();
      out.println("runs removed        " + list(r.runsRemoved()));
      out.println("baselines removed   " + list(r.baselinesRemoved()));
      out.println("merges removed      " + list(r.mergesRemoved()));
      out.flush();
      return ExitCodes.SUCCESS;
    }

    private static String list(List<String> items) {
      return items.isEmpty() ? "none" : String.join(", ", items);
    }
  }
}

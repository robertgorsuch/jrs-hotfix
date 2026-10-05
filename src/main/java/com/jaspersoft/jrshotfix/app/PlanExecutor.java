package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.JournalException;
import com.jaspersoft.jrshotfix.engine.LockHeldException;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.PlanFingerprint;
import com.jaspersoft.jrshotfix.engine.RunLock;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.RunRecord;
import com.jaspersoft.jrshotfix.engine.Runner;
import com.jaspersoft.jrshotfix.engine.TerminalState;
import com.jaspersoft.jrshotfix.event.EventBus;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.redact.Redactor;
import com.jaspersoft.jrshotfix.state.RunPlans;
import com.jaspersoft.jrshotfix.state.UndoStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The one path every mutating command takes from a {@link Plan} to a process exit code. Invariants:
 * a held run lock is exit 9 and is checked first, so a run another process is executing is never
 * mistaken for one that needs recovery; a pending run blocks every new run with exit 8 and names
 * the exact {@code runs resume} and {@code runs undo} commands; {@code --plan} prints the plan and
 * touches nothing; nothing runs without {@code --yes} or an explicit yes at the terminal, and a
 * non-interactive caller without {@code --yes} exits 2; the plan is rebuilt from its arguments
 * after the answer and a changed fingerprint is exit 2 naming the changed inputs; Ctrl-C cancels
 * through the run's single {@link CancellationToken} and waits up to 30 s for the step in flight;
 * the exit code is {@link RunOutcome#exitCode()}, except that a rollback the operator asked for
 * through recovery that completes is a success.
 */
final class PlanExecutor {

  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

  private final Bootstrap boot;
  private final RunService runs;
  private final PrintWriter out;
  private final PrintWriter err;
  private final Ansi ansi;
  private final boolean yes;
  private final Redactor redactor;

  PlanExecutor(
      Bootstrap boot, RunService runs, PrintWriter out, PrintWriter err, Ansi ansi, boolean yes) {
    this.boot = Objects.requireNonNull(boot, "boot");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
    this.ansi = Objects.requireNonNull(ansi, "ansi");
    this.yes = yes;
    this.redactor = boot.redactor();
  }

  /** Shows, confirms and runs a fresh plan; returns the process exit code. */
  int execute(Plan plan, String operation, String argsJson, boolean showOnly) {
    return execute(plan, operation, argsJson, showOnly, List.of());
  }

  /** As {@link #execute(Plan, String, String, boolean)}, writing {@code audit} to the run log. */
  int execute(Plan plan, String operation, String argsJson, boolean showOnly, List<String> audit) {
    Optional<Integer> blocked = blocked(true);
    if (blocked.isPresent()) {
      return blocked.get();
    }
    PlanPrinter.print(out, boot.home(), plan, ansi, redactor);
    if (showOnly) {
      return ExitCodes.SUCCESS;
    }
    Optional<Integer> refused = confirm("Run this plan? [y/N] ");
    if (refused.isPresent()) {
      return refused.get();
    }
    // rebuilt after the answer: inputs that changed while the prompt waited are refused
    PlanFingerprint recomputed;
    try {
      recomputed = boot.plans().rebuild(operation, argsJson).fingerprint();
    } catch (HotfixException e) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "the plan can no longer be built: " + e.getMessage(),
          Optional.of(e.remediation()));
    }
    if (!plan.fingerprint().matches(recomputed)) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "inputs changed since planning: " + plan.fingerprint().changedKeys(recomputed),
          Optional.of("run the command again to plan against the current state"));
    }
    String runId = runs.newRunId();
    Context ctx = runs.context(runId);
    return run(plan, ctx, runner -> runs.run(runner, plan, ctx, operation, argsJson, audit), false);
  }

  /**
   * Runs a mutation outside the engine (prune, settings, merges) under the same gates as a run:
   * exit 9 when the run lock is held, exit 8 while a run is pending; the run lock is held for the
   * whole of {@code body}, so no run can start in between.
   */
  int mutate(String what, IntSupplier body) {
    Optional<Integer> blocked = blocked(true);
    if (blocked.isPresent()) {
      return blocked.get();
    }
    try (RunLock unused = new RunLock(boot.home(), what, boot.clock().instant())) {
      return body.getAsInt();
    } catch (LockHeldException held) {
      return lockHeld(held.holderRunId(), held.holderPid());
    }
  }

  /**
   * Resumes or rolls back a pending run: loads its stored plan, rebuilds it from the stored
   * arguments, compares the fingerprint inputs that stay stable while a run is half done, then
   * hands the run to recovery.
   */
  int recover(String runId, boolean resume) {
    Optional<Integer> locked = blocked(false);
    if (locked.isPresent()) {
      return locked.get();
    }
    Optional<RunRecord> run = runs.journal().run(runId);
    if (run.isEmpty()) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "unknown run " + runId,
          Optional.of("run `jrs-hotfix runs list`"));
    }
    if (!run.get().pending()) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "run "
              + runId
              + " already ended with state "
              + run.get().terminalState().map(Enum::name).orElse("?"),
          Optional.of("nothing to recover"));
    }
    Optional<RunPlans.Stored> stored = runs.plans().load(runId);
    if (stored.isEmpty()) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "run " + runId + " has no stored plan",
          Optional.of(
              "restore the files by hand from the snapshot under " + boot.home().runDir(runId)));
    }
    Plan plan;
    try {
      plan = boot.plans().rebuild(stored.get().operation(), stored.get().argsJson());
    } catch (HotfixException e) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "the plan of run " + runId + " cannot be rebuilt: " + e.getMessage(),
          Optional.of(e.remediation()));
    } catch (IllegalArgumentException e) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "the plan of run " + runId + " cannot be rebuilt: " + e.getMessage(),
          Optional.empty());
    }
    List<String> changed =
        changedStableKeys(
            stored.get().operation(), stored.get().inputs(), plan.fingerprint().inputs());
    if (!changed.isEmpty()) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "the installation changed since run " + runId + " started: " + changed,
          Optional.of(
              "put back what changed, or restore the files by hand from the snapshot under "
                  + boot.home().runDir(runId)));
    }
    out.println(
        (resume ? "resume" : "roll back")
            + " run "
            + runId
            + " ("
            + stored.get().operation()
            + ", "
            + plan.steps().size()
            + " steps)");
    Optional<Integer> refused =
        confirm(resume ? "Resume this run? [y/N] " : "Roll this run back? [y/N] ");
    if (refused.isPresent()) {
      return refused.get();
    }
    Context ctx = runs.context(runId);
    return run(
        plan,
        ctx,
        runner ->
            resume
                ? runs.resume(runner, plan, runId, ctx)
                : runs.rollback(runner, plan, runId, ctx),
        !resume);
  }

  /**
   * Closes a pending run without resuming or undoing it (0.9), for a server the operator has put
   * right another way. Shows what the run completed and what it did not, and says loudly when it
   * left the service stopped; then asks, as recovery does. Nothing on the server or in the home is
   * changed: the run is recorded as ended, its snapshot stays for restoring by hand, and the undo
   * of the previous apply, which only an apply's last step replaces, stays as it was.
   */
  int abandon(String runId) {
    Optional<Integer> locked = blocked(false);
    if (locked.isPresent()) {
      return locked.get();
    }
    Optional<RunRecord> run = runs.journal().run(runId);
    if (run.isEmpty()) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "unknown run " + runId,
          Optional.of("run `jrs-hotfix runs list`"));
    }
    if (!run.get().pending()) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "run "
              + runId
              + " already ended with state "
              + run.get().terminalState().map(Enum::name).orElse("?"),
          Optional.of("there is nothing to close"));
    }
    RunService.Leftovers left = runs.leftovers(runId);
    out.println("run " + runId + " (" + left.operation() + ") stopped part way through");
    out.println(
        "  completed:     "
            + (left.completed().isEmpty() ? "nothing" : String.join(", ", left.completed())));
    out.println(
        "  not completed: "
            + (left.notCompleted().isEmpty() ? "nothing" : String.join(", ", left.notCompleted())));
    if (left.serviceDown()) {
      out.println("! the run stopped the service and did not start it again: the server is down");
    }
    out.println(
        "Closing it records the run as ended and changes nothing: the server's files stay as they"
            + " are now, and the run's snapshot stays in "
            + boot.home().runDir(runId)
            + " for restoring files by hand.");
    Optional<Integer> refused = confirm("Close this run and keep the server as it is? [y/N] ");
    if (refused.isPresent()) {
      return refused.get();
    }
    try (RunLock unused = new RunLock(boot.home(), "abandon", boot.clock().instant())) {
      runs.abandon(runId);
    } catch (LockHeldException held) {
      return lockHeld(held.holderRunId(), held.holderPid());
    } catch (JournalException | UncheckedIOException e) {
      return ExitCodes.fail(
          err,
          ExitCodes.PRECHECK_FAILED,
          "cannot close run " + runId + ": " + e.getMessage(),
          Optional.of("check the rights and free space under " + boot.home().root()));
    }
    out.println("run " + runId + " is closed; nothing else was changed");
    if (left.serviceDown()) {
      out.println("! start the service yourself: jrs-hotfix did not start it");
    }
    out.println(
        "  its snapshot stays until `jrs-hotfix runs prune --include-failed`; `jrs-hotfix verify"
            + " <package.zip>` shows what the server holds now");
    out.flush();
    return ExitCodes.SUCCESS;
  }

  /**
   * The inputs recovery compares: a half-done run has changed the target files by design, so only
   * what identifies the request and the installation counts. Apply: the package, the settings, the
   * installed release, and the merge it was planned with, by id and by the hash of its document, so
   * a merge edited after the run began is refused. Rollback: the settings and each hotfix's
   * installing run.
   */
  static List<String> changedStableKeys(
      String operation, Map<String, String> stored, Map<String, String> rebuilt) {
    TreeSet<String> keys = new TreeSet<>();
    keys.addAll(stored.keySet());
    keys.addAll(rebuilt.keySet());
    List<String> changed = new ArrayList<>();
    for (String key : keys) {
      boolean stable =
          (operation.equals(HotfixPlans.APPLY) || operation.equals(HotfixPlans.APPLY_WAR))
              ? key.equals("package")
                  || key.equals("settings")
                  || key.equals("installed")
                  || key.equals(HotfixPlans.MERGE_INPUT)
                  || key.equals(HotfixPlans.MERGE_DOC_INPUT)
              : key.equals("settings") || key.startsWith("hotfix:");
      if (stable && !Objects.equals(stored.get(key), rebuilt.get(key))) {
        changed.add(key);
      }
    }
    return changed;
  }

  /**
   * Converts a home written by 0.1 to 0.5 once, under the run lock (0.6 design, section 7): the
   * newest hotfix the ledger lists as installed becomes the undo, the snapshots of failed runs move
   * into their runs, and {@code ledger.json} is set aside. Empty when done or not needed.
   */
  private Optional<Integer> convertLegacy() {
    if (!Files.isRegularFile(boot.home().ledgerFile())) {
      return Optional.empty();
    }
    Set<String> failed = new HashSet<>();
    for (RunRecord run : runs.journal().runs()) {
      if (run.terminalState().equals(Optional.of(TerminalState.FAILED))) {
        failed.add(run.runId());
      }
    }
    try (RunLock unused = new RunLock(boot.home(), "convert", boot.clock().instant())) {
      if (new UndoStore(boot.home()).convertLegacy(failed)) {
        out.println(
            "converted this home from jrs-hotfix 0.5: "
                + boot.home().ledgerFile().getFileName()
                + " is kept as "
                + boot.home().ledgerFile().getFileName()
                + ".0.5 and no longer read");
        out.flush();
      }
      return Optional.empty();
    } catch (LockHeldException held) {
      return Optional.of(lockHeld(held.holderRunId(), held.holderPid()));
    } catch (IOException | UncheckedIOException e) {
      return Optional.of(
          ExitCodes.fail(
              err,
              ExitCodes.PRECHECK_FAILED,
              "cannot convert this home from jrs-hotfix 0.5: " + e.getMessage(),
              Optional.of("check the rights and free space under " + boot.home().root())));
    }
  }

  /** Reports the run lock held by another process: exit 9. */
  private int lockHeld(String runId, String pid) {
    return ExitCodes.fail(
        err,
        ExitCodes.LOCK_HELD,
        "the run lock is held by run " + runId + " (pid " + pid + ")",
        Optional.of("wait for that jrs-hotfix process to finish, then run this again"));
  }

  /**
   * Exit 9 when the run lock is held; with {@code pendingToo}, exit 8 for a pending run, and
   * otherwise a home written by 0.1 to 0.5 is converted first.
   */
  private Optional<Integer> blocked(boolean pendingToo) {
    Optional<RunLock.Holder> holder = runs.lockHolder();
    if (holder.isPresent()) {
      return Optional.of(lockHeld(holder.get().runId(), holder.get().pid()));
    }
    if (!pendingToo) {
      return Optional.empty();
    }
    List<RunRecord> pending = runs.pendingRuns();
    if (pending.isEmpty()) {
      return convertLegacy();
    }
    err.println(
        redactor.redact(
            "error: "
                + pending.size()
                + (pending.size() == 1 ? " run needs" : " runs need")
                + " recovery before anything else can run:"));
    for (RunRecord run : pending) {
      err.println(
          redactor.redact(
              "  " + run.runId() + "  " + run.operation() + "  started " + run.startedAt()));
    }
    String first = pending.get(0).runId();
    err.println(
        "  run `jrs-hotfix runs resume "
            + first
            + "` to continue it, `jrs-hotfix runs undo "
            + first
            + "` to undo it, or `jrs-hotfix runs abandon "
            + first
            + "` to close it and keep the server as it is");
    err.flush();
    return Optional.of(ExitCodes.RECOVERY_REQUIRED);
  }

  /** Empty when the operator said yes (or passed {@code --yes}); else the exit code. */
  private Optional<Integer> confirm(String question) {
    if (yes) {
      return Optional.empty();
    }
    if (!boot.interactive()) {
      return Optional.of(
          ExitCodes.fail(
              err,
              ExitCodes.PRECHECK_FAILED,
              "confirmation required",
              Optional.of("pass --yes to run without asking, or --plan to only show the plan")));
    }
    out.println();
    if (!Prompter.yes(out, question, false)) {
      out.println("not run; nothing has changed");
      out.flush();
      return Optional.of(ExitCodes.SUCCESS);
    }
    return Optional.empty();
  }

  private int run(
      Plan plan, Context ctx, Function<Runner, RunOutcome> body, boolean rollbackRequested) {
    EventBus bus = new EventBus();
    ProgressRenderer renderer = new ProgressRenderer(plan, out, ansi, redactor);
    bus.subscribe(renderer);
    Runner runner = runs.runner(bus);
    out.println();
    out.println("run " + ctx.runId());
    out.flush();
    AtomicReference<RunOutcome> result = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                result.set(body.apply(runner));
              } catch (RuntimeException e) {
                failure.set(e);
              }
            },
            "jrs-hotfix-run");
    // Ctrl-C: the hook cancels, waits for the worker and for the outcome block, then halts with
    // the run's exit code; the JVM's own 130/143 never reaches the operator
    RunGuard guard =
        new RunGuard(
            ctx.cancel(),
            worker,
            SHUTDOWN_GRACE,
            RunGuard.RENDER_GRACE,
            Runtime.getRuntime()::halt);
    Thread hook = new Thread(guard::onShutdown, "jrs-hotfix-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    worker.start();
    try {
      worker.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ctx.cancel().cancel("interrupted");
      try {
        worker.join(SHUTDOWN_GRACE.toMillis());
      } catch (InterruptedException again) {
        Thread.currentThread().interrupt();
      }
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException shuttingDown) {
        // the JVM is already going down; the hook is doing the cancelling
      }
    }
    RuntimeException thrown = failure.get();
    if (thrown != null) {
      if (thrown instanceof LockHeldException held) {
        int code = lockHeld(held.holderRunId(), held.holderPid());
        guard.rendered(code);
        return code;
      }
      throw thrown;
    }
    RunOutcome outcome = result.get();
    if (outcome == null) {
      int code =
          ExitCodes.fail(
              err,
              ExitCodes.CANCELLED,
              "run " + ctx.runId() + " was interrupted before it reported an outcome",
              Optional.empty());
      guard.rendered(code);
      return code;
    }
    renderer.outcome(ctx.runId(), outcome);
    List<String> notes = HotfixPlans.notesOf(plan);
    // a rolled-back or failed run never installed the hotfix, so its manual steps do not apply
    if (!notes.isEmpty() && outcome instanceof RunOutcome.Succeeded) {
      out.println();
      out.println(
          redactor.redact(
              "Manual steps from the package readme (also saved to "
                  + boot.home().notesFile(ctx.runId())
                  + "):"));
      for (String note : notes) {
        out.println(redactor.redact("  " + note));
      }
    }
    out.flush();
    // a rollback the operator asked for that completes is what they wanted: success
    int code =
        rollbackRequested && outcome instanceof RunOutcome.RolledBack
            ? ExitCodes.SUCCESS
            : outcome.exitCode();
    guard.rendered(code);
    return code;
  }
}

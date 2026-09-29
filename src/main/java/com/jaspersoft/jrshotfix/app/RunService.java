package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.Recovery;
import com.jaspersoft.jrshotfix.engine.RunIds;
import com.jaspersoft.jrshotfix.engine.RunLock;
import com.jaspersoft.jrshotfix.engine.RunOptions;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.RunRecord;
import com.jaspersoft.jrshotfix.engine.Runner;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.redact.RedactingEventSink;
import com.jaspersoft.jrshotfix.snapshot.Snapshot;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.FileJournal;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.Ledger;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.RunPlans;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The run glue every mutating command shares: the journal, the stored plans, the run context, the
 * runner and retention. Invariants: a run's plan is stored before its first step; the run lock is
 * taken by the {@link Runner} for exactly the duration of a run, resume or rollback; while one of
 * those executes, every event the runner emits (already redacted) is also written to the run's own
 * log, {@code runs/<id>/run.log}, together with the platform's diagnostics; {@link #prune} never
 * removes a pending run, the snapshot of a pending run, or the snapshot of an installed hotfix.
 */
final class RunService {

  /** What {@link #prune} removed: run ids, {@code runId/stepId} snapshots and ledger ids. */
  record PruneResult(
      List<String> runsRemoved, List<String> snapshotsRemoved, List<String> ledgerEntriesRemoved) {
    PruneResult {
      runsRemoved = List.copyOf(runsRemoved);
      snapshotsRemoved = List.copyOf(snapshotsRemoved);
      ledgerEntriesRemoved = List.copyOf(ledgerEntriesRemoved);
    }
  }

  private final Bootstrap boot;
  private final FileJournal journal;
  private final RunPlans plans;
  private final AtomicReference<LogFile> log = new AtomicReference<>();

  RunService(Bootstrap boot) {
    this.boot = Objects.requireNonNull(boot, "boot");
    this.journal = new FileJournal(boot.home(), boot.clock());
    this.plans = new RunPlans(boot.home());
  }

  /** The process holding the run lock right now, if any. */
  Optional<RunLock.Holder> lockHolder() {
    return RunLock.heldBy(boot.home().runLock());
  }

  List<RunRecord> pendingRuns() {
    return journal.pendingRuns();
  }

  FileJournal journal() {
    return journal;
  }

  RunPlans plans() {
    return plans;
  }

  String newRunId() {
    return RunIds.next(boot.clock());
  }

  Context context(String runId) {
    return new Context(runId, boot.home(), boot.platform(), new CancellationToken(), Map.of());
  }

  /** A runner whose events go to {@code sink} and, while a run executes, to its log. */
  Runner runner(EventSink sink) {
    EventSink tee =
        event -> {
          sink.emit(event);
          LogFile current = log.get();
          if (current != null) {
            current.sink().emit(event);
          }
        };
    return new Runner(
        journal,
        new RedactingEventSink(tee, boot.redactor()),
        boot.clock(),
        Sleeper.system(),
        boot.redactor());
  }

  /** Stores the plan, then runs it. The runner takes the run lock. */
  RunOutcome run(Runner runner, Plan plan, Context ctx, String operation, String argsJson) {
    return run(runner, plan, ctx, operation, argsJson, List.of());
  }

  /** As {@link #run(Runner, Plan, Context, String, String)}, writing {@code audit} to the log. */
  RunOutcome run(
      Runner runner,
      Plan plan,
      Context ctx,
      String operation,
      String argsJson,
      List<String> audit) {
    plans.store(ctx.runId(), plan, operation, argsJson);
    writeNotes(ctx.runId(), plan);
    return logged(
        ctx.runId(),
        operation + " " + argsJson,
        audit,
        () -> runner.run(plan, ctx, plan.fingerprint(), RunOptions.DEFAULT));
  }

  RunOutcome resume(Runner runner, Plan plan, String runId, Context ctx) {
    return logged(
        runId,
        "resume requested by the operator",
        List.of(),
        () -> new Recovery(journal, runner).resume(plan, runId, ctx, RunOptions.DEFAULT));
  }

  RunOutcome rollback(Runner runner, Plan plan, String runId, Context ctx) {
    return logged(
        runId,
        "rollback requested by the operator",
        List.of(),
        () -> new Recovery(journal, runner).rollback(plan, runId, ctx));
  }

  /**
   * Saves the package readme's manual steps beside the plan, {@code runs/<runId>/notes.txt}, one
   * per line, UTF-8; writes nothing when {@code plan} has none. Never executed by this tool.
   */
  private void writeNotes(String runId, Plan plan) {
    List<String> notes = HotfixPlans.notesOf(plan);
    if (notes.isEmpty()) {
      return;
    }
    Path file = boot.home().notesFile(runId);
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      String text = String.join(System.lineSeparator(), notes) + System.lineSeparator();
      Files.writeString(tmp, text, StandardCharsets.UTF_8);
      Durability.sync(tmp);
      Durability.move(
          tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Durability.syncDirectory(file.toAbsolutePath().getParent());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  private RunOutcome logged(
      String runId, String what, List<String> audit, Supplier<RunOutcome> body) {
    try (LogFile file = LogFile.open(boot.home(), runId)) {
      file.line("run " + runId + ": " + boot.redactor().redact(what));
      for (String line : audit) {
        file.line("audit " + boot.redactor().redact(line));
      }
      log.set(file);
      try {
        RunOutcome outcome = body.get();
        file.line("outcome " + outcome.getClass().getSimpleName() + " exit " + outcome.exitCode());
        return outcome;
      } finally {
        log.set(null);
      }
    }
  }

  /**
   * Removes what is older than {@code olderThan}: the directories of ended runs; snapshots, except
   * those of pending runs and of hotfixes the ledger has installed; and rolled-back ledger entries
   * whose snapshot is gone.
   */
  PruneResult prune(Duration olderThan) {
    Instant cutoff = boot.clock().instant().minus(olderThan);
    List<String> runsRemoved = new ArrayList<>();
    Set<String> pending = new HashSet<>();
    try {
      for (RunRecord run : journal.runs()) {
        if (run.pending()) {
          pending.add(run.runId());
          continue;
        }
        if (run.endedAt().filter(e -> e.isBefore(cutoff)).isPresent()) {
          Trees.deleteRecursively(boot.home().runDir(run.runId()));
          runsRemoved.add(run.runId());
        }
      }
      Ledger ledger = new Ledger(boot.home());
      Set<String> protectedRunIds = new HashSet<>(pending);
      for (LedgerEntry e : ledger.all()) {
        if (e.state() == HotfixState.INSTALLED) {
          protectedRunIds.add(e.runId());
        }
      }
      SnapshotStore snapshots =
          new SnapshotStore(boot.home(), boot.platform().files(), boot.clock());
      List<String> snapshotsRemoved = new ArrayList<>();
      // SnapshotStore reads a zero retention as "no age limit"; here zero days means "all of it"
      Duration retention = olderThan.isZero() ? Duration.ofMillis(1) : olderThan;
      for (Snapshot s : snapshots.prune(retention, Integer.MAX_VALUE, protectedRunIds)) {
        snapshotsRemoved.add(s.runId() + "/" + s.stepId());
      }
      List<String> ledgerRemoved = new ArrayList<>();
      for (LedgerEntry e : ledger.all()) {
        if (e.state() == HotfixState.ROLLED_BACK
            && !Files.exists(boot.home().snapshots().resolve(e.runId()))
            && ledger.delete(e.id())) {
          ledgerRemoved.add(e.id());
        }
      }
      return new PruneResult(runsRemoved, snapshotsRemoved, ledgerRemoved);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot prune " + boot.home().root(), e);
    }
  }
}

package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.baseline.BaselineManifest;
import com.jaspersoft.jrshotfix.baseline.BaselineStore;
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
import com.jaspersoft.jrshotfix.engine.TerminalState;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * removes a pending run, the snapshot of a pending run, or the snapshot of an installed hotfix, and
 * keeps a failed run (exit 4) and its snapshot unless asked to include them.
 */
final class RunService {

  /**
   * What {@link #prune} removed: run ids, {@code runId/stepId} snapshots, ledger ids, hotfix
   * baseline ids and merge ids.
   */
  record PruneResult(
      List<String> runsRemoved,
      List<String> snapshotsRemoved,
      List<String> ledgerEntriesRemoved,
      List<String> baselinesRemoved,
      List<String> mergesRemoved) {
    PruneResult {
      runsRemoved = List.copyOf(runsRemoved);
      snapshotsRemoved = List.copyOf(snapshotsRemoved);
      ledgerEntriesRemoved = List.copyOf(ledgerEntriesRemoved);
      baselinesRemoved = List.copyOf(baselinesRemoved);
      mergesRemoved = List.copyOf(mergesRemoved);
    }
  }

  /** Test-only environment variable: the step id a run pauses before; see {@link #pauseIfAt}. */
  static final String PAUSE_AT = "JRS_HOTFIX_TEST_PAUSE_AT";

  private static final Duration PAUSE = Duration.ofMinutes(2);

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
    // test-only: the acceptance crash tests hold a run before a chosen step; inert when unset
    Optional<String> pauseAt =
        Optional.ofNullable(Env.vars().get(PAUSE_AT)).map(String::strip).filter(s -> !s.isEmpty());
    EventSink tee =
        event -> {
          sink.emit(event);
          LogFile current = log.get();
          if (current != null) {
            current.sink().emit(event);
          }
          pauseAt.ifPresent(step -> pauseIfAt(step, event));
        };
    return new Runner(
        journal,
        new RedactingEventSink(tee, boot.redactor()),
        boot.clock(),
        Sleeper.system(),
        boot.redactor());
  }

  /**
   * Test-only (acceptance crash tests): after {@code event} has been delivered, when it is the
   * {@link Event.StepRunning} of {@code stepId}, writes {@code runs/<runId>/paused} and sleeps so
   * the test can kill the process before the step executes. An interrupt ends the sleep early.
   */
  private void pauseIfAt(String stepId, Event event) {
    if (!(event instanceof Event.StepRunning running)
        || !running.stepId().equals(Optional.of(stepId))) {
      return;
    }
    Path marker = boot.home().runDir(running.runId()).resolve("paused");
    try {
      Files.createDirectories(marker.getParent());
      Files.writeString(marker, stepId, StandardCharsets.UTF_8);
      Thread.sleep(PAUSE.toMillis());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + marker, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Stores the plan, then runs it, writing {@code audit} to the log. The runner takes the lock. */
  RunOutcome run(
      Runner runner,
      Plan plan,
      Context ctx,
      String operation,
      String argsJson,
      List<String> audit) {
    boot.ensureHome();
    plans.store(ctx.runId(), plan, operation, argsJson);
    writeNotes(ctx.runId(), plan);
    return logged(
        ctx.runId(),
        operation + " " + argsJson,
        audit,
        () -> runner.run(plan, ctx, RunOptions.DEFAULT));
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
      Durability.writeAtomically(
          file, String.join(System.lineSeparator(), notes) + System.lineSeparator());
    } catch (IOException e) {
      // before any step has run: a precheck-class refusal (exit 2), not an unexplained exit 4
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot write " + file + ": " + e.getMessage(),
          "check that the run directory is writable; nothing was changed",
          e);
    }
  }

  private RunOutcome logged(
      String runId, String what, List<String> audit, Supplier<RunOutcome> body) {
    boot.ensureHome();
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
   * Removes the hotfix baselines older than the newest two, whatever their age: a base is needed
   * for the level the webapp is at, and for the one a rollback returns to. The baseline of the
   * build the webapp states is kept in any case; release baselines are never pruned.
   */
  private List<String> pruneBaselines() throws IOException {
    BaselineStore store = new BaselineStore(boot.home(), boot.clock());
    Optional<String> stated =
        boot.settings()
            .flatMap(s -> InstalledBuild.ofWebapp(s.webappDir()))
            .map(InstalledBuild::build);
    List<BaselineManifest> hotfixes =
        store.list().stream()
            .filter(b -> b.kind() == BaselineManifest.Kind.HOTFIX)
            .sorted(Comparator.comparing(BaselineManifest::build).reversed())
            .toList();
    List<String> removed = new ArrayList<>();
    for (BaselineManifest b : hotfixes.subList(Math.min(2, hotfixes.size()), hotfixes.size())) {
      if (!stated.equals(Optional.of(b.build())) && store.remove(b.id())) {
        removed.add(b.id());
      }
    }
    return removed;
  }

  /**
   * Removes the merges prepared before {@code cutoff} that no installed hotfix was applied with: a
   * merge is the record of how its hotfix was applied for as long as that hotfix is installed.
   */
  private List<String> pruneMerges(Ledger ledger, Instant cutoff) throws IOException {
    MergeWorkspace merges = new MergeWorkspace(boot.home(), boot.clock());
    Set<String> inUse = new HashSet<>();
    for (LedgerEntry e : ledger.installed()) {
      e.mergeId().ifPresent(inUse::add);
    }
    List<String> removed = new ArrayList<>();
    for (MergeDoc doc : merges.list()) {
      if (!inUse.contains(doc.id())
          && doc.createdAt().isBefore(cutoff)
          && merges.discard(doc.id())) {
        removed.add(doc.id());
      }
    }
    return removed;
  }

  /**
   * Removes what is older than {@code olderThan}: the directories of ended runs; snapshots, except
   * those of pending runs, of failed runs and of hotfixes the ledger has installed; rolled-back
   * ledger entries whose snapshot is gone; merges no installed hotfix was applied with; and,
   * whatever their age, the hotfix baselines older than the newest two. A run that ended {@link
   * TerminalState#FAILED} (exit 4, its rollback incomplete) keeps its directory and its snapshot,
   * which its message told the operator to restore from, unless {@code includeFailed}.
   */
  PruneResult prune(Duration olderThan, boolean includeFailed) {
    Instant cutoff = boot.clock().instant().minus(olderThan);
    List<String> runsRemoved = new ArrayList<>();
    Set<String> pending = new HashSet<>();
    try {
      for (RunRecord run : journal.runs()) {
        if (run.pending()
            || (!includeFailed && run.terminalState().equals(Optional.of(TerminalState.FAILED)))) {
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
      return new PruneResult(
          runsRemoved,
          snapshotsRemoved,
          ledgerRemoved,
          pruneBaselines(),
          pruneMerges(ledger, cutoff));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot prune " + boot.home().root(), e);
    }
  }
}

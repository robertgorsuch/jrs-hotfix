package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.RetryPolicy;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.snapshot.Snapshot;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.Ledger;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The steps of the rollback plan other than the service steps. Invariants: {@code RestoreSnapshot}
 * snapshots the current files first so that its own compensation can put the hotfix back; hashes
 * are verified before the restore (the snapshot) and after it (every file against the ledger's
 * before-hash); a file the hotfix added is removed and a file it deleted comes back from the
 * snapshot; the installing run's snapshot must exist, and {@link CheckedStop} refuses before the
 * outage when any snapshot of the chain is missing; {@code RecordRolledBack} flips the ledger entry
 * and its compensation flips it back.
 */
final class RollbackSteps {

  static final String PHASE = "rollback";
  static final String RESTORE_SNAPSHOT = "restore-snapshot";
  static final String RECORD_ROLLED_BACK = "record-rolled-back";
  static final String PRE_ROLLBACK_PREFIX = "pre-rollback-";

  private RollbackSteps() {}

  /** One hotfix to roll back: its ledger entry, and the phase and id suffix of its steps. */
  record Input(LedgerEntry hotfix, String phase, String suffix) {

    Input {
      Objects.requireNonNull(hotfix, "hotfix");
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(suffix, "suffix");
    }

    String id() {
      return hotfix.id();
    }

    List<OwnedFile> files() {
      return hotfix.files();
    }

    String preRollbackStepId() {
      return PRE_ROLLBACK_PREFIX + hotfix.id();
    }

    List<Path> touched() {
      return files().stream().map(OwnedFile::path).toList();
    }
  }

  /**
   * The stop step of a rollback, refusing before the outage when a snapshot the rollback restores
   * is missing or damaged. Everything but the precheck is the wrapped stop step's.
   */
  static final class CheckedStop implements Step {
    private final Step stop;
    private final List<RestoreSnapshot> restores;

    CheckedStop(Step stop, List<RestoreSnapshot> restores) {
      this.stop = Objects.requireNonNull(stop, "stop");
      this.restores = List.copyOf(restores);
    }

    @Override
    public String id() {
      return stop.id();
    }

    @Override
    public String title() {
      return stop.title();
    }

    @Override
    public String phase() {
      return stop.phase();
    }

    @Override
    public String detail() {
      return stop.detail();
    }

    @Override
    public boolean rollbackAllOnFailure() {
      return stop.rollbackAllOnFailure();
    }

    @Override
    public boolean irreversible() {
      return stop.irreversible();
    }

    @Override
    public boolean mutating() {
      return stop.mutating();
    }

    @Override
    public RetryPolicy retryPolicy() {
      return stop.retryPolicy();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      for (RestoreSnapshot restore : restores) {
        CheckResult snapshot = restore.snapshotCheck();
        if (snapshot instanceof CheckResult.Fail) {
          return snapshot;
        }
      }
      return stop.precheck(ctx);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return stop.execute(ctx, out);
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      return stop.postcheck(ctx);
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return stop.compensate(ctx, out);
    }
  }

  /** Restores every file from the installation snapshot and deletes what the hotfix added. */
  static final class RestoreSnapshot implements Step {
    private final HotfixRuntime rt;
    private final Input in;

    RestoreSnapshot(HotfixRuntime rt, Input in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return RESTORE_SNAPSHOT + in.suffix();
    }

    @Override
    public String title() {
      return "restore the files of " + in.id() + " from the snapshot";
    }

    @Override
    public String phase() {
      return in.phase();
    }

    @Override
    public String detail() {
      return "snapshot "
          + in.hotfix().runId()
          + "/"
          + ApplySteps.SNAPSHOT
          + "; hashes verified before and after; "
          + in.files().size()
          + " file(s)";
    }

    /**
     * The installing run's snapshot exists and verifies; the part of the precheck no outage needs.
     */
    CheckResult snapshotCheck() {
      try {
        Optional<Snapshot> snapshot = snapshot();
        if (snapshot.isEmpty()) {
          return CheckResult.fail(
              "snapshot " + rt.home().snapshots().resolve(in.hotfix().runId()) + " is missing",
              "restore it from a backup of the jrs-hotfix home, or remove the hotfix by hand"
                  + " following the vendor's readme");
        }
        rt.snapshots().verify(snapshot.get());
        return CheckResult.pass();
      } catch (IOException | RuntimeException e) {
        return CheckResult.fail(
            "snapshot "
                + rt.home().snapshots().resolve(in.hotfix().runId())
                + " is unusable: "
                + Failures.describe(e),
            "restore it from a backup of the jrs-hotfix home, or remove the hotfix by hand"
                + " following the vendor's readme");
      }
    }

    @Override
    public CheckResult precheck(Context ctx) {
      CheckResult snapshot = snapshotCheck();
      if (snapshot instanceof CheckResult.Fail) {
        return snapshot;
      }
      FileOps files = rt.files();
      List<String> problems = new ArrayList<>();
      for (Path p : in.touched()) {
        if (Files.isRegularFile(p) && files.isLocked(p)) {
          problems.add(p + " is locked" + files.lockHolder(p).map(h -> " by " + h).orElse(""));
        }
      }
      // #157: the restore writes every file anew, so its owner must be assignable back
      OwnerRestore.problem(files, in.touched()).ifPresent(problems::add);
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            String.join("; ", problems),
            "end the process holding the files, or run jrs-hotfix as an account that may restore"
                + " their owners");
      }
      // review 3.3: a blind scan is a warning, not a pass
      return files
          .lockInspectionLimit()
          .map(limit -> CheckResult.warn("no locked file found, but " + limit))
          .orElseGet(CheckResult::pass);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      try {
        List<Path> current = in.touched().stream().filter(Files::isRegularFile).toList();
        Optional<Path> base =
            PackagePaths.commonAncestor(current.stream().map(Path::getParent).toList());
        if (base.isPresent()) {
          // idempotent per run and step: a retried execute keeps the first pre-rollback state
          rt.snapshots().create(ctx.runId(), in.preRollbackStepId(), current, base.get());
        }
        for (OwnedFile f : in.files()) {
          ctx.cancel().checkpoint();
          if (f.beforeSha256().isEmpty()) {
            // the hotfix added it
            Files.deleteIfExists(f.path());
          }
        }
        Optional<Snapshot> snapshot = snapshot();
        if (snapshot.isEmpty()) {
          return Failures.recoverable(
              "snapshot " + rt.home().snapshots().resolve(in.hotfix().runId()) + " is missing",
              "restore it from a backup of the jrs-hotfix home, then run the rollback again",
              in.touched(),
              backups());
        }
        // verifies the snapshot first; puts back replaced and deleted files alike
        rt.snapshots().restore(snapshot.get());
        List<String> mismatches = new ArrayList<>();
        for (OwnedFile f : in.files()) {
          if (f.beforeSha256().isPresent()) {
            Optional<String> actual = FileTarget.hashOf(files, f.path());
            if (!actual.equals(f.beforeSha256())) {
              mismatches.add(f.path() + " is " + actual.orElse("absent"));
            }
          }
        }
        if (!mismatches.isEmpty()) {
          return Failures.recoverable(
              "files differ from the snapshot after restore: " + String.join(", ", mismatches),
              "restore the snapshot by hand",
              in.touched(),
              backups());
        }
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot restore: " + e.getMessage(),
            "restore the snapshot by hand",
            in.touched(),
            backups());
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Optional<Snapshot> pre = rt.snapshots().find(ctx.runId(), in.preRollbackStepId());
        if (pre.isPresent()) {
          rt.snapshots().restore(pre.get());
        }
        for (OwnedFile f : in.files()) {
          if (f.afterSha256().isEmpty()) {
            // the hotfix deleted it; the restore brought it back
            Files.deleteIfExists(f.path());
          }
        }
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot re-apply " + in.id() + ": " + e.getMessage(),
            "restore the pre-rollback snapshot by hand",
            in.touched(),
            List.of(rt.home().snapshots().resolve(ctx.runId()).resolve(in.preRollbackStepId())));
      }
    }

    private Optional<Snapshot> snapshot() throws IOException {
      return rt.snapshots().find(in.hotfix().runId(), ApplySteps.SNAPSHOT);
    }

    private List<Path> backups() {
      return List.of(
          rt.home().snapshots().resolve(in.hotfix().runId()).resolve(ApplySteps.SNAPSHOT));
    }
  }

  /** Marks the hotfix as rolled back; compensation marks it installed again. */
  static final class RecordRolledBack implements Step {
    private final HotfixRuntime rt;
    private final Input in;

    RecordRolledBack(HotfixRuntime rt, Input in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return RECORD_ROLLED_BACK + in.suffix();
    }

    @Override
    public String title() {
      return "record " + in.id() + " as rolled back";
    }

    @Override
    public String phase() {
      return in.phase();
    }

    @Override
    public String detail() {
      return "ledger entry state = ROLLED_BACK, audit";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Ledger ledger = rt.ledger();
      Optional<LedgerEntry> entry = ledger.find(in.id());
      if (entry.isEmpty()) {
        return Failures.recoverable(in.id() + " is no longer in the ledger", "run jrs-hotfix list");
      }
      if (entry.get().state() != HotfixState.ROLLED_BACK) {
        ledger.updateState(in.id(), HotfixState.ROLLED_BACK);
        audit(ctx, out, ApplySteps.AUDIT_ROLLED_BACK, in.id() + " in run " + ctx.runId());
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Ledger ledger = rt.ledger();
      if (ledger.find(in.id()).map(h -> h.state() == HotfixState.ROLLED_BACK).orElse(false)) {
        ledger.updateState(in.id(), HotfixState.INSTALLED);
      }
      return StepResult.ok();
    }

    private void audit(Context ctx, EventSink out, String kind, String text) {
      out.emit(
          new Event.Log(
              rt.clock().instant(),
              ctx.runId(),
              Optional.of(id()),
              phase(),
              Event.Log.Level.INFO,
              "audit " + kind + ": " + text));
    }
  }
}

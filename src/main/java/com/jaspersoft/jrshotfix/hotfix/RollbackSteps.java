package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Lists;
import com.jaspersoft.jrshotfix.service.ServiceSteps;
import com.jaspersoft.jrshotfix.snapshot.Snapshot;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import com.jaspersoft.jrshotfix.state.UndoRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The steps of the rollback plan other than the service steps (0.6 design, section 3). Invariants:
 * only the latest apply is undone, from {@code undo/}; the first stop refuses before the outage
 * when the undo's snapshot is damaged or any file is no longer as the apply left it; {@code
 * RestoreSnapshot} snapshots the current files first so that its own compensation can put the
 * hotfix back; hashes are verified before the restore (the snapshot) and after it (every file
 * against the record's before-hash); a file the hotfix added is removed and a file it deleted comes
 * back; {@code DiscardUndo} moves {@code undo/} into the run, and its compensation moves it back.
 */
final class RollbackSteps {

  static final String PHASE = "rollback";
  static final String RESTORE_SNAPSHOT = "restore-snapshot";
  static final String DISCARD_UNDO = "discard-undo";
  static final String PRE_ROLLBACK_PREFIX = "pre-rollback-";

  private static final String SNAPSHOT_REMEDIATION =
      "restore the jrs-hotfix home from a backup and run the rollback again, or remove the hotfix"
          + " by hand following the vendor's readme";

  private RollbackSteps() {}

  /** The hotfix to roll back: what the latest apply did. */
  record Input(UndoRecord undo) {

    Input {
      Objects.requireNonNull(undo, "undo");
    }

    String id() {
      return undo.id();
    }

    List<OwnedFile> files() {
      return undo.files();
    }

    String preRollbackStepId() {
      return PRE_ROLLBACK_PREFIX + undo.id();
    }

    List<Path> touched() {
      return files().stream().map(OwnedFile::path).toList();
    }
  }

  /**
   * The stop step of a rollback, which starts the outage: it also refuses when the undo's snapshot
   * is missing or damaged, or a file changed since the apply (before the controller check), or when
   * the service runs but {@code baseUrl} does not answer (after it).
   */
  static Step stop(HotfixRuntime rt, Input in, RestoreSnapshot restore) {
    return ServiceSteps.stop(
        rt,
        PHASE,
        ServiceSteps.STOP,
        () -> {
          CheckResult snapshot = restore.snapshotCheck();
          if (snapshot instanceof CheckResult.Fail) {
            return snapshot;
          }
          List<String> changed = changedSinceApply(rt, in.undo());
          if (!changed.isEmpty()) {
            return CheckResult.fail(
                changedProblem(in.undo(), changed),
                "the server was changed after the hotfix was applied, so jrs-hotfix does not put"
                    + " its files back; the files it replaced are under "
                    + rt.home().undo()
                    + " for whoever puts the server back by hand");
          }
          return CheckResult.pass();
        },
        () -> ApplySteps.baseUrlCheck(rt));
  }

  /**
   * The files that are no longer as the apply left them: a file it wrote that now has another hash
   * or is gone, and a file it deleted that is there again. Each as "path is hash" or "path is
   * absent"; empty when the server is as the apply left it.
   */
  static List<String> changedSinceApply(HotfixRuntime rt, UndoRecord undo) {
    List<String> changed = new ArrayList<>();
    for (OwnedFile f : undo.files()) {
      Optional<String> actual = FileTarget.hashOf(rt.files(), f.path());
      if (!actual.equals(f.afterSha256())) {
        changed.add(f.path() + " is " + actual.orElse("absent"));
      }
    }
    return changed;
  }

  static String changedProblem(UndoRecord undo, List<String> changed) {
    return changed.size()
        + " file(s) changed since "
        + undo.id()
        + " was applied: "
        + Lists.firstAndMore(changed, 8);
  }

  /** Restores every file from the undo's snapshot and deletes what the hotfix added. */
  static final class RestoreSnapshot extends HotfixStep<Input> {
    RestoreSnapshot(HotfixRuntime rt, Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return RESTORE_SNAPSHOT;
    }

    @Override
    public String title() {
      return "restore the files of " + in.id() + " from the snapshot";
    }

    @Override
    public String phase() {
      return PHASE;
    }

    @Override
    public String detail() {
      return "snapshot "
          + rt.home().undo()
          + "; hashes verified before and after; "
          + in.files().size()
          + " file(s)";
    }

    /** The undo's snapshot exists and verifies; the part of the precheck no outage needs. */
    CheckResult snapshotCheck() {
      try {
        Optional<Snapshot> snapshot = snapshot();
        if (snapshot.isEmpty()) {
          return CheckResult.fail(missingSnapshot(), SNAPSHOT_REMEDIATION);
        }
        rt.snapshots().verify(snapshot.get());
        return CheckResult.pass();
      } catch (IOException | RuntimeException e) {
        return CheckResult.fail(
            "snapshot " + rt.home().undo() + " is unusable: " + Failures.describe(e),
            SNAPSHOT_REMEDIATION);
      }
    }

    @Override
    public CheckResult precheck(Context ctx) {
      CheckResult snapshot = snapshotCheck();
      if (snapshot instanceof CheckResult.Fail) {
        return snapshot;
      }
      FileOps files = rt.files();
      List<String> problems =
          ApplySteps.locked(
              files,
              in.touched(),
              (p, holder) -> p + " is locked" + holder.map(h -> " by " + h).orElse(""));
      // #157: the restore writes every file anew, so its owner must be assignable back
      OwnerRestore.problem(files, in.touched()).ifPresent(problems::add);
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            String.join("; ", problems),
            "end the process holding the files, or run jrs-hotfix as an account that may restore"
                + " their owners");
      }
      return ApplySteps.noLockFound(files);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      try {
        // checked before anything is touched: a missing snapshot leaves the files as they are
        Optional<Snapshot> snapshot = snapshot();
        if (snapshot.isEmpty()) {
          return Failures.recoverable(
              missingSnapshot(), SNAPSHOT_REMEDIATION, in.touched(), backups());
        }
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
            List.of(rt.snapshots().snapshotDir(ctx.runId(), in.preRollbackStepId())));
      }
    }

    private String missingSnapshot() {
      return "snapshot " + rt.home().undo() + " of " + in.id() + " is missing";
    }

    private Optional<Snapshot> snapshot() throws IOException {
      return rt.snapshots().find(in.undo().runId(), ApplySteps.SNAPSHOT);
    }

    private List<Path> backups() {
      return List.of(rt.home().undo());
    }
  }

  /**
   * Uses the undo up: {@code undo/} moves into this run, so nothing is left to undo once the run
   * ends; compensation moves it back.
   */
  static final class DiscardUndo extends HotfixStep<Input> {
    DiscardUndo(HotfixRuntime rt, Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return DISCARD_UNDO;
    }

    @Override
    public String title() {
      return "use the undo of " + in.id() + " up";
    }

    @Override
    public String phase() {
      return PHASE;
    }

    @Override
    public String detail() {
      return rt.home().undo() + " is removed; there is nothing further to undo, audit";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      try {
        rt.undo().discard(in.undo().runId(), ctx.runId());
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot remove the undo of " + in.id() + ": " + e.getMessage(),
            "check that " + rt.home().root() + " is writable");
      }
      audit(ctx, out, ApplySteps.AUDIT_ROLLED_BACK, in.id() + " in run " + ctx.runId());
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        rt.undo().undiscard(in.undo().runId(), ctx.runId());
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot put the undo of " + in.id() + " back: " + e.getMessage(),
            "move " + rt.undo().undone(ctx.runId()) + " back to " + rt.home().undo() + " by hand");
      }
    }
  }
}

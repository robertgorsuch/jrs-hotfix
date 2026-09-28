package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.snapshot.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The backup phase of the apply plan: the snapshot every later step restores from. Invariant: the
 * snapshot is verified as it is created, and an existing snapshot for the same run and step is
 * reused rather than rewritten, so re-execution converges. The ledger entry's {@code snapshotRef}
 * is what records it; nothing else does.
 */
final class BackupSteps {

  private BackupSteps() {}

  /** Step 2: snapshot every file that will be replaced or deleted. */
  static final class TakeSnapshot extends ApplySteps.ReadOnly {
    TakeSnapshot(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.SNAPSHOT;
    }

    @Override
    public String title() {
      return "snapshot the files this hotfix replaces or deletes";
    }

    @Override
    public String phase() {
      return ApplySteps.BACKUP;
    }

    @Override
    public String detail() {
      return "up to "
          + in.touched().size()
          + " file(s) -> "
          + rt.home().snapshots().resolve("{runId}").resolve(ApplySteps.SNAPSHOT);
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    /**
     * Snapshots every path the plan touches that exists right now, not only those the plan expected
     * to find: a target the plan saw as absent may exist by now, and without this it would be
     * overwritten with nothing kept to put back.
     */
    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<Path> paths = in.touched().stream().filter(Files::isRegularFile).distinct().toList();
      try {
        Snapshot snapshot =
            rt.snapshots().create(ctx.runId(), ApplySteps.SNAPSHOT, paths, in.paths().commonBase());
        log(ctx, out, Event.Log.Level.INFO, paths.size() + " file(s) saved to " + snapshot.dir());
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot snapshot: " + Failures.describe(e),
            "check free space and permissions under " + rt.home().snapshots());
      }
    }
  }
}

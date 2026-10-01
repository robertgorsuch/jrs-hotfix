package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.snapshot.Snapshot;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import com.jaspersoft.jrshotfix.state.UndoRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The last phase of the apply plan: the run's snapshot becomes {@code undo/}, with the record of
 * what the apply did (0.6 design, sections 2 and 3). Invariants: the undo is promoted only after
 * the swap has verified what it landed; its before-hashes come from the run's own snapshot, so they
 * are what a rollback restores; the previous undo is replaced only by this step, so an apply that
 * fails earlier leaves it as it was; the package's files are the hotfix's baseline from staging on,
 * and a baseline that cannot be written never fails this step.
 */
final class RecordSteps {

  private RecordSteps() {}

  /** Step 9: keep the run's snapshot as the undo and remove the emptied staging tree. */
  static final class PromoteUndo extends HotfixStep<ApplyInput> {
    PromoteUndo(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.PROMOTE_UNDO;
    }

    @Override
    public String title() {
      return "keep the snapshot as the undo of " + in.contents().id();
    }

    @Override
    public String phase() {
      return ApplySteps.RECORD;
    }

    @Override
    public String detail() {
      return rt.home().undo()
          + " with "
          + rows(planState()).size()
          + " file(s); the previous undo"
          + " is replaced";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      PackageContents c = in.contents();
      PriorState before;
      Optional<Snapshot> snapshot;
      try {
        snapshot = rt.snapshots().find(ctx.runId(), ApplySteps.SNAPSHOT);
        PriorState fromSnapshot = PriorState.of(rt.snapshots(), ctx, ApplySteps.SNAPSHOT);
        before = fromSnapshot.known() ? fromSnapshot : planState();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot read the pre-swap snapshot of run " + ctx.runId() + ": " + e.getMessage(),
            "without it the undo's before-hashes would not match what rollback restores");
      }
      if (snapshot.isEmpty()) {
        return Failures.recoverable(
            "the snapshot of run " + ctx.runId() + " is gone",
            "without it this apply cannot be undone; the hotfix is applied");
      }
      try {
        // what this package ships is the vendor's level from now on: the base of the next merge.
        // Staging wrote it before the outage, so this finds it there and reads nothing; a run
        // staged by an older jrs-hotfix writes it now. The server is up again by this step, so a
        // failure is said and not made the run's: undoing a good apply for it would be worse.
        rt.baselines().addHotfix(in.packageFile(), c, rt.settings().webappName());
      } catch (IOException | RuntimeException e) {
        log(
            ctx,
            out,
            Event.Log.Level.WARN,
            "the baseline of "
                + c.id()
                + " could not be written ("
                + Failures.describe(e)
                + "); add it with `jrs-hotfix baseline add <package.zip>` before the next"
                + " hotfix");
      }
      try {
        rt.undo()
            .promote(
                snapshot.get().dir(),
                new UndoRecord(
                    c.id(),
                    c.release(),
                    c.edition(),
                    c.build(),
                    c.title(),
                    ctx.runId(),
                    rt.clock().instant(),
                    rows(before),
                    kept(),
                    in.merge().map(MergeDoc::id),
                    in.merge().map(MergeDoc::baselines).orElse(List.of())));
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot keep the undo of " + c.id() + ": " + e.getMessage(),
            "check free space and rights under " + rt.home().root());
      }
      audit(
          ctx,
          out,
          ApplySteps.AUDIT_APPLIED,
          c.id() + " build " + c.build() + " in run " + ctx.runId());
      removeStaging(ctx, out);
      return StepResult.ok();
    }

    /** The swap moved every payload out of staging; what is left is empty directories. */
    private void removeStaging(Context ctx, EventSink out) {
      try {
        Trees.deleteRecursively(in.stagingDir(ctx));
      } catch (IOException e) {
        log(ctx, out, Event.Log.Level.WARN, "staging left behind: " + e.getMessage());
      }
    }

    /**
     * The files are already swapped when this step runs; a failure here (a full disk, say)
     * therefore undoes the whole apply, so "rolled back" means the files are back too.
     */
    @Override
    public boolean rollbackAllOnFailure() {
      return true;
    }

    /**
     * Puts the run's snapshot back in the run, where the swap's compensation finds it, and the
     * previous undo back into place.
     */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        rt.undo().demote(ctx.runId(), rt.snapshots().snapshotDir(ctx.runId(), ApplySteps.SNAPSHOT));
        audit(
            ctx,
            out,
            ApplySteps.AUDIT_ROLLED_BACK,
            in.contents().id() + " (compensation of run " + ctx.runId() + ")");
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot put the previous undo back: " + e.getMessage(),
            "check " + rt.home().undo() + " by hand");
      }
    }

    /**
     * One owned file per target. Every before-hash comes from {@code before}, so what is written
     * here is what a rollback will find after it restores the snapshot; a delete has no after-hash.
     */
    private List<OwnedFile> rows(PriorState before) {
      List<OwnedFile> rows = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        rows.add(
            new OwnedFile(
                t.target(),
                t.action().name().toLowerCase(Locale.ROOT),
                before.known() ? before.before(t.target()) : t.before(),
                t.after(),
                t.entry().packageSha256()));
      }
      return List.copyOf(rows);
    }

    /** The files the package ships that stayed as the site has them. */
    private List<UndoRecord.KeptFile> kept() {
      List<UndoRecord.KeptFile> kept = new ArrayList<>();
      for (PackageContents.Kept k : in.contents().kept()) {
        kept.add(
            new UndoRecord.KeptFile(in.paths().resolve(k.path()), k.vendorSha256(), k.reason()));
      }
      return kept;
    }

    /** The plan's own view, used for the detail line and when no snapshot was taken. */
    private PriorState planState() {
      Map<Path, String> hashes = new LinkedHashMap<>();
      for (FileTarget t : in.targets()) {
        t.before().ifPresent(h -> hashes.put(t.target().toAbsolutePath().normalize(), h));
      }
      return new PriorState(hashes, true);
    }
  }
}

package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.Ledger;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.Origin;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The record phase of the apply plan: the ledger entry that makes the hotfix visible to {@code
 * list} and to a later rollback. Invariants: the entry is written only after the swap has verified
 * what it landed, so nothing is recorded as installed that is not; its before-hashes come from the
 * run's own snapshot, so they are what a rollback restores; audit lines go to the run's event
 * stream, not to the ledger.
 */
final class RecordSteps {

  private RecordSteps() {}

  /** Step 8: record the installation in the ledger and remove the emptied staging tree. */
  static final class RecordInstalled implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    RecordInstalled(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return ApplySteps.RECORD_INSTALLED;
    }

    @Override
    public String title() {
      return "record " + in.contents().id() + " as installed";
    }

    @Override
    public String phase() {
      return ApplySteps.RECORD;
    }

    @Override
    public String detail() {
      return "ledger entry with " + rows(planState()).size() + " file(s)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Ledger ledger = rt.ledger();
      PackageContents c = in.contents();
      String id = c.id();
      PriorState before;
      try {
        PriorState fromSnapshot = PriorState.of(rt.snapshots(), ctx, ApplySteps.SNAPSHOT);
        before = fromSnapshot.known() ? fromSnapshot : planState();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot read the pre-swap snapshot of run " + ctx.runId() + ": " + e.getMessage(),
            "without it the recorded before-hashes would not match what rollback restores");
      }
      Optional<LedgerEntry> existing = ledger.find(id);
      if (existing.isPresent()) {
        LedgerEntry h = existing.get();
        if (h.state() == HotfixState.INSTALLED) {
          if (h.runId().equals(ctx.runId())) {
            removeStaging(ctx, out);
            return StepResult.ok();
          }
          return Failures.recoverable(
              id + " is already recorded as installed by run " + h.runId(),
              "roll back the earlier installation first");
        }
        // One entry per id; a rolled-back entry is superseded by this installation.
        ledger.delete(id);
      }
      ledger.recordInstalled(
          new LedgerEntry(
              id,
              c.release(),
              c.edition(),
              c.build(),
              c.title(),
              HotfixState.INSTALLED,
              Origin.TOOL,
              ctx.runId(),
              Optional.of(ctx.runId() + "/" + ApplySteps.SNAPSHOT),
              rt.clock().instant(),
              rows(before)));
      audit(
          ctx,
          out,
          ApplySteps.AUDIT_APPLIED,
          id + " build " + c.build() + " in run " + ctx.runId());
      removeStaging(ctx, out);
      return StepResult.ok();
    }

    /** The swap moved every payload out of staging; what is left is empty directories. */
    private void removeStaging(Context ctx, EventSink out) {
      try {
        Trees.deleteRecursively(in.stagingDir(ctx));
      } catch (IOException e) {
        out.emit(
            new Event.Log(
                rt.clock().instant(),
                ctx.runId(),
                Optional.of(id()),
                phase(),
                Event.Log.Level.WARN,
                "staging left behind: " + e.getMessage()));
      }
    }

    /**
     * The files are already swapped when this step runs, and its own compensation only flips an
     * entry that may not exist yet; a failure here (a full disk, say) therefore undoes the whole
     * apply, so "rolled back" means the files are back too.
     */
    @Override
    public boolean rollbackAllOnFailure() {
      return true;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Ledger ledger = rt.ledger();
      String id = in.contents().id();
      Optional<LedgerEntry> existing = ledger.find(id);
      if (existing.isPresent()
          && existing.get().state() == HotfixState.INSTALLED
          && existing.get().runId().equals(ctx.runId())) {
        ledger.updateState(id, HotfixState.ROLLED_BACK);
        audit(
            ctx,
            out,
            ApplySteps.AUDIT_ROLLED_BACK,
            id + " (compensation of run " + ctx.runId() + ")");
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
                t.after()));
      }
      return List.copyOf(rows);
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

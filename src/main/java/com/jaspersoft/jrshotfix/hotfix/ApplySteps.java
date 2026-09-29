package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.platform.DiskSpace;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import com.jaspersoft.jrshotfix.service.ServiceSteps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ids, phases and read-only steps of the apply plan. Invariants: verify-phase steps do all
 * their work in {@code precheck} and mutate nothing, so a refusal ends the run with exit code 2;
 * every mutating step re-checks the current state before acting, so re-execution converges; {@code
 * AtomicSwap} restores from the snapshot when compensated. What each touched file looked like
 * beforehand comes from {@link PriorState}, that is from the run's own snapshot rather than from
 * the plan.
 */
final class ApplySteps {

  static final String VERIFY = "verify";
  static final String BACKUP = "backup";
  static final String APPLY = "apply";
  static final String RECORD = "record";

  static final String PREFLIGHT = "preflight";
  static final String SNAPSHOT = "snapshot";
  static final String STAGE_FILES = "stage-files";
  static final String ATOMIC_SWAP = "atomic-swap";
  static final String RECORD_INSTALLED = "record-installed";

  static final String AUDIT_CHECKSUM_CONFIRMED = "hotfix.checksum-confirmed";
  static final String AUDIT_APPLIED = "hotfix.applied";
  static final String AUDIT_ROLLED_BACK = "hotfix.rolled-back";

  private ApplySteps() {}

  /**
   * Refuses before the outage when the service is running but {@code baseUrl} does not answer:
   * otherwise the wait after the start fails only after its full timeout and the whole run is
   * compensated with the service down all along. A stopped service, or one whose state cannot be
   * read (the controller check reports that), is not probed.
   */
  static CheckResult baseUrlCheck(HotfixRuntime rt) {
    ServiceController.State state;
    try {
      state = rt.controller().state();
    } catch (RuntimeException e) {
      return CheckResult.pass();
    }
    if (state != ServiceController.State.RUNNING) {
      return CheckResult.pass();
    }
    Optional<String> problem = rt.probe().problem();
    if (problem.isEmpty()) {
      return CheckResult.pass();
    }
    String url = rt.settings().baseUrl().toString();
    return CheckResult.fail(
        "baseUrl "
            + url
            + " does not answer while the service is running: "
            + problem.get()
            + "; fix it with `jrs-hotfix settings set baseUrl <url>`",
        "correct baseUrl, then run again; nothing was changed");
  }

  /** Read-only base for the verify phase. */
  abstract static class ReadOnly implements Step {
    final HotfixRuntime rt;
    final ApplyInput in;

    ReadOnly(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    void log(Context ctx, EventSink out, Event.Log.Level level, String message) {
      out.emit(
          new Event.Log(
              rt.clock().instant(), ctx.runId(), Optional.of(id()), phase(), level, message));
    }
  }

  /**
   * Step 1: the package fits this installation and the host can take it. Release and edition must
   * match the webapp, the hotfix must not be installed already, the directories written to must be
   * writable, the home's volume must hold staging and the snapshot, the replaced files' owners must
   * be restorable, and the service must be identifiable.
   */
  static final class Preflight extends ReadOnly {
    Preflight(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return PREFLIGHT;
    }

    @Override
    public String title() {
      return "check this installation";
    }

    @Override
    public String phase() {
      return VERIFY;
    }

    @Override
    public String detail() {
      return "release and edition, not installed already, write access, free space, file owners,"
          + " service state";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> problems = new ArrayList<>();
      problems.addAll(HotfixPlans.applicability(rt, in.contents()));
      for (Path dir :
          List.of(rt.settings().webappDir(), rt.settings().installDir(), rt.home().root())) {
        if (!rt.files().isWritable(dir)) {
          problems.add(dir + " is not writable by this account");
        }
      }
      // staged bytes are about the package size; the snapshot holds the files replaced or deleted
      long payload = 0;
      try {
        payload = Files.size(in.packageFile());
      } catch (IOException e) {
        problems.add("cannot read " + in.packageFile() + ": " + e.getMessage());
      }
      problems.addAll(
          DiskSpace.problems(
              rt.files(),
              List.of(
                  new DiskSpace.Need("staging", rt.home().root(), payload * 2),
                  new DiskSpace.Need("snapshot", rt.home().root(), snapshotBytes(problems)))));
      OwnerRestore.problem(rt.files(), in.snapshotPaths()).ifPresent(problems::add);
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            String.join("; ", problems),
            "fix the listed problems, then run again; nothing was changed");
      }
      CheckResult controller = ServiceSteps.controllerCheck(rt);
      return controller instanceof CheckResult.Fail ? controller : baseUrlCheck(rt);
    }

    private long snapshotBytes(List<String> problems) {
      long bytes = 0;
      for (Path p : in.snapshotPaths()) {
        if (!Files.isRegularFile(p)) {
          continue;
        }
        try {
          bytes += Files.size(p);
        } catch (IOException e) {
          problems.add("cannot size " + p + " for the snapshot: " + e.getMessage());
        }
      }
      return bytes;
    }
  }
}

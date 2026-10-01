package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every step of the apply plan executed a second time after a complete first execution (what a
 * resume after a crash does) leaves the installation, the undo, the run directory, the service and
 * the service exactly as one execution did; the same holds for every compensation.
 */
class ApplyIdempotencyTest {

  @TempDir Path tmp;

  // ---------------------------------------------------------------- helpers

  private static Map<String, String> tree(HotfixFixture f, String prefix, Path dir)
      throws IOException {
    Map<String, String> out = new TreeMap<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(Files::isRegularFile).forEach(files::add);
    }
    for (Path p : files) {
      out.put(prefix + "/" + dir.relativize(p).toString().replace('\\', '/'), f.sha(p));
    }
    return out;
  }

  /** Everything a step may change, except the run journal, which is append-only by design. */
  private static Map<String, String> state(HotfixFixture f, String runId) throws IOException {
    Map<String, String> m = new TreeMap<>();
    m.putAll(tree(f, "install", f.paths.installDir()));
    m.putAll(tree(f, "undo", f.home.undo()));
    m.putAll(tree(f, "run", f.home.runDir(runId)));
    m.put("service", f.platform.controller.state().name());
    m.put("calls", f.platform.controller.calls().toString());
    m.put("undo.json", f.undo.read().toString());
    return m;
  }

  private static void executeOk(Step step, Context ctx) {
    CheckResult pre = step.precheck(ctx);
    assertThat(pre)
        .as("precheck of " + step.id() + ": " + pre)
        .isNotInstanceOf(CheckResult.Fail.class);
    StepResult result = step.execute(ctx, EventSink.discard());
    assertThat(result)
        .as("execute of " + step.id() + ": " + result)
        .isInstanceOf(StepResult.Ok.class);
    CheckResult post = step.postcheck(ctx);
    assertThat(post)
        .as("postcheck of " + step.id() + ": " + post)
        .isNotInstanceOf(CheckResult.Fail.class);
  }

  private static void compensateOk(Step step, Context ctx) {
    StepResult result = step.compensate(ctx, EventSink.discard());
    assertThat(result)
        .as("compensate of " + step.id() + ": " + result)
        .isInstanceOf(StepResult.Ok.class);
  }

  private static void runUpTo(Plan plan, Context ctx, String stepId) {
    for (Step s : plan.steps()) {
      executeOk(s, ctx);
      if (s.id().equals(stepId)) {
        return;
      }
    }
    throw new AssertionError("no step " + stepId);
  }

  private static void assertReexecutionConverges(HotfixFixture f, String runId, String stepId)
      throws IOException {
    Plan plan = f.plan();
    Context ctx = f.ctx(runId);
    runUpTo(plan, ctx, stepId);
    Map<String, String> once = state(f, runId);
    executeOk(HotfixFixture.step(plan, stepId), ctx);
    assertThat(state(f, runId)).as(stepId).isEqualTo(once);
  }

  private static void assertCompensationConverges(HotfixFixture f, String runId, String stepId)
      throws IOException {
    Plan plan = f.plan();
    Context ctx = f.ctx(runId);
    runUpTo(plan, ctx, "promote-undo");
    Step step = HotfixFixture.step(plan, stepId);
    compensateOk(step, ctx);
    Map<String, String> once = state(f, runId);
    compensateOk(step, ctx);
    assertThat(state(f, runId)).as(stepId).isEqualTo(once);
  }

  // ---------------------------------------------------------------- tests

  @Test
  void should_not_mutate_when_read_only_steps_execute_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      Context ctx = f.ctx("r-ro");
      List<String> readOnly = List.of("preflight", "wait-for-server");
      List<String> seen = new ArrayList<>();
      for (Step step : plan.steps()) {
        executeOk(step, ctx);
        if (readOnly.contains(step.id())) {
          assertThat(step.mutating()).as(step.id()).isFalse();
          Map<String, String> before = state(f, "r-ro");
          executeOk(step, ctx);
          compensateOk(step, ctx);
          assertThat(state(f, "r-ro")).as(step.id()).isEqualTo(before);
          seen.add(step.id());
        }
      }
      assertThat(seen).containsExactlyInAnyOrderElementsOf(readOnly);
    }
  }

  @Test
  void should_converge_when_take_snapshot_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, "r-snap", "snapshot");
      assertThat(f.snapshots.find("r-snap", "snapshot")).isPresent();
    }
  }

  @Test
  void should_converge_when_stage_files_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, "r-stage", "stage-files");
      assertThat(f.home.stagingDir("r-stage").resolve(HotfixFixture.FOO)).hasContent("patched foo");
    }
  }

  @Test
  void should_restage_only_what_is_wrong_when_stage_files_runs_again() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      Context ctx = f.ctx("r-restage");
      runUpTo(plan, ctx, "stage-files");
      Path staged = f.home.stagingDir("r-restage").resolve(HotfixFixture.NEW);
      Files.writeString(staged, "damaged");
      Map<String, String> before = state(f, "r-restage");
      executeOk(HotfixFixture.step(plan, "stage-files"), ctx);
      assertThat(staged).hasContent("brand new");
      Map<String, String> after = state(f, "r-restage");
      after.keySet().removeIf(k -> k.endsWith("new-1.0.jar"));
      before.keySet().removeIf(k -> k.endsWith("new-1.0.jar"));
      assertThat(after).isEqualTo(before);
    }
  }

  @Test
  void should_converge_when_stage_files_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, "r-stage-c", "stage-files");
      assertThat(f.home.stagingDir("r-stage-c")).doesNotExist();
    }
  }

  @Test
  void should_converge_when_atomic_swap_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, "r-swap", "atomic-swap");
      assertThat(f.target(HotfixFixture.FOO)).hasContent("patched foo");
    }
  }

  @Test
  void should_converge_when_atomic_swap_executes_after_a_partial_swap() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      Context ctx = f.ctx("r-partial");
      runUpTo(plan, ctx, "stop-service");
      // the first file landed and one deletion happened, then the process died
      Path staged = f.home.stagingDir("r-partial").resolve(HotfixFixture.FOO);
      f.platform.files().atomicReplace(staged, f.target(HotfixFixture.FOO));
      Files.delete(f.target(HotfixFixture.BAR));
      executeOk(HotfixFixture.step(plan, "atomic-swap"), ctx);
      assertThat(f.target(HotfixFixture.FOO)).hasContent("patched foo");
      assertThat(f.target(HotfixFixture.NEW)).hasContent("brand new");
      assertThat(f.target(HotfixFixture.TOOL)).hasContent("patched tool");
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
    }
  }

  @Test
  void should_refuse_the_swap_when_the_payload_was_never_staged() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      Context ctx = f.ctx("r-unstaged");
      runUpTo(plan, ctx, "snapshot");
      CheckResult pre = HotfixFixture.step(plan, "atomic-swap").precheck(ctx);
      assertThat(pre).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) pre).message())
          .contains("not staged")
          .contains("foo-1.2.3.jar");
      assertThat(((CheckResult.Fail) pre).remediation()).doesNotContain("jrsctl");
    }
  }

  @Test
  void should_fail_the_swap_postcheck_when_a_swapped_file_changed_under_it() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      Context ctx = f.ctx("r-post");
      runUpTo(plan, ctx, "atomic-swap");
      Files.writeString(f.target(HotfixFixture.FOO), "tampered after the swap");
      CheckResult after = HotfixFixture.step(plan, "atomic-swap").postcheck(ctx);
      assertThat(after).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) after).message()).contains("foo-1.2.3.jar").contains("hash");
    }
  }

  @Test
  void should_converge_when_atomic_swap_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      String oldFoo = f.sha(f.target(HotfixFixture.FOO));
      assertCompensationConverges(f, "r-swap-c", "atomic-swap");
      assertThat(f.sha(f.target(HotfixFixture.FOO))).isEqualTo(oldFoo);
      assertThat(f.target(HotfixFixture.NEW)).doesNotExist();
      assertThat(f.target(HotfixFixture.BAR)).exists();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).exists();
      assertThat(f.target(HotfixFixture.TOOL)).hasContent("old tool");
    }
  }

  @Test
  void should_converge_when_promote_undo_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, "r-rec", "promote-undo");
      assertThat(f.undo.read().orElseThrow().runId()).isEqualTo("r-rec");
      assertThat(f.home.undo().resolve("manifest.json")).isRegularFile();
      assertThat(f.home.stagingDir("r-rec")).doesNotExist();
    }
  }

  @Test
  void should_converge_when_promote_undo_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, "r-rec-c", "promote-undo");
      assertThat(f.undo.read()).isEmpty();
      assertThat(f.home.runDir("r-rec-c").resolve("snapshot").resolve("manifest.json"))
          .isRegularFile();
    }
  }

  @Test
  void should_stop_once_when_stop_service_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, "r-stop", "stop-service");
      assertThat(f.platform.controller.calls()).containsExactly("stop");
      assertThat(f.platform.controller.state()).isEqualTo(ServiceController.State.STOPPED);
    }
  }

  @Test
  void should_start_once_when_stop_service_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      Context ctx = f.ctx("r-stop-c");
      runUpTo(plan, ctx, "stop-service");
      Step stop = HotfixFixture.step(plan, "stop-service");
      compensateOk(stop, ctx);
      compensateOk(stop, ctx);
      assertThat(f.platform.controller.calls()).containsExactly("stop", "start");
      assertThat(f.platform.controller.state()).isEqualTo(ServiceController.State.RUNNING);
    }
  }

  @Test
  void should_start_once_when_start_service_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, "r-start", "start-service");
      assertThat(f.platform.controller.calls()).containsExactly("stop", "start");
      assertThat(f.platform.controller.state()).isEqualTo(ServiceController.State.RUNNING);
    }
  }

  @Test
  void should_stop_once_when_start_service_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, "r-start-c", "start-service");
      assertThat(f.platform.controller.calls()).containsExactly("stop", "start", "stop");
      assertThat(f.platform.controller.state()).isEqualTo(ServiceController.State.STOPPED);
    }
  }
}

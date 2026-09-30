package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.Origin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollbackPlanTest {

  @TempDir Path tmp;

  @Test
  void should_restore_replaced_files_remove_added_and_put_back_deleted_when_rolled_back()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Path cache = ApplyPlanTest.jspCache(f);
      Plan rb =
          f.plans.planRollback(new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false));
      assertThat(HotfixFixture.ids(rb))
          .containsExactly(
              "stop-service",
              "restore-snapshot",
              "clear-jsp-cache",
              "start-service",
              "wait-for-server",
              "record-rolled-back");
      assertThat(rb.planId()).startsWith("hotfix-rollback-");
      assertThat(rb.summary().operation()).isEqualTo("hotfix.rollback");
      assertThat(rb.fingerprint().inputs())
          .containsKeys("settings", "hotfix:JRSHF-10.0.0-20260730-0457")
          .containsKey("file:" + f.target(HotfixFixture.FOO));
      assertThat(f.run(rb, "r2")).isInstanceOf(RunOutcome.Succeeded.class);
      // restored pages are older than what Tomcat compiled from the hotfix's, so it would keep them
      assertThat(cache).doesNotExist();
      assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
          .isEqualTo("old foo");
      assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar")).doesNotExist();
      assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar")).exists();
      assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.0.0.jar")).exists();
      assertThat(Files.readString(f.target(HotfixFixture.TOOL))).isEqualTo("old tool");
      assertThat(f.ledger.find("JRSHF-10.0.0-20260730-0457").orElseThrow().state())
          .isEqualTo(HotfixState.ROLLED_BACK);
    }
  }

  @Test
  void should_install_again_when_a_rolled_back_hotfix_is_applied_again() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.plan(), "r1")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(
              f.run(
                  f.plans.planRollback(new HotfixPlans.RollbackArgs(HotfixFixture.ID, false)),
                  "r2"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.run(f.plan(), "r3")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("patched foo");
      // one entry per id: the new installation replaces the rolled-back one
      assertThat(f.ledger.all()).filteredOn(e -> e.id().equals(HotfixFixture.ID)).hasSize(1);
      LedgerEntry e = f.ledger.find(HotfixFixture.ID).orElseThrow();
      assertThat(e.state()).isEqualTo(HotfixState.INSTALLED);
      assertThat(e.runId()).isEqualTo("r3");
      assertThat(e.snapshotRef()).contains("r3/snapshot");
    }
  }

  @Test
  void should_refuse_when_the_snapshot_directory_was_deleted_by_hand() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Trees.deleteRecursively(f.home.snapshots().resolve("r1"));
      int callsBefore = f.platform.controller.calls().size();
      RunOutcome out =
          f.run(
              f.plans.planRollback(
                  new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false)),
              "r2");
      assertThat(out).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) out).message()).contains("snapshot").contains("r1");
      assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
          .isEqualTo("patched foo");
      // refused before the outage: the service was never stopped
      assertThat(f.platform.controller.calls()).hasSize(callsBefore);
    }
  }

  @Test
  void should_touch_nothing_when_the_restore_finds_the_snapshot_missing() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Plan rb =
          f.plans.planRollback(new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false));
      Trees.deleteRecursively(f.home.snapshots().resolve("r1"));
      StepResult result =
          HotfixFixture.step(rb, "restore-snapshot").execute(f.ctx("r2"), EventSink.discard());
      assertThat(result).isNotInstanceOf(StepResult.Ok.class);
      // the file the hotfix added is still there: a missing snapshot deletes nothing
      assertThat(Files.readString(f.target(HotfixFixture.NEW))).isEqualTo("brand new");
    }
  }

  @Test
  void should_refuse_a_recorded_entry_when_rolled_back() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.ledger.recordInstalled(
          new LedgerEntry(
              "JRSHF-10.0.0-20260101-0000",
              "10.0.0",
              "PRO",
              "20260101_0000",
              "by hand",
              HotfixState.INSTALLED,
              Origin.RECORDED,
              "recorded",
              Optional.empty(),
              Instant.now(),
              List.of()));
      assertThatThrownBy(
              () ->
                  f.plans.planRollback(
                      new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260101-0000", false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("by hand");
    }
  }

  @Test
  void should_refuse_when_the_hotfix_is_unknown_or_not_installed() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThatThrownBy(
              () -> f.plans.planRollback(new HotfixPlans.RollbackArgs("JRSHF-nope", false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("unknown hotfix");
      f.run(f.plan(), "r1");
      f.ledger.updateState(HotfixFixture.ID, HotfixState.ROLLED_BACK);
      assertThatThrownBy(
              () -> f.plans.planRollback(new HotfixPlans.RollbackArgs(HotfixFixture.ID, false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("is not installed");
    }
  }

  @Test
  void should_stay_rolled_back_when_recorded_twice_and_flip_back_when_compensated()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plan(), "r1");
      Plan rb = f.plans.planRollback(new HotfixPlans.RollbackArgs(HotfixFixture.ID, false));
      Step record = HotfixFixture.step(rb, "record-rolled-back");
      assertThat(record.execute(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(record.execute(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(f.ledger.find(HotfixFixture.ID).orElseThrow().state())
          .isEqualTo(HotfixState.ROLLED_BACK);
      assertThat(record.compensate(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(record.compensate(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(f.ledger.find(HotfixFixture.ID).orElseThrow().state())
          .isEqualTo(HotfixState.INSTALLED);
    }
  }

  @Test
  void should_round_trip_the_arguments_when_stored_as_json() {
    HotfixPlans.RollbackArgs args = new HotfixPlans.RollbackArgs("JRSHF-x", true);
    assertThat(HotfixPlans.rollbackArgs(HotfixPlans.rollbackArgsJson(args))).isEqualTo(args);
    HotfixPlans.RollbackArgs chained =
        new HotfixPlans.RollbackArgs("JRSHF-x", true, List.of("JRSHF-y", "JRSHF-x"));
    assertThat(HotfixPlans.rollbackArgs(HotfixPlans.rollbackArgsJson(chained))).isEqualTo(chained);
  }

  @Test
  void should_rebuild_the_stored_plan_when_the_ledger_already_says_rolled_back() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      HotfixPlans.ResolvedRollback resolved =
          f.plans.resolveRollback(new HotfixPlans.RollbackArgs(HotfixFixture.ID, false));
      assertThat(resolved.args().chain()).containsExactly(HotfixFixture.ID);
      f.ledger.updateState(HotfixFixture.ID, HotfixState.ROLLED_BACK);

      Plan rebuilt =
          f.plans.rebuild(HotfixPlans.ROLLBACK, HotfixPlans.rollbackArgsJson(resolved.args()));

      assertThat(HotfixFixture.ids(rebuilt)).isEqualTo(HotfixFixture.ids(resolved.plan()));
      assertThat(rebuilt.steps().stream().map(Step::phase).toList())
          .isEqualTo(resolved.plan().steps().stream().map(Step::phase).toList());
      assertThatThrownBy(
              () -> f.plans.planRollback(new HotfixPlans.RollbackArgs(HotfixFixture.ID, false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("not installed");
    }
  }

  @Test
  void should_refuse_a_rebuild_when_a_chained_hotfix_left_the_ledger() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      HotfixPlans.RollbackArgs args =
          new HotfixPlans.RollbackArgs(HotfixFixture.ID, false, List.of(HotfixFixture.ID));
      f.ledger.delete(HotfixFixture.ID);

      assertThatThrownBy(() -> f.plans.planRollback(args))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("is no longer in the ledger");
    }
  }

  @Test
  void should_refuse_before_the_stop_when_the_base_url_does_not_answer() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Plan rb =
          PreflightTest.withProbe(f, "HTTP 404", new AtomicInteger())
              .planRollback(new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false));
      int callsBefore = f.platform.controller.calls().size();
      RunOutcome out = f.run(rb, "r2");
      assertThat(out).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) out).message())
          .contains("does not answer while the service is running: HTTP 404");
      assertThat(
              f.platform
                  .controller
                  .calls()
                  .subList(callsBefore, f.platform.controller.calls().size()))
          .doesNotContain("stop");
    }
  }
}

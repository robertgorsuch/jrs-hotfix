package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.Trees;
import java.nio.file.Files;
import java.nio.file.Path;
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
      Plan rb = f.plans.planRollback();
      assertThat(HotfixFixture.ids(rb))
          .containsExactly(
              "stop-service",
              "restore-snapshot",
              "clear-jsp-cache",
              "start-service",
              "wait-for-server",
              "discard-undo");
      assertThat(rb.planId()).startsWith("hotfix-rollback-");
      assertThat(rb.summary().operation()).isEqualTo("hotfix.rollback");
      assertThat(rb.summary().target()).isEqualTo(HotfixFixture.ID);
      assertThat(rb.fingerprint().inputs())
          .containsEntry("hotfix:" + HotfixFixture.ID, "r1")
          .containsKeys("settings", "file:" + f.target(HotfixFixture.FOO));
      assertThat(f.run(rb, "r2")).isInstanceOf(RunOutcome.Succeeded.class);
      // restored pages are older than what Tomcat compiled from the hotfix's, so it would keep them
      assertThat(cache).doesNotExist();
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("old foo");
      assertThat(f.target(HotfixFixture.NEW)).doesNotExist();
      assertThat(f.target(HotfixFixture.BAR)).exists();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).exists();
      assertThat(Files.readString(f.target(HotfixFixture.TOOL))).isEqualTo("old tool");
      // one level of undo: it is used up
      assertThat(f.undo.read()).isEmpty();
      assertThat(f.home.undo()).doesNotExist();
    }
  }

  @Test
  void should_undo_the_latest_apply_only_and_then_have_nothing_left_to_undo() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.plan(), "r1")).isInstanceOf(RunOutcome.Succeeded.class);
      Path later = Packages.later(tmp.resolve("dl/later.zip"));
      assertThat(f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(later, true)), "r2"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.undo.read().orElseThrow().id()).isEqualTo(Packages.laterId());
      // the first apply's snapshot went when the second replaced the undo
      assertThat(f.snapshots.find("r1", ApplySteps.SNAPSHOT)).isEmpty();

      Plan rb = f.plans.planRollback();
      assertThat(rb.summary().target()).isEqualTo(Packages.laterId());
      assertThat(f.run(rb, "r3")).isInstanceOf(RunOutcome.Succeeded.class);

      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("patched foo");
      assertThat(f.target(HotfixFixture.NEW)).exists();
      assertThatThrownBy(() -> f.plans.planRollback())
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("nothing to undo");
    }
  }

  @Test
  void should_keep_the_previous_undo_when_an_apply_is_compensated() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.plan(), "r1")).isInstanceOf(RunOutcome.Succeeded.class);
      Path later = Packages.later(tmp.resolve("dl/later.zip"));
      Plan second = f.plans.planApply(new HotfixPlans.ApplyArgs(later, true));
      // the service does not stop: the apply undoes what it did before the outage
      f.platform.controller.hangOnStop(100);

      assertThat(f.run(second, "r2")).isInstanceOf(RunOutcome.RolledBack.class);

      assertThat(f.undo.read().orElseThrow().runId()).isEqualTo("r1");
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("patched foo");
    }
  }

  @Test
  void should_refuse_when_there_is_nothing_to_undo() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThatThrownBy(() -> f.plans.planRollback())
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("nothing to undo");
    }
  }

  @Test
  void should_refuse_before_the_stop_and_name_the_file_when_it_changed_since_the_apply()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plan(), "r1");
      Files.writeString(f.target(HotfixFixture.FOO), "edited by hand");
      Plan rb = f.plans.planRollback();
      assertThat(rb.summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .startsWith("this plan will be refused")
                      .contains("1 file(s) changed since " + HotfixFixture.ID)
                      .contains(f.target(HotfixFixture.FOO).toString()));
      int callsBefore = f.platform.controller.calls().size();

      RunOutcome out = f.run(rb, "r2");

      assertThat(out).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) out).message())
          .contains("changed since")
          .contains(f.target(HotfixFixture.FOO).toString());
      assertThat(((RunOutcome.PrecheckFailed) out).remediation())
          .contains(f.home.undo().toString());
      assertThat(f.platform.controller.calls()).hasSize(callsBefore);
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("edited by hand");
      assertThat(f.undo.read()).isPresent();
    }
  }

  @Test
  void should_refuse_when_a_deleted_file_is_back() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plan(), "r1");
      Files.writeString(f.target(HotfixFixture.BAR), "bar again");

      RunOutcome out = f.run(f.plans.planRollback(), "r2");

      assertThat(out).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) out).message())
          .contains(f.target(HotfixFixture.BAR) + " is ");
    }
  }

  @Test
  void should_refuse_when_the_snapshot_was_deleted_by_hand() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Files.delete(f.home.undo().resolve("manifest.json"));
      int callsBefore = f.platform.controller.calls().size();
      RunOutcome out = f.run(f.plans.planRollback(), "r2");
      assertThat(out).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) out).message())
          .contains("snapshot")
          .contains("missing");
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("patched foo");
      // refused before the outage: the service was never stopped
      assertThat(f.platform.controller.calls()).hasSize(callsBefore);
    }
  }

  @Test
  void should_touch_nothing_when_the_restore_finds_the_snapshot_missing() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Plan rb = f.plans.planRollback();
      Trees.deleteRecursively(f.home.undo());
      StepResult result =
          HotfixFixture.step(rb, "restore-snapshot").execute(f.ctx("r2"), EventSink.discard());
      assertThat(result).isNotInstanceOf(StepResult.Ok.class);
      // the file the hotfix added is still there: a missing snapshot deletes nothing
      assertThat(Files.readString(f.target(HotfixFixture.NEW))).isEqualTo("brand new");
    }
  }

  @Test
  void should_converge_when_the_undo_is_discarded_twice_and_put_back_twice() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plan(), "r1");
      Plan rb = f.plans.planRollback();
      Step discard = HotfixFixture.step(rb, "discard-undo");
      assertThat(discard.execute(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(discard.execute(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(f.undo.read()).isEmpty();
      assertThat(f.undo.find("r1")).isPresent();
      assertThat(discard.compensate(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(discard.compensate(f.ctx("r2"), EventSink.discard())).isEqualTo(StepResult.ok());
      assertThat(f.undo.read().orElseThrow().runId()).isEqualTo("r1");
    }
  }

  @Test
  void should_round_trip_the_arguments_and_refuse_those_of_an_earlier_version() {
    HotfixPlans.RollbackArgs args = new HotfixPlans.RollbackArgs("r1");
    assertThat(HotfixPlans.rollbackArgs(HotfixPlans.rollbackArgsJson(args))).isEqualTo(args);
    assertThatThrownBy(
            () ->
                HotfixPlans.rollbackArgs(
                    "{\"hotfixId\":\"JRSHF-x\",\"cascade\":false,\"chain\":[\"JRSHF-x\"]}"))
        .isInstanceOf(HotfixException.class)
        .hasMessageContaining("0.5 or earlier");
  }

  @Test
  void should_rebuild_the_stored_plan_when_the_undo_is_already_used_up() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Plan planned = f.plans.planRollback();
      String args = HotfixPlans.rollbackArgsJson(new HotfixPlans.RollbackArgs("r1"));
      // the run reached its last step before a crash: the undo is in the run, not in undo/
      HotfixFixture.step(planned, "discard-undo").execute(f.ctx("r2"), EventSink.discard());

      Plan rebuilt = f.plans.rebuild(HotfixPlans.ROLLBACK, args);

      assertThat(HotfixFixture.ids(rebuilt)).isEqualTo(HotfixFixture.ids(planned));
      assertThat(rebuilt.fingerprint().inputs()).containsEntry("hotfix:" + HotfixFixture.ID, "r1");
      assertThatThrownBy(() -> f.plans.planRollback())
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("nothing to undo");
    }
  }

  @Test
  void should_refuse_a_rebuild_when_another_apply_replaced_the_undo() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plan(), "r1");
      f.run(
          f.plans.planApply(
              new HotfixPlans.ApplyArgs(Packages.later(tmp.resolve("dl/later.zip")), true)),
          "r2");

      assertThatThrownBy(() -> f.plans.planRollback(new HotfixPlans.RollbackArgs("r1")))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("is gone");
    }
  }

  @Test
  void should_refuse_before_the_stop_when_the_base_url_does_not_answer() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      Plan rb = PreflightTest.withProbe(f, "HTTP 404", new AtomicInteger()).planRollback();
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

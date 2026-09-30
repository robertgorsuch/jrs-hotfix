package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyPlanTest {

  @TempDir Path tmp;

  @Test
  void should_build_nine_steps_in_order_when_planning_a_standard_package() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), false));
      assertThat(HotfixFixture.ids(plan))
          .containsExactly(
              "preflight",
              "snapshot",
              "stage-files",
              "stop-service",
              "atomic-swap",
              "clear-jsp-cache",
              "start-service",
              "wait-for-server",
              "record-installed");
      assertThat(plan.planId()).startsWith("hotfix-apply-");
      assertThat(plan.summary().operation()).isEqualTo("hotfix.apply");
      assertThat(plan.summary().target()).startsWith(HotfixFixture.ID);
      assertThat(plan.summary().warnings()).anySatisfy(w -> assertThat(w).contains("sha256"));
      assertThat(plan.fingerprint().inputs())
          .containsKey("target:webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")
          .containsKeys("package", "settings", "installed");
      assertThat(HotfixFixture.step(plan, "record-installed").rollbackAllOnFailure()).isTrue();
    }
  }

  @Test
  void should_swap_delete_and_record_when_the_plan_runs() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true));
      assertThat(f.run(plan, "r1")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("patched foo");
      assertThat(f.target(HotfixFixture.NEW)).exists();
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
      assertThat(Files.readString(f.target(HotfixFixture.TOOL))).isEqualTo("patched tool");
      LedgerEntry e = f.ledger.find(HotfixFixture.ID).orElseThrow();
      assertThat(e.state()).isEqualTo(HotfixState.INSTALLED);
      assertThat(e.runId()).isEqualTo("r1");
      assertThat(e.snapshotRef()).contains("r1/snapshot");
      assertThat(e.files()).extracting(OwnedFile::action).contains("replace", "add", "delete");
      assertThat(f.home.stagingDir("r1")).doesNotExist();
      assertThat(f.snapshots.find("r1", "snapshot")).isPresent();
      assertThat(f.platform.controller.calls()).containsExactly("stop", "start");
    }
  }

  /** Tomcat's compiled JSPs for the fixture's webapp, with one class in them. */
  static Path jspCache(HotfixFixture f) throws Exception {
    Path cache = f.settings.tomcatDir().resolve("work/Catalina/localhost/jasperserver-pro");
    Files.createDirectories(cache.resolve("org/apache/jsp"));
    Files.writeString(cache.resolve("org/apache/jsp/login_jsp.class"), "compiled");
    return cache;
  }

  @Test
  void should_remove_the_jsp_cache_while_the_service_is_down_when_the_plan_runs() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path cache = jspCache(f);
      Path other = f.settings.tomcatDir().resolve("work/Catalina/localhost/other-app/x.class");
      Files.createDirectories(other.getParent());
      Files.writeString(other, "another webapp's");
      assertThat(f.run(f.plan(), "r1")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(cache).doesNotExist();
      assertThat(other).exists();
    }
  }

  @Test
  void should_succeed_when_there_is_no_jsp_cache_to_remove() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.settings.tomcatDir().resolve("work")).doesNotExist();
      assertThat(f.run(f.plan(), "r1")).isInstanceOf(RunOutcome.Succeeded.class);
    }
  }

  @Test
  void should_refuse_with_precheck_when_the_same_package_is_applied_twice() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      RunOutcome second =
          f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r2");
      assertThat(second).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) second).message()).contains("already installed");
    }
  }

  @Test
  void should_restore_the_snapshot_when_the_swap_fails() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.platform.failAtomicReplaceFor(f.target(HotfixFixture.NEW));
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true));
      assertThat(f.run(plan, "r1")).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("old foo");
      assertThat(f.target(HotfixFixture.BAR)).exists();
      assertThat(f.target(HotfixFixture.NEW)).doesNotExist();
      assertThat(Files.readString(f.target(HotfixFixture.TOOL))).isEqualTo("old tool");
      assertThat(f.ledger.find(HotfixFixture.ID)).isEmpty();
      assertThat(f.home.stagingDir("r1")).doesNotExist();
      assertThat(f.platform.controller.calls()).containsExactly("stop", "start");
    }
  }

  @Test
  void should_refuse_a_file_that_is_not_an_official_package() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path bogus = tmp.resolve("bogus.zip");
      Files.writeString(bogus, "not a zip");
      assertThatThrownBy(() -> f.plans.planApply(new HotfixPlans.ApplyArgs(bogus, false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("not an official");
      assertThatThrownBy(
              () -> f.plans.planApply(new HotfixPlans.ApplyArgs(tmp.resolve("absent.zip"), false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("does not exist");
    }
  }

  @Test
  void should_round_trip_the_apply_arguments_through_json() {
    HotfixPlans.ApplyArgs args = new HotfixPlans.ApplyArgs(tmp.resolve("hf.zip"), true);
    assertThat(HotfixPlans.applyArgs(HotfixPlans.applyArgsJson(args))).isEqualTo(args);
  }
}

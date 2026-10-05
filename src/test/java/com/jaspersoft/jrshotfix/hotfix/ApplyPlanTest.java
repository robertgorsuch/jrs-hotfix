package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import com.jaspersoft.jrshotfix.state.UndoRecord;
import com.jaspersoft.jrshotfix.text.PropertiesMerge;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
              "promote-undo");
      assertThat(plan.planId()).startsWith("hotfix-apply-");
      assertThat(plan.summary().operation()).isEqualTo("hotfix.apply");
      assertThat(plan.summary().target()).startsWith(HotfixFixture.ID);
      assertThat(plan.summary().warnings()).anySatisfy(w -> assertThat(w).contains("sha256"));
      assertThat(plan.fingerprint().inputs())
          .containsKey("target:webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")
          .containsKeys("package", "settings", "installed");
      assertThat(HotfixFixture.step(plan, "promote-undo").rollbackAllOnFailure()).isTrue();
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
      UndoRecord e = f.undo.read().orElseThrow();
      assertThat(e.id()).isEqualTo(HotfixFixture.ID);
      assertThat(e.runId()).isEqualTo("r1");
      assertThat(e.files()).extracting(OwnedFile::action).contains("replace", "add", "delete");
      assertThat(f.home.stagingDir("r1")).doesNotExist();
      // the run's snapshot became the undo
      assertThat(f.home.runDir("r1").resolve("snapshot")).doesNotExist();
      assertThat(f.snapshots.find("r1", "snapshot").orElseThrow().dir()).isEqualTo(f.home.undo());
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

  static final String QUARTZ = "webapps/jasperserver-pro/WEB-INF/js.quartz.properties";
  static final String QUARTZ_MINE = "a=1\nuri=http://reports:8081/x\nmail.host=smtp\n";
  static final String QUARTZ_THEIRS = "# scheduler\na=1\nuri=http://localhost:8080/x\nfresh=true\n";
  static final String QUARTZ_MERGED =
      "# scheduler\na=1\nuri=http://reports:8081/x\nfresh=true\n\n"
          + PropertiesMerge.CARRIED_HEADING
          + "\nmail.host=smtp\n";

  /** The fixture's server with a scheduler file of its own, and a package that ships another. */
  static Path quartzPackage(HotfixFixture f) throws Exception {
    Files.writeString(f.target(QUARTZ), QUARTZ_MINE);
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(Packages.LIB + "foo-1.2.3.jar", "patched foo");
    payload.put("WEB-INF/js.quartz.properties", QUARTZ_THEIRS);
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(payload, null));
    return Packages.zip(f.root.resolve("dl/quartz.zip"), outer);
  }

  @Test
  void should_keep_this_servers_values_in_an_installer_written_file_when_the_plan_runs()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(quartzPackage(f), true));
      assertThat(f.run(plan, "r1")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(Files.readString(f.target(QUARTZ))).isEqualTo(QUARTZ_MERGED);
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("patched foo");
      OwnedFile owned =
          f.undo.read().orElseThrow().files().stream()
              .filter(o -> o.path().equals(f.target(QUARTZ)))
              .findFirst()
              .orElseThrow();
      assertThat(owned.afterSha256()).contains(f.sha(f.target(QUARTZ)));
    }
  }

  @Test
  void should_put_this_servers_file_back_as_it_was_when_a_merged_hotfix_is_rolled_back()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(quartzPackage(f), true)), "r1");
      Plan rb = f.plans.planRollback();
      assertThat(f.run(rb, "r2")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(Files.readString(f.target(QUARTZ))).isEqualTo(QUARTZ_MINE);
    }
  }

  @Test
  void should_plan_the_same_file_again_when_the_plan_is_rebuilt_after_the_swap() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path pkg = quartzPackage(f);
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(pkg, true));
      Context ctx = f.ctx("r1");
      for (String id : List.of("preflight", "snapshot", "stage-files", "stop-service")) {
        assertThat(HotfixFixture.step(plan, id).execute(ctx, EventSink.discard()))
            .isInstanceOf(StepResult.Ok.class);
      }
      assertThat(HotfixFixture.step(plan, "atomic-swap").execute(ctx, EventSink.discard()))
          .isInstanceOf(StepResult.Ok.class);
      // what recovery does after a crash here: the plan is built again, from the files as they are
      Plan rebuilt = f.plans.planApply(new HotfixPlans.ApplyArgs(pkg, true));
      assertThat(HotfixFixture.step(rebuilt, "atomic-swap").postcheck(ctx))
          .isNotInstanceOf(CheckResult.Fail.class);
      assertThat(HotfixFixture.step(rebuilt, "atomic-swap").execute(ctx, EventSink.discard()))
          .isInstanceOf(StepResult.Ok.class);
      assertThat(Files.readString(f.target(QUARTZ))).isEqualTo(QUARTZ_MERGED);
    }
  }

  @Test
  void should_refuse_to_stage_when_the_servers_file_changed_after_the_plan() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(quartzPackage(f), true));
      Files.writeString(f.target(QUARTZ), QUARTZ_MINE + "later=edit\n");
      StepResult staged =
          HotfixFixture.step(plan, "stage-files").execute(f.ctx("r1"), EventSink.discard());
      assertThat(staged).isInstanceOf(StepResult.Failed.class);
      assertThat(((StepResult.Failed) staged).failure().cause())
          .contains("js.quartz.properties")
          .contains("changed since");
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
      // the fixture's webapp states no build: every file in place is what says so
      assertThat(((RunOutcome.PrecheckFailed) second).message())
          .contains("is already on this server");
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
      assertThat(f.undo.read()).isEmpty();
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
    HotfixPlans.ApplyArgs war =
        args.intoWar(tmp.resolve("in.war"), tmp.resolve("out.war"))
            .asGeneric()
            .withInstallOut(tmp.resolve("buildomatic-tree"));
    assertThat(HotfixPlans.applyArgs(HotfixPlans.applyArgsJson(war))).isEqualTo(war);
    HotfixPlans.ApplyArgs older = args.keepingSuperseded().allowingOlder();
    assertThat(HotfixPlans.applyArgs(HotfixPlans.applyArgsJson(older))).isEqualTo(older);
    // arguments stored before 0.8 have no allowOlder: they never had the leave
    assertThat(HotfixPlans.applyArgsJson(args)).doesNotContain("allowOlder");
    assertThatThrownBy(() -> args.withInstallOut(tmp))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--install-out needs --war");
  }
}

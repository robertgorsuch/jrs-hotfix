package com.jaspersoft.jrshotfix.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.hotfix.HotfixFixture;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.json.Json;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunPlansTest {

  @TempDir Path tmp;

  @Test
  void should_round_trip_the_plan_record_when_stored_and_loaded() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      HotfixPlans.ApplyArgs args = new HotfixPlans.ApplyArgs(f.packageFile(), true);
      Plan plan = f.plans.planApply(args);
      String argsJson = HotfixPlans.applyArgsJson(args);
      RunPlans store = new RunPlans(f.home);

      store.store("r1", plan, HotfixPlans.APPLY, argsJson);
      RunPlans.Stored s = store.load("r1").orElseThrow();

      assertThat(f.home.runDir("r1").resolve("plan.json")).isRegularFile();
      assertThat(s.planId()).isEqualTo(plan.planId());
      assertThat(s.operation()).isEqualTo(HotfixPlans.APPLY);
      assertThat(Json.mapper().readTree(s.argsJson())).isEqualTo(Json.mapper().readTree(argsJson));
      assertThat(HotfixPlans.applyArgs(s.argsJson())).isEqualTo(args);
      assertThat(s.fingerprint()).isEqualTo(plan.fingerprint().value());
      assertThat(s.stepIds()).isEqualTo(plan.steps().stream().map(Step::id).toList());
    }
  }

  @Test
  void should_be_empty_when_the_run_is_unknown() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(new RunPlans(f.home).load("nope")).isEmpty();
    }
  }

  @Test
  void should_rebuild_the_same_fingerprint_when_rebuilding_from_the_stored_args() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      HotfixPlans.ApplyArgs args = new HotfixPlans.ApplyArgs(f.packageFile(), true);
      Plan plan = f.plans.planApply(args);
      RunPlans store = new RunPlans(f.home);
      store.store("r1", plan, HotfixPlans.APPLY, HotfixPlans.applyArgsJson(args));
      RunPlans.Stored s = store.load("r1").orElseThrow();

      Plan rebuilt = f.plans.rebuild(s.operation(), s.argsJson());

      assertThat(rebuilt.fingerprint().value()).isEqualTo(s.fingerprint());
      assertThat(rebuilt.steps().stream().map(Step::id).toList()).isEqualTo(s.stepIds());
    }
  }

  @Test
  void should_refuse_an_unknown_operation_when_rebuilding() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThatThrownBy(() -> f.plans.rebuild("hotfix.nothing", "{}"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("hotfix.nothing");
    }
  }
}

package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.state.HotfixState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two hotfixes owning the same file: the earlier one comes off only after the later one, and only
 * when the operator asks for the cascade.
 */
class RollbackChainTest {

  private static final String FIRST = HotfixFixture.ID;

  @TempDir Path tmp;

  private static HotfixFixture twoApplied(Path tmp) throws Exception {
    HotfixFixture f = HotfixFixture.create(tmp);
    assertThat(f.run(f.plan(), "r1")).isInstanceOf(RunOutcome.Succeeded.class);
    Path later = Packages.later(tmp.resolve("dl/later.zip"));
    assertThat(f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(later, true)), "r2"))
        .isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("later foo");
    return f;
  }

  @Test
  void should_refuse_naming_the_later_hotfix_when_not_cascading() throws Exception {
    try (HotfixFixture f = twoApplied(tmp)) {
      assertThatThrownBy(() -> f.plans.planRollback(new HotfixPlans.RollbackArgs(FIRST, false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining(Packages.laterId())
          .satisfies(e -> assertThat(((HotfixException) e).remediation()).contains("--cascade"));
    }
  }

  @Test
  void should_roll_back_the_later_hotfix_first_when_cascading() throws Exception {
    try (HotfixFixture f = twoApplied(tmp)) {
      String later = Packages.laterId();
      Plan plan = f.plans.planRollback(new HotfixPlans.RollbackArgs(FIRST, true));
      assertThat(plan.steps().stream().map(Step::phase).distinct())
          .containsExactly("rollback:" + later, "rollback:" + FIRST);
      assertThat(HotfixFixture.ids(plan))
          .containsExactly(
              "stop-service:" + later,
              "restore-snapshot:" + later,
              "start-service:" + later,
              "wait-for-server:" + later,
              "record-rolled-back:" + later,
              "stop-service:" + FIRST,
              "restore-snapshot:" + FIRST,
              "start-service:" + FIRST,
              "wait-for-server:" + FIRST,
              "record-rolled-back:" + FIRST);
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains(later).contains("--cascade"));

      assertThat(f.run(plan, "r3")).isInstanceOf(RunOutcome.Succeeded.class);

      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("old foo");
      assertThat(f.target(HotfixFixture.NEW)).doesNotExist();
      assertThat(f.ledger.find(FIRST).orElseThrow().state()).isEqualTo(HotfixState.ROLLED_BACK);
      assertThat(f.ledger.find(later).orElseThrow().state()).isEqualTo(HotfixState.ROLLED_BACK);
    }
  }

  @Test
  void should_roll_back_the_later_hotfix_alone_when_nothing_newer_owns_its_files()
      throws Exception {
    try (HotfixFixture f = twoApplied(tmp)) {
      Plan plan = f.plans.planRollback(new HotfixPlans.RollbackArgs(Packages.laterId(), false));
      assertThat(HotfixFixture.ids(plan)).hasSize(5).contains("restore-snapshot");

      assertThat(f.run(plan, "r3")).isInstanceOf(RunOutcome.Succeeded.class);

      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("patched foo");
      assertThat(f.ledger.find(FIRST).orElseThrow().state()).isEqualTo(HotfixState.INSTALLED);
      assertThat(f.ledger.find(Packages.laterId()).orElseThrow().state())
          .isEqualTo(HotfixState.ROLLED_BACK);
    }
  }
}

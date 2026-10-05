package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import com.jaspersoft.jrshotfix.service.ServerProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightTest {

  @TempDir Path tmp;

  private static String failure(CheckResult r) {
    assertThat(r).isInstanceOf(CheckResult.Fail.class);
    return ((CheckResult.Fail) r).message();
  }

  @Test
  void should_pass_when_the_installation_matches_the_package() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      assertThat(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r")))
          .isNotInstanceOf(CheckResult.Fail.class);
    }
  }

  /** What an operator following the readme leaves behind: every file in place. */
  static void applyByHand(HotfixFixture f) throws Exception {
    Files.writeString(f.target(HotfixFixture.FOO), "patched foo");
    Files.writeString(f.target(HotfixFixture.NEW), "brand new");
    Files.writeString(f.target(HotfixFixture.TOOL), "patched tool");
    Files.delete(f.target(HotfixFixture.BAR));
    Files.delete(f.target(HotfixFixture.FOO_OLDER));
  }

  @Test
  void should_refuse_when_every_file_is_in_place_and_the_webapp_states_no_build() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      applyByHand(f);
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains(HotfixFixture.ID + " is already on this server")
          .doesNotContain("record");
    }
  }

  private static final String STAMPS = "WEB-INF/internal/jasperserver-pro.properties";

  /** Writes the file the webapp states its build in. */
  static void stateBuild(HotfixFixture f, String date, String time) throws Exception {
    Path file = f.settings.webappDir().resolve(STAMPS);
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "PRO_VERSION=10.0.0\n  BUILD_DATE_STAMP=" + date + "\n  BUILD_TIME_STAMP=" + time + "\n");
  }

  private static String warning(CheckResult r) {
    assertThat(r).isInstanceOf(CheckResult.Warn.class);
    return ((CheckResult.Warn) r).message();
  }

  @Test
  void should_refuse_when_the_webapp_states_the_packages_build() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      // one file of the package differs, so the files alone would not say "already applied"
      stateBuild(f, "20260730", "0457");
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains(HotfixFixture.ID + " is already installed")
          .contains("build 20260730_0457")
          .doesNotContain("record");
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("will be refused").contains("already installed"));
    }
  }

  @Test
  void should_refuse_a_package_older_than_the_build_the_webapp_states() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      stateBuild(f, "20260801", "0000");
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("the webapp states build 20260801_0000, newer than this package's")
          .contains("20260730_0457")
          .contains("take the server back");
    }
  }

  @Test
  void should_warn_instead_of_refusing_an_older_package_when_the_operator_allowed_it()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      stateBuild(f, "20260801", "0000");
      assertThat(HotfixPlans.refusedOnlyAsOlder(f.plan())).isTrue();
      Plan allowed =
          f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true).allowingOlder());
      assertThat(HotfixPlans.refusedOnlyAsOlder(allowed)).isFalse();
      assertThat(allowed.summary().warnings()).noneMatch(w -> w.contains("will be refused"));
      assertThat(warning(HotfixFixture.step(allowed, "preflight").precheck(f.ctx("r"))))
          .contains("newer than this package's 20260730_0457")
          .contains("takes the server back, as allowed");
    }
  }

  @Test
  void should_refuse_the_packages_own_build_even_when_older_is_allowed() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      stateBuild(f, "20260730", "0457");
      Plan plan =
          f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true).allowingOlder());
      assertThat(HotfixPlans.refusedOnlyAsOlder(plan)).isFalse();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("is already installed");
    }
  }

  @Test
  void should_pass_without_a_word_when_the_webapp_states_an_older_build() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      // the release, a hotfix applied by hand, a redeploy: whoever left it, it is older
      stateBuild(f, "20260121", "2317");
      Plan plan = f.plan();
      assertThat(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r")))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_warn_once_when_the_webapp_states_no_build() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      assertThat(warning(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("states no build");
      assertThat(plan.summary().warnings())
          .filteredOn(w -> w.contains("states no build"))
          .hasSize(1);
    }
  }

  @Test
  void should_pass_when_only_a_deletion_is_left_to_do() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      applyByHand(f);
      Files.writeString(f.target(HotfixFixture.BAR), "bar");
      Plan plan = f.plan();
      assertThat(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r")))
          .isNotInstanceOf(CheckResult.Fail.class);
    }
  }

  @Test
  void should_refuse_when_the_webapp_is_another_release() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path lib = f.settings.webappDir().resolve("WEB-INF/lib");
      Files.move(
          lib.resolve("jasperserver-api-10.0.0.jar"), lib.resolve("jasperserver-api-9.0.0.jar"));
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("release")
          .contains("9.0.0");
    }
  }

  @Test
  void should_refuse_when_there_is_not_enough_space() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.platform.freeSpace = 1;
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("space");
    }
  }

  @Test
  void should_refuse_when_the_webapp_is_not_writable() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.platform.unwritable.add(f.settings.webappDir());
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains(f.settings.webappDir() + " is not writable");
    }
  }

  @Test
  void should_refuse_the_swap_when_a_target_is_still_locked() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      Context ctx = f.ctx("r-locked");
      for (String id : List.of("preflight", "snapshot", "stage-files", "stop-service")) {
        HotfixFixture.step(plan, id).execute(ctx, EventSink.discard());
      }
      f.platform.locked.add(f.target(HotfixFixture.FOO));
      assertThat(failure(HotfixFixture.step(plan, "atomic-swap").precheck(ctx)))
          .contains("still locked")
          .contains("foo-1.2.3.jar");
    }
  }

  /** The fixture's runtime with a probe that reports {@code problem} and counts its calls. */
  static HotfixPlans withProbe(HotfixFixture f, String problem, AtomicInteger calls) {
    return new HotfixPlans(
        new HotfixRuntime(
            f.home,
            f.settings,
            f.platform,
            f.undo,
            f.snapshots,
            Clock.systemUTC(),
            Sleeper.none(),
            () -> {
              calls.incrementAndGet();
              return Optional.of(problem);
            }));
  }

  @Test
  void should_let_the_base_url_probe_wait_for_a_slow_answer_while_the_service_runs()
      throws Exception {
    // a real JRS 10.0.0 took over ten seconds to answer one serverInfo request (2026-09-29) and a
    // five-second probe refused the rollback of a healthy server
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      List<Duration> patience = new ArrayList<>();
      ServerProbe recording =
          new ServerProbe() {
            @Override
            public Optional<String> problem() {
              throw new AssertionError("preflight must say how long its request may take");
            }

            @Override
            public Optional<String> problem(Duration p) {
              patience.add(p);
              return Optional.empty();
            }
          };
      HotfixPlans plans =
          new HotfixPlans(
              new HotfixRuntime(
                  f.home,
                  f.settings,
                  f.platform,
                  f.undo,
                  f.snapshots,
                  Clock.systemUTC(),
                  Sleeper.none(),
                  recording));
      Plan plan = plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true));
      assertThat(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r")))
          .isNotInstanceOf(CheckResult.Fail.class);
      assertThat(patience)
          .singleElement()
          .satisfies(p -> assertThat(p.toSeconds()).isGreaterThanOrEqualTo(30));
    }
  }

  @Test
  void should_refuse_when_the_base_url_does_not_answer_while_the_service_runs() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      AtomicInteger calls = new AtomicInteger();
      Plan plan =
          withProbe(f, "connection refused", calls)
              .planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true));
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("baseUrl " + f.settings.baseUrl())
          .contains("does not answer while the service is running: connection refused")
          .contains("jrs-hotfix settings set baseUrl");
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void should_not_consult_the_base_url_when_the_service_is_stopped() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.platform.controller.state(ServiceController.State.STOPPED);
      AtomicInteger calls = new AtomicInteger();
      Plan plan =
          withProbe(f, "connection refused", calls)
              .planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true));
      assertThat(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r")))
          .isNotInstanceOf(CheckResult.Fail.class);
      assertThat(calls).hasValue(0);
    }
  }
}

package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
            f.ledger,
            f.snapshots,
            Clock.systemUTC(),
            Sleeper.none(),
            () -> {
              calls.incrementAndGet();
              return Optional.of(problem);
            }));
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

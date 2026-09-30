package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import com.jaspersoft.jrshotfix.service.ServerProbe;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.Origin;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

  /** What an operator following the readme leaves behind: every file in place, no ledger entry. */
  static void applyByHand(HotfixFixture f) throws Exception {
    Files.writeString(f.target(HotfixFixture.FOO), "patched foo");
    Files.writeString(f.target(HotfixFixture.NEW), "brand new");
    Files.writeString(f.target(HotfixFixture.TOOL), "patched tool");
    Files.delete(f.target(HotfixFixture.BAR));
    Files.delete(f.target(HotfixFixture.FOO_OLDER));
  }

  @Test
  void should_refuse_and_name_record_when_the_hotfix_was_applied_by_hand() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      applyByHand(f);
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains(HotfixFixture.ID + " is already on this server")
          .contains("jrs-hotfix record");
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

  /** A ledger entry for a hotfix of {@code build} that shipped the build file. */
  static void installed(HotfixFixture f, String id, String build) {
    f.ledger.recordInstalled(
        new LedgerEntry(
            id,
            "10.0.0",
            "PRO",
            build,
            "an earlier hotfix",
            HotfixState.INSTALLED,
            Origin.RECORDED,
            HotfixPlans.RECORDED_RUN_ID,
            Optional.empty(),
            Instant.parse("2026-08-01T00:00:00Z"),
            List.of(
                new OwnedFile(
                    f.settings.webappDir().resolve(STAMPS),
                    "replace",
                    Optional.of("a"),
                    Optional.of("b")))));
  }

  private static String warning(CheckResult r) {
    assertThat(r).isInstanceOf(CheckResult.Warn.class);
    return ((CheckResult.Warn) r).message();
  }

  @Test
  void should_refuse_and_name_record_when_the_webapp_states_the_packages_build() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      // one file of the package differs, so the files alone would not say "already applied"
      stateBuild(f, "20260730", "0457");
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains(HotfixFixture.ID + " is already on this server")
          .contains("build 20260730_0457")
          .contains("jrs-hotfix record");
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("will be refused").contains("jrs-hotfix record"));
    }
  }

  @Test
  void should_pass_when_the_webapp_states_the_build_of_the_newest_entry() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      stateBuild(f, "20260601", "1200");
      installed(f, "JRSHF-10.0.0-20260601-1200", "20260601_1200");
      Plan plan = f.plan();
      assertThat(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r")))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_refuse_when_the_webapp_is_older_than_the_ledger_says() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      stateBuild(f, "20260121", "2317");
      installed(f, "JRSHF-10.0.0-20260601-1200", "20260601_1200");
      Plan plan = f.plan();
      assertThat(failure(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("build 20260121_2317")
          .contains("older than JRSHF-10.0.0-20260601-1200")
          .contains("replaced under the ledger");
    }
  }

  @Test
  void should_warn_and_go_on_when_a_hotfix_was_applied_outside_the_tool() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      stateBuild(f, "20260615", "0900");
      installed(f, "JRSHF-10.0.0-20260601-1200", "20260601_1200");
      Plan plan = f.plan();
      assertThat(warning(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r"))))
          .contains("build 20260615_0900 was applied outside jrs-hotfix");
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("applied outside jrs-hotfix"));
    }
  }

  @Test
  void should_say_nothing_about_the_build_when_the_ledger_is_empty() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      // without an entry or a baseline, a release's own build cannot be told from a hotfix's
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
  void should_not_compare_with_an_entry_whose_hotfix_shipped_no_build_file() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      stateBuild(f, "20260121", "2317");
      f.ledger.recordInstalled(
          new LedgerEntry(
              "JRSHF-10.0.0-20260601-1200",
              "10.0.0",
              "PRO",
              "20260601_1200",
              "a hotfix of one jar",
              HotfixState.INSTALLED,
              Origin.RECORDED,
              HotfixPlans.RECORDED_RUN_ID,
              Optional.empty(),
              Instant.parse("2026-08-01T00:00:00Z"),
              List.of()));
      Plan plan = f.plan();
      assertThat(HotfixFixture.step(plan, "preflight").precheck(f.ctx("r")))
          .isNotInstanceOf(CheckResult.Fail.class);
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
                  f.ledger,
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

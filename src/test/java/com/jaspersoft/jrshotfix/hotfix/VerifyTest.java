package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.pkg.Packages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifying a package against this installation without changing anything. */
class VerifyTest {

  @TempDir Path tmp;

  @Test
  void should_be_ok_and_list_the_changes_when_the_package_fits() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      HotfixPlans.VerifyReport r = f.plans.verify(f.packageFile());
      assertThat(r.ok()).isTrue();
      assertThat(r.id()).isEqualTo(HotfixFixture.ID);
      assertThat(r.adds()).hasSize(1);
      assertThat(r.replaces()).hasSize(2);
      assertThat(r.deletes()).hasSize(2);
      assertThat(r.notes()).isNotEmpty();
      assertThat(f.ledger.all()).isEmpty();
      assertThat(f.home.runs()).doesNotExist();
    }
  }

  @Test
  void should_report_already_installed_when_the_package_was_applied() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.plan(), "r1")).isInstanceOf(RunOutcome.Succeeded.class);
      HotfixPlans.VerifyReport r = f.plans.verify(f.packageFile());
      assertThat(r.readable()).isTrue();
      assertThat(r.applicable()).isFalse();
      assertThat(r.ok()).isFalse();
      // said once: the ledger knows it, so the files being in place is no second finding
      assertThat(r.problems())
          .singleElement()
          .satisfies(p -> assertThat(p).contains("already installed"));
    }
  }

  @Test
  void should_name_record_when_the_hotfix_was_applied_by_hand() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      PreflightTest.applyByHand(f);
      HotfixPlans.VerifyReport r = f.plans.verify(f.packageFile());
      assertThat(r.readable()).isTrue();
      assertThat(r.applicable()).isFalse();
      assertThat(r.problems())
          .singleElement()
          .satisfies(
              p ->
                  assertThat(p)
                      .contains("is already on this server")
                      .contains("jrs-hotfix record"));
    }
  }

  @Test
  void should_report_the_release_when_the_webapp_is_another_release() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path lib = f.settings.webappDir().resolve("WEB-INF/lib");
      Files.move(
          lib.resolve("jasperserver-api-10.0.0.jar"), lib.resolve("jasperserver-api-9.0.0.jar"));
      HotfixPlans.VerifyReport r = f.plans.verify(f.packageFile());
      assertThat(r.applicable()).isFalse();
      assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("release").contains("9.0.0"));
    }
  }

  @Test
  void should_report_unreadable_without_throwing_when_the_zip_is_not_a_package() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip =
          Packages.zip(
              tmp.resolve("dl/other.zip"),
              Map.of("notes.txt", "hello".getBytes(StandardCharsets.UTF_8)));
      HotfixPlans.VerifyReport r = f.plans.verify(zip);
      assertThat(r.readable()).isFalse();
      assertThat(r.ok()).isFalse();
      assertThat(r.problems())
          .anySatisfy(p -> assertThat(p).contains("is not an official Jaspersoft hotfix package"));
    }
  }
}

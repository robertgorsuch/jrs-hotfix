package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.Origin;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Recording a hotfix applied by hand: a ledger entry, and nothing on the server touched. */
class RecordTest {

  @TempDir Path tmp;

  @Test
  void should_write_a_recorded_entry_with_the_package_files_when_recording() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      LedgerEntry e = f.plans.record(f.packageFile());
      assertThat(e.origin()).isEqualTo(Origin.RECORDED);
      assertThat(e.id()).isEqualTo("JRSHF-10.0.0-20260730-0457");
      assertThat(e.runId()).isEqualTo(HotfixPlans.RECORDED_RUN_ID);
      assertThat(e.snapshotRef()).isEmpty();
      assertThat(e.files())
          .extracting(OwnedFile::path)
          .contains(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"));
      assertThat(f.ledger.installed()).hasSize(1);
      // the server is not touched
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo("old foo");
      assertThat(f.target(HotfixFixture.NEW)).doesNotExist();
      assertThatThrownBy(() -> f.plans.record(f.packageFile()))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("already");
    }
  }

  @Test
  void should_refuse_a_missing_file_when_recording() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThatThrownBy(() -> f.plans.record(tmp.resolve("dl/missing.zip")))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("does not exist");
      assertThat(f.ledger.all()).isEmpty();
    }
  }

  @Test
  void should_block_a_later_rollback_that_overlaps_a_recorded_entry() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      // install a package, then record a hand-applied later package owning the same jar
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      f.plans.record(Packages.later(tmp.resolve("dl/later.zip")));
      assertThatThrownBy(
              () ->
                  f.plans.planRollback(
                      new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false)))
          .hasMessageContaining("JRSHF-10.0.0-20260830-0100");
    }
  }

  @Test
  void should_list_every_entry_in_install_order() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
      f.plans.record(Packages.later(tmp.resolve("dl/later.zip")));
      assertThat(f.plans.list())
          .extracting(LedgerEntry::id)
          .containsExactly(HotfixFixture.ID, Packages.laterId());
    }
  }
}

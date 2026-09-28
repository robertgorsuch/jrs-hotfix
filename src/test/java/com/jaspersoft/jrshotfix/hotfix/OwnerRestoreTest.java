package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Plan;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #157: a swap run by an account that may not give the replaced files back to their owner
 * left them owned by that account, and the run still succeeded. Preflight now refuses before
 * anything changes, naming the owner and what to run as.
 */
class OwnerRestoreTest {

  @TempDir Path tmp;

  @Test
  void should_refuse_preflight_when_the_replaced_files_owner_cannot_be_restored()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();
      f.platform.ownerRestorable = false;

      CheckResult preflight = HotfixFixture.step(plan, "preflight").precheck(f.ctx("r-owner"));

      assertThat(preflight).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) preflight).message())
          .contains("cannot give")
          .contains("back to their owner")
          .contains("elevated")
          .doesNotContain("jrsctl");
    }
  }

  @Test
  void should_pass_preflight_when_the_owner_can_be_restored() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.plan();

      CheckResult preflight = HotfixFixture.step(plan, "preflight").precheck(f.ctx("r-owner-ok"));

      assertThat(preflight).isNotInstanceOf(CheckResult.Fail.class);
    }
  }
}

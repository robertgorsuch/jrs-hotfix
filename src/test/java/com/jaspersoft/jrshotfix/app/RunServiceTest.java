package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.TerminalState;
import com.jaspersoft.jrshotfix.hotfix.HotfixFixture;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.FileJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Retention: {@link RunService#prune} and what it must never remove. */
class RunServiceTest {

  @TempDir Path tmp;

  @Test
  void should_remove_ended_old_runs_and_keep_new_and_pending_ones() throws Exception {
    HotfixFixture hf = HotfixFixture.create(tmp);
    Instant now = Instant.parse("2026-09-28T12:00:00Z");
    Instant old = now.minus(Duration.ofDays(60));
    FileJournal journal = new FileJournal(hf.home, Clock.fixed(old, ZoneOffset.UTC));
    for (String run : List.of("r-old", "r-older")) {
      journal.recordRunStart(run, "hotfix.apply", Optional.empty(), old);
      journal.recordRunEnd(run, old, TerminalState.SUCCEEDED, 0);
    }
    journal.recordRunStart("r-new", "hotfix.apply", Optional.empty(), now);
    journal.recordRunEnd("r-new", now, TerminalState.SUCCEEDED, 0);
    journal.recordRunStart("r-pending", "hotfix.apply", Optional.empty(), old);

    Bootstrap boot = boot(hf, Clock.fixed(now, ZoneOffset.UTC));
    RunService.PruneResult result = new RunService(boot).prune(Duration.ofDays(30), false);

    assertThat(result.runsRemoved()).containsExactlyInAnyOrder("r-old", "r-older");
    assertThat(hf.home.runDir("r-old")).doesNotExist();
    assertThat(hf.home.runDir("r-new")).exists();
    assertThat(hf.home.runDir("r-pending")).exists();
  }

  @Test
  void should_keep_a_failed_run_and_its_snapshot_unless_failed_runs_are_included()
      throws Exception {
    HotfixFixture hf = HotfixFixture.create(tmp);
    Instant now = Instant.parse("2026-09-28T12:00:00Z");
    Instant old = now.minus(Duration.ofDays(60));
    Clock oldClock = Clock.fixed(old, ZoneOffset.UTC);
    FileJournal journal = new FileJournal(hf.home, oldClock);
    // exit 4: the rollback did not complete and the message pointed at this snapshot
    journal.recordRunStart("r-failed", "hotfix.apply", Optional.empty(), old);
    journal.recordRunEnd("r-failed", old, TerminalState.FAILED, 4);
    new SnapshotStore(hf.home, hf.platform.files(), oldClock)
        .create(
            "r-failed", "snapshot", List.of(hf.target(HotfixFixture.FOO)), hf.paths.installDir());
    Bootstrap boot = boot(hf, Clock.fixed(now, ZoneOffset.UTC));

    RunService.PruneResult kept = new RunService(boot).prune(Duration.ofDays(30), false);
    assertThat(kept.runsRemoved()).isEmpty();
    assertThat(hf.snapshots.find("r-failed", "snapshot")).isPresent();

    RunService.PruneResult removed = new RunService(boot).prune(Duration.ofDays(30), true);
    assertThat(removed.runsRemoved()).containsExactly("r-failed");
    assertThat(hf.snapshots.find("r-failed", "snapshot")).isEmpty();
    assertThat(Files.exists(hf.home.runDir("r-failed"))).isFalse();
  }

  static Bootstrap boot(HotfixFixture hf, Clock clock) {
    GlobalOptions g = new GlobalOptions();
    g.home = hf.home.root();
    g.nonInteractive = true;
    return Bootstrap.open(
        g,
        Map.of("XDG_CONFIG_HOME", hf.root.resolve("config").toString()),
        clock,
        prompt -> hf.platform);
  }
}

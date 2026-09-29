package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.TerminalState;
import com.jaspersoft.jrshotfix.hotfix.HotfixFixture;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.FileJournal;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.Origin;
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
  void should_remove_an_ended_old_run_and_keep_the_snapshot_of_an_installed_hotfix()
      throws Exception {
    HotfixFixture hf = HotfixFixture.create(tmp);
    Instant now = Instant.parse("2026-09-28T12:00:00Z");
    Instant old = now.minus(Duration.ofDays(60));
    Clock oldClock = Clock.fixed(old, ZoneOffset.UTC);
    FileJournal journal = new FileJournal(hf.home, oldClock);
    for (String run : List.of("r-old", "r-inst")) {
      journal.recordRunStart(run, "hotfix.apply", Optional.empty(), old);
      journal.recordRunEnd(run, old, TerminalState.SUCCEEDED, 0);
    }
    journal.recordRunStart("r-new", "hotfix.apply", Optional.empty(), now);
    journal.recordRunEnd("r-new", now, TerminalState.SUCCEEDED, 0);
    journal.recordRunStart("r-pending", "hotfix.apply", Optional.empty(), old);
    SnapshotStore oldSnapshots = new SnapshotStore(hf.home, hf.platform.files(), oldClock);
    Path foo = hf.target(HotfixFixture.FOO);
    for (String run : List.of("r-old", "r-inst", "r-pending")) {
      oldSnapshots.create(run, "snapshot", List.of(foo), hf.paths.installDir());
    }
    hf.ledger.recordInstalled(entry("HF-INSTALLED", "r-inst", HotfixState.INSTALLED));
    hf.ledger.recordInstalled(entry("HF-GONE", "r-vanished", HotfixState.INSTALLED));
    hf.ledger.updateState("HF-GONE", HotfixState.ROLLED_BACK);

    Bootstrap boot = boot(hf, Clock.fixed(now, ZoneOffset.UTC));
    RunService.PruneResult result = new RunService(boot).prune(Duration.ofDays(30));

    assertThat(result.runsRemoved()).containsExactlyInAnyOrder("r-old", "r-inst");
    assertThat(Files.exists(hf.home.runDir("r-old"))).isFalse();
    assertThat(Files.exists(hf.home.runDir("r-new"))).isTrue();
    assertThat(Files.exists(hf.home.runDir("r-pending"))).isTrue();
    assertThat(result.snapshotsRemoved()).containsExactly("r-old/snapshot");
    assertThat(hf.snapshots.find("r-inst", "snapshot")).isPresent();
    assertThat(hf.snapshots.find("r-pending", "snapshot")).isPresent();
    assertThat(result.ledgerEntriesRemoved()).containsExactly("HF-GONE");
    assertThat(hf.ledger.find("HF-INSTALLED")).isPresent();
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
    assertThat(kept.snapshotsRemoved()).isEmpty();
    assertThat(hf.snapshots.find("r-failed", "snapshot")).isPresent();
    assertThat(Files.exists(hf.home.runDir("r-failed"))).isTrue();

    RunService.PruneResult removed = new RunService(boot).prune(Duration.ofDays(30), true);
    assertThat(removed.runsRemoved()).containsExactly("r-failed");
    assertThat(removed.snapshotsRemoved()).containsExactly("r-failed/snapshot");
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

  private static LedgerEntry entry(String id, String runId, HotfixState state) {
    return new LedgerEntry(
        id,
        "10.0.0",
        "PRO",
        "1",
        "t",
        state,
        Origin.TOOL,
        runId,
        Optional.empty(),
        Instant.now(),
        List.of());
  }
}

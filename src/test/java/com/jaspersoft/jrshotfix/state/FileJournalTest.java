package com.jaspersoft.jrshotfix.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.TerminalState;
import com.jaspersoft.jrshotfix.home.Home;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileJournalTest {
  @TempDir Path tmp;

  @Test
  void should_list_a_run_as_pending_until_it_ends() throws Exception {
    FileJournal j = new FileJournal(new Home(tmp), Clock.systemUTC());
    j.recordRunStart(
        "r1", "hotfix.apply", Optional.of("p1"), Instant.parse("2026-09-28T10:00:00Z"));
    assertThat(j.pendingRuns()).extracting("runId").containsExactly("r1");
    j.appendTransition("r1", "snapshot", "backup", Optional.empty(), "RUNNING", Optional.empty());
    j.appendTransition(
        "r1", "snapshot", "backup", Optional.of("RUNNING"), "SUCCEEDED", Optional.of("12 files"));
    j.recordRunEnd("r1", Instant.parse("2026-09-28T10:05:00Z"), TerminalState.SUCCEEDED, 0);
    assertThat(j.pendingRuns()).isEmpty();
    assertThat(j.runs()).extracting("runId").containsExactly("r1");
    assertThat(j.run("r1").orElseThrow().exitCode()).contains(0);
    assertThat(j.transitions("r1")).hasSize(2);
    assertThat(j.transitions("r1").get(1).seq()).isEqualTo(2);
    assertThat(Files.readAllLines(tmp.resolve("runs/r1/journal.jsonl"))).hasSize(2);
  }

  @Test
  void should_survive_a_reopen_when_the_process_restarts() throws Exception {
    new FileJournal(new Home(tmp), Clock.systemUTC())
        .recordRunStart("r2", "hotfix.apply", Optional.empty(), Instant.now());
    assertThat(new FileJournal(new Home(tmp), Clock.systemUTC()).pendingRuns())
        .extracting("runId")
        .containsExactly("r2");
  }
}

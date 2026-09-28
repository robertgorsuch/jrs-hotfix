package com.jaspersoft.jrshotfix.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.home.Home;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerTest {
  @TempDir Path tmp;

  LedgerEntry entry(String id, String runId, Path... paths) {
    List<OwnedFile> files =
        java.util.Arrays.stream(paths)
            .map(p -> new OwnedFile(p, "replace", Optional.of("aaa"), Optional.of("bbb")))
            .toList();
    return new LedgerEntry(
        id,
        "10.0.0",
        "PRO",
        "20260730_0457",
        "hotfix " + id,
        HotfixState.INSTALLED,
        Origin.TOOL,
        runId,
        Optional.of("snapshots/" + runId),
        Instant.parse("2026-09-28T10:00:00Z"),
        files);
  }

  @Test
  void should_persist_entries_in_install_order_when_reopened() {
    Ledger l = new Ledger(new Home(tmp));
    l.recordInstalled(entry("A", "r1", tmp.resolve("x.jar")));
    l.recordInstalled(entry("B", "r2", tmp.resolve("x.jar"), tmp.resolve("y.jar")));
    Ledger reopened = new Ledger(new Home(tmp));
    assertThat(reopened.installed()).extracting(LedgerEntry::id).containsExactly("A", "B");
    assertThat(reopened.files("B")).hasSize(2);
    assertThat(reopened.filesOwnedBy(List.of(tmp.resolve("x.jar"))))
        .extracting(e -> e.getKey())
        .containsExactly("A", "B");
  }

  @Test
  void should_refuse_a_duplicate_id_when_recording() {
    Ledger l = new Ledger(new Home(tmp));
    l.recordInstalled(entry("A", "r1"));
    assertThatThrownBy(() -> l.recordInstalled(entry("A", "r9")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("A");
  }

  @Test
  void should_drop_a_rolled_back_entry_from_installed_but_keep_it_in_all() {
    Ledger l = new Ledger(new Home(tmp));
    l.recordInstalled(entry("A", "r1"));
    l.updateState("A", HotfixState.ROLLED_BACK);
    assertThat(l.installed()).isEmpty();
    assertThat(l.all()).extracting(LedgerEntry::state).containsExactly(HotfixState.ROLLED_BACK);
    assertThat(l.delete("A")).isTrue();
    assertThat(l.all()).isEmpty();
  }
}

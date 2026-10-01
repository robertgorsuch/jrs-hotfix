package com.jaspersoft.jrshotfix.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one level of undo: promote, demote, discard, and the conversion of a 0.5 home. */
class UndoStoreTest {

  @TempDir Path root;

  private Home home;
  private UndoStore store;

  @BeforeEach
  void setUp() {
    home = new Home(root);
    store = new UndoStore(home);
  }

  /** A snapshot directory as a run leaves it: a manifest and one payload file. */
  private Path snapshot(String runId) throws IOException {
    Path dir = home.runDir(runId).resolve("snapshot");
    Files.createDirectories(dir.resolve("payload"));
    Files.writeString(dir.resolve("manifest.json"), "{\"runId\":\"" + runId + "\"}");
    Files.writeString(dir.resolve("payload/a.jar"), runId);
    return dir;
  }

  private UndoRecord record(String id, String runId) {
    return new UndoRecord(
        id,
        "10.0.0",
        "PRO",
        "20260730_0457",
        "title",
        runId,
        Instant.parse("2026-10-01T00:00:00Z"),
        List.of(
            new OwnedFile(root.resolve("x/a.jar"), "replace", Optional.of("1"), Optional.of("2"))),
        List.of(),
        Optional.of("m-1"),
        List.of("release-10.0.0"));
  }

  @Test
  void should_publish_the_run_snapshot_with_its_record_when_promoting() throws IOException {
    Path snap = snapshot("r1");

    store.promote(snap, record("HF-1", "r1"));

    assertThat(snap).doesNotExist();
    assertThat(home.undo().resolve("payload/a.jar")).hasContent("r1");
    assertThat(store.read()).contains(record("HF-1", "r1"));
    assertThat(store.find("r1")).contains(record("HF-1", "r1"));
  }

  @Test
  void should_replace_the_previous_undo_and_converge_from_every_crash_point() throws IOException {
    store.promote(snapshot("r1"), record("HF-1", "r1"));
    Path snap2 = snapshot("r2");
    // a crash after the previous undo was moved aside, before the new one was renamed in
    Files.writeString(snap2.resolve(UndoStore.RECORD), Json.writePretty(record("HF-2", "r2")));
    Durability.move(home.undo(), root.resolve("undo.old"));

    store.promote(snap2, record("HF-2", "r2"));
    store.promote(snap2, record("HF-2", "r2"));

    assertThat(store.read().orElseThrow().runId()).isEqualTo("r2");
    assertThat(root.resolve("undo.old")).doesNotExist();
    assertThat(store.find("r1")).isEmpty();
  }

  @Test
  void should_put_the_previous_undo_back_when_demoting() throws IOException {
    store.promote(snapshot("r1"), record("HF-1", "r1"));
    Path snap2 = snapshot("r2");
    // promote crashed before it deleted the previous undo
    Files.writeString(snap2.resolve(UndoStore.RECORD), Json.writePretty(record("HF-2", "r2")));
    Durability.move(home.undo(), root.resolve("undo.old"));
    Durability.move(snap2, home.undo());

    store.demote("r2", snap2);
    store.demote("r2", snap2);

    assertThat(store.read().orElseThrow().runId()).isEqualTo("r1");
    assertThat(snap2.resolve("payload/a.jar")).hasContent("r2");
    assertThat(root.resolve("undo.old")).doesNotExist();
  }

  @Test
  void should_move_the_undo_into_the_rollback_and_back() throws IOException {
    store.promote(snapshot("r1"), record("HF-1", "r1"));

    store.discard("r1", "rb");
    store.discard("r1", "rb");

    assertThat(store.read()).isEmpty();
    assertThat(home.undo()).doesNotExist();
    assertThat(store.find("r1")).isPresent();
    assertThat(store.undone("rb").resolve("payload/a.jar")).hasContent("r1");

    store.undiscard("r1", "rb");
    store.undiscard("r1", "rb");

    assertThat(store.read().orElseThrow().runId()).isEqualTo("r1");
  }

  /** A 0.5 home: a ledger with an older and a newer installed hotfix, each with its snapshot. */
  private void legacyHome() throws IOException {
    for (String run : List.of("r-old", "r-new", "r-failed")) {
      Path dir = home.snapshots().resolve(run).resolve("snapshot");
      Files.createDirectories(dir.resolve("payload"));
      Files.writeString(dir.resolve("manifest.json"), "{}");
      Files.writeString(dir.resolve("payload/a.jar"), run);
    }
    List<Map<String, Object>> ledger =
        List.of(
            entry("HF-OLD", "r-old", "INSTALLED", "TOOL"),
            entry("HF-NEW", "r-new", "INSTALLED", "TOOL"),
            entry("HF-HAND", "recorded", "INSTALLED", "RECORDED"),
            entry("HF-GONE", "r-gone", "ROLLED_BACK", "TOOL"));
    Files.writeString(home.ledgerFile(), Json.writePretty(ledger), StandardCharsets.UTF_8);
  }

  private static Map<String, Object> entry(String id, String runId, String state, String origin) {
    return Map.of(
        "id",
        id,
        "release",
        "10.0.0",
        "edition",
        "PRO",
        "build",
        "20260730_0457",
        "title",
        "t",
        "state",
        state,
        "origin",
        origin,
        "runId",
        runId,
        "installedAt",
        "2026-08-01T00:00:00Z",
        "files",
        List.of());
  }

  @Test
  void should_read_the_newest_installed_hotfix_of_a_0_5_ledger_without_writing()
      throws IOException {
    legacyHome();

    assertThat(store.read().orElseThrow().id()).isEqualTo("HF-NEW");
    assertThat(store.find("r-new")).isPresent();
    assertThat(home.undo()).doesNotExist();
    assertThat(home.ledgerFile()).exists();
  }

  @Test
  void should_convert_a_0_5_home_once_and_keep_a_failed_runs_snapshot() throws IOException {
    legacyHome();

    assertThat(store.convertLegacy(Set.of("r-failed"))).isTrue();
    assertThat(store.convertLegacy(Set.of("r-failed"))).isFalse();

    assertThat(store.read().orElseThrow().runId()).isEqualTo("r-new");
    assertThat(home.undo().resolve("payload/a.jar")).hasContent("r-new");
    assertThat(home.snapshots()).doesNotExist();
    assertThat(home.runDir("r-failed").resolve("snapshot/payload/a.jar")).hasContent("r-failed");
    assertThat(home.ledgerFile()).doesNotExist();
    assertThat(root.resolve("ledger.json.0.5")).exists();
  }

  @Test
  void should_convert_a_0_5_home_with_nothing_left_to_undo() throws IOException {
    Files.writeString(
        home.ledgerFile(),
        Json.writePretty(List.of(entry("HF-GONE", "r-gone", "ROLLED_BACK", "TOOL"))));

    assertThat(store.convertLegacy(Set.of())).isTrue();

    assertThat(store.read()).isEmpty();
    assertThat(root.resolve("ledger.json.0.5")).exists();
  }
}

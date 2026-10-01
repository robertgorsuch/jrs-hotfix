package com.jaspersoft.jrshotfix.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one level of undo (0.6 design, sections 2, 3 and 7): {@code undo/} holds the latest apply's
 * snapshot and {@link UndoRecord}, until the next apply replaces it or a rollback uses it up.
 * Invariants: {@code undo/} is only ever whole, because it is published by renaming a complete
 * directory into place; {@link #promote} and {@link #discard} are idempotent and each has an
 * inverse, so a step that is retried or compensated after a crash converges; a home written by 0.1
 * to 0.5 is read through its {@code ledger.json} until {@link #convertLegacy} has run, and is never
 * written in that form.
 */
public final class UndoStore {

  /** The record's file name, beside the snapshot's {@code manifest.json}. */
  public static final String RECORD = "undo.json";

  /** Where a rollback keeps the undo it used up while it runs: {@code runs/<runId>/undone}. */
  public static final String UNDONE = "undone";

  private static final String OLD = "undo.old";
  private static final String LEGACY_SNAPSHOT = "snapshot";
  private static final TypeReference<List<LegacyEntry>> LEGACY = new TypeReference<>() {};

  private final Home home;

  public UndoStore(Home home) {
    this.home = home;
  }

  /** The directory a rollback of run {@code runId} moves the used-up undo into. */
  public Path undone(String runId) {
    return home.runDir(runId).resolve(UNDONE);
  }

  /** The latest apply that can be undone; empty when there is none. */
  public Optional<UndoRecord> read() {
    Optional<UndoRecord> current = readRecord(home.undo());
    return current.isPresent() ? current : legacy().map(Legacy::record);
  }

  /**
   * The undo of the apply run {@code appliedRunId}: in {@code undo/}, in the {@code undone/} of the
   * rollback that is using it up, or, for a home not yet converted, in {@code ledger.json}.
   */
  public Optional<UndoRecord> find(String appliedRunId) {
    Optional<UndoRecord> current = read().filter(r -> r.runId().equals(appliedRunId));
    if (current.isPresent() || !Files.isDirectory(home.runs())) {
      return current;
    }
    try (DirectoryStream<Path> runs = Files.newDirectoryStream(home.runs(), Files::isDirectory)) {
      for (Path run : runs) {
        Optional<UndoRecord> used =
            readRecord(run.resolve(UNDONE)).filter(r -> r.runId().equals(appliedRunId));
        if (used.isPresent()) {
          return used;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + home.runs(), e);
    }
    return Optional.empty();
  }

  /**
   * Makes {@code snapshotDir}, the apply run's own snapshot, the undo: writes {@code record} beside
   * its manifest, moves the previous {@code undo/} aside, renames the snapshot into place and
   * deletes the previous one. A crash between any two of these is finished by calling it again.
   */
  public void promote(Path snapshotDir, UndoRecord record) throws IOException {
    Path undo = home.undo();
    Path old = home.root().resolve(OLD);
    if (!holds(undo, record.runId())) {
      if (!Files.isRegularFile(snapshotDir.resolve("manifest.json"))) {
        throw new IOException(
            "the snapshot " + snapshotDir + " of run " + record.runId() + " is gone");
      }
      Durability.writeAtomically(snapshotDir.resolve(RECORD), Json.writePretty(record));
      if (Files.exists(undo)) {
        if (Files.exists(old)) {
          throw new IOException(
              undo + " and " + old + " both exist; remove the one that is not wanted by hand");
        }
        Durability.move(undo, old);
      }
      Durability.move(snapshotDir, undo);
      Durability.syncDirectory(home.root());
    }
    Trees.deleteRecursively(old);
  }

  /**
   * Undoes {@link #promote} of run {@code runId}: its snapshot goes back to {@code snapshotDir} and
   * the previous undo back into place. Nothing is done when the undo is not this run's.
   */
  public void demote(String runId, Path snapshotDir) throws IOException {
    Path undo = home.undo();
    Path old = home.root().resolve(OLD);
    if (holds(undo, runId)) {
      Durability.move(undo, snapshotDir);
    }
    if (Files.exists(old) && !Files.exists(undo)) {
      Durability.move(old, undo);
    }
    Durability.syncDirectory(home.root());
  }

  /**
   * Uses the undo of {@code appliedRunId} up: {@code undo/} moves into the rollback run's {@code
   * undone/}, where it stays until that run ends. Idempotent.
   */
  public void discard(String appliedRunId, String rollbackRunId) throws IOException {
    Path into = undone(rollbackRunId);
    if (holds(into, appliedRunId)) {
      return;
    }
    if (!holds(home.undo(), appliedRunId)) {
      throw new IOException("the undo of run " + appliedRunId + " is no longer in " + home.undo());
    }
    Files.createDirectories(into.getParent());
    Durability.move(home.undo(), into);
    Durability.syncDirectory(home.root());
  }

  /** Undoes {@link #discard}: the used-up undo goes back to {@code undo/}. */
  public void undiscard(String appliedRunId, String rollbackRunId) throws IOException {
    Path from = undone(rollbackRunId);
    if (holds(from, appliedRunId) && !Files.exists(home.undo())) {
      Durability.move(from, home.undo());
      Durability.syncDirectory(home.root());
    }
  }

  /**
   * Converts a home written by 0.1 to 0.5, once (0.6 design, section 7): the newest hotfix the
   * ledger lists as installed by this tool, whose snapshot is there, becomes {@code undo/}; the
   * snapshots of the runs in {@code failedRunIds} move into their run directories; the other
   * snapshots are deleted; {@code ledger.json} is renamed {@code ledger.json.0.5} and never read
   * again. Each step is idempotent, so a crash part-way is finished by the next call. Returns
   * whether there was anything to convert.
   */
  public boolean convertLegacy(Set<String> failedRunIds) throws IOException {
    Path ledger = home.ledgerFile();
    if (!Files.isRegularFile(ledger)) {
      return false;
    }
    if (!Files.exists(home.undo())) {
      Optional<Legacy> newest = legacy();
      if (newest.isPresent()) {
        promote(newest.get().snapshotDir(), newest.get().record());
      }
    }
    Path snapshots = home.snapshots();
    if (Files.isDirectory(snapshots)) {
      try (DirectoryStream<Path> runs = Files.newDirectoryStream(snapshots, Files::isDirectory)) {
        for (Path run : runs) {
          String runId = run.getFileName().toString();
          if (failedRunIds.contains(runId)) {
            moveSteps(run, home.runDir(runId));
          }
          Trees.deleteRecursively(run);
        }
      }
      Trees.deleteRecursively(snapshots);
    }
    Durability.move(ledger, ledger.resolveSibling(ledger.getFileName() + ".0.5"));
    Durability.syncDirectory(home.root());
    return true;
  }

  private static void moveSteps(Path from, Path into) throws IOException {
    Files.createDirectories(into);
    try (DirectoryStream<Path> steps = Files.newDirectoryStream(from, Files::isDirectory)) {
      for (Path step : steps) {
        Path target = into.resolve(step.getFileName().toString());
        if (!Files.exists(target)) {
          Durability.move(step, target, StandardCopyOption.ATOMIC_MOVE);
        }
      }
    }
  }

  private static boolean holds(Path dir, String runId) {
    return readRecord(dir).filter(r -> r.runId().equals(runId)).isPresent();
  }

  private static Optional<UndoRecord> readRecord(Path dir) {
    Path file = dir.resolve(RECORD);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          Json.mapper()
              .readValue(Files.readString(file, StandardCharsets.UTF_8), UndoRecord.class));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  /** The newest hotfix a 0.5 ledger lists as installed by this tool, with its snapshot there. */
  private Optional<Legacy> legacy() {
    Path file = home.ledgerFile();
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    List<LegacyEntry> all;
    try {
      all = Json.mapper().readValue(Files.readString(file, StandardCharsets.UTF_8), LEGACY);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    List<LegacyEntry> newestFirst = new ArrayList<>(all);
    java.util.Collections.reverse(newestFirst);
    for (LegacyEntry e : newestFirst) {
      Path snapshot = home.snapshots().resolve(e.runId()).resolve(LEGACY_SNAPSHOT);
      if ("INSTALLED".equals(e.state())
          && "TOOL".equals(e.origin())
          && Files.isRegularFile(snapshot.resolve("manifest.json"))) {
        return Optional.of(new Legacy(e.toRecord(), snapshot));
      }
    }
    return Optional.empty();
  }

  private record Legacy(UndoRecord record, Path snapshotDir) {}

  /** A row of a 0.1 to 0.5 {@code ledger.json}, as far as the conversion reads it. */
  private record LegacyEntry(
      String id,
      String release,
      String edition,
      String build,
      String title,
      String state,
      String origin,
      String runId,
      Instant installedAt,
      List<OwnedFile> files,
      Optional<String> mergeId,
      List<String> baselines,
      List<UndoRecord.KeptFile> kept) {

    UndoRecord toRecord() {
      return new UndoRecord(
          id,
          release,
          edition,
          build,
          title,
          runId,
          installedAt,
          files == null ? List.of() : files,
          kept,
          mergeId,
          baselines);
    }
  }
}

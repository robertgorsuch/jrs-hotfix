package com.jaspersoft.jrshotfix.home;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Every path under the jrs-hotfix home. Invariants: {@code root} is absolute and normalised;
 * nothing here touches the disk; run and snapshot directories are keyed by run id only.
 */
public record Home(Path root) {
  public Home {
    root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
  }

  public Path settingsFile() {
    return root.resolve("settings.json");
  }

  public Path ledgerFile() {
    return root.resolve("ledger.json");
  }

  public Path runLock() {
    return root.resolve("lock");
  }

  public Path runs() {
    return root.resolve("runs");
  }

  public Path runDir(String runId) {
    return runs().resolve(runId);
  }

  public Path stagingDir(String runId) {
    return runDir(runId).resolve("staging");
  }

  public Path logFile(String runId) {
    return runDir(runId).resolve("run.log");
  }

  /** The readme's manual steps for the package this run applied, never executed. */
  public Path notesFile(String runId) {
    return runDir(runId).resolve("notes.txt");
  }

  public Path snapshots() {
    return root.resolve("snapshots");
  }

  /** The vendor's files this installation is compared with, one directory per baseline. */
  public Path baselines() {
    return root.resolve("baselines");
  }

  /** The merge workspaces, one directory per merge. */
  public Path merges() {
    return root.resolve("merges");
  }

  public Path nativeTemp() {
    return root.resolve("tmp");
  }
}

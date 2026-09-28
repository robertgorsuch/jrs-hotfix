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

  public Path snapshots() {
    return root.resolve("snapshots");
  }

  public Path nativeTemp() {
    return root.resolve("tmp");
  }
}

package com.jaspersoft.jrshotfix.state;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the latest apply did, {@code undo/undo.json} beside the snapshot that undoes it (0.6 design,
 * section 2). Invariants: written once, by the apply's last step, and never edited; {@code files}
 * lists every file the hotfix wrote or deleted, in the order the hotfix applied them, with the hash
 * before (from the run's snapshot) and after the swap; {@code kept} lists the files the package
 * ships that were left as the site has them, which a rollback does not touch; {@code mergeId} names
 * the prepared merge the hotfix was applied with and {@code baselines} the vendor's files it was
 * compared with, both empty for an apply without a baseline.
 */
public record UndoRecord(
    String id,
    String release,
    String edition,
    String build,
    String title,
    String runId,
    Instant appliedAt,
    List<OwnedFile> files,
    List<KeptFile> kept,
    Optional<String> mergeId,
    List<String> baselines) {

  public UndoRecord {
    requireNonNull(id, "id");
    requireNonNull(release, "release");
    requireNonNull(edition, "edition");
    requireNonNull(build, "build");
    requireNonNull(title, "title");
    requireNonNull(runId, "runId");
    requireNonNull(appliedAt, "appliedAt");
    files = List.copyOf(requireNonNull(files, "files"));
    kept = kept == null ? List.of() : List.copyOf(kept);
    mergeId = mergeId == null ? Optional.empty() : mergeId;
    baselines = baselines == null ? List.of() : List.copyOf(baselines);
  }

  /**
   * One file the package ships that stayed as the site has it: the absolute path, the hash of the
   * package's copy, and why it stayed.
   */
  public record KeptFile(Path path, String vendorSha256, String reason) {
    public KeptFile {
      requireNonNull(path, "path");
      requireNonNull(vendorSha256, "vendorSha256");
      requireNonNull(reason, "reason");
    }
  }
}

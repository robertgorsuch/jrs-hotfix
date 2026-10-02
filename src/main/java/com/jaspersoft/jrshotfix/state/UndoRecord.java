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
 * compared with, both empty for an apply without a baseline; {@code war} is the WAR a build host's
 * apply replaced in place (0.7 design, section 2.1), whose earlier copy is in the undo's {@code
 * war/} directory, empty for every other apply.
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
    List<String> baselines,
    Optional<OwnedFile> war) {

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
    war = war == null ? Optional.empty() : war;
  }

  /** The record of an apply that replaced no WAR. */
  public UndoRecord(
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
    this(
        id,
        release,
        edition,
        build,
        title,
        runId,
        appliedAt,
        files,
        kept,
        mergeId,
        baselines,
        Optional.empty());
  }

  /** Every file the apply wrote, the WAR it replaced last. */
  public List<OwnedFile> allFiles() {
    if (war.isEmpty()) {
      return files;
    }
    List<OwnedFile> all = new java.util.ArrayList<>(files);
    all.add(war.get());
    return List.copyOf(all);
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

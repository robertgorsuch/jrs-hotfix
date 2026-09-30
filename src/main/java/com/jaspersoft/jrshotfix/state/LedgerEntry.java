package com.jaspersoft.jrshotfix.state;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One row of the ledger: a hotfix this installation knows about, installed by this tool or recorded
 * from an external application (spec, jrs-hotfix design). Invariants: {@code files} is immutable
 * and lists every file the hotfix owns, in the order the hotfix applied them; {@code kept} lists
 * the files the package ships that were left as the site has them, which the hotfix does not own
 * and a rollback does not touch; {@code mergeId} names the prepared merge the hotfix was applied
 * with and {@code baselines} the vendor's files it was compared with, both empty for an apply
 * without a baseline; an entry written before these existed reads back with none.
 */
public record LedgerEntry(
    String id,
    String release,
    String edition,
    String build,
    String title,
    HotfixState state,
    Origin origin,
    String runId,
    Optional<String> snapshotRef,
    Instant installedAt,
    List<OwnedFile> files,
    Optional<String> mergeId,
    List<String> baselines,
    List<KeptFile> kept) {
  public LedgerEntry {
    requireNonNull(id, "id");
    requireNonNull(release, "release");
    requireNonNull(edition, "edition");
    requireNonNull(build, "build");
    requireNonNull(title, "title");
    requireNonNull(state, "state");
    requireNonNull(origin, "origin");
    requireNonNull(runId, "runId");
    requireNonNull(snapshotRef, "snapshotRef");
    requireNonNull(installedAt, "installedAt");
    files = List.copyOf(requireNonNull(files, "files"));
    mergeId = mergeId == null ? Optional.empty() : mergeId;
    baselines = baselines == null ? List.of() : List.copyOf(baselines);
    kept = kept == null ? List.of() : List.copyOf(kept);
  }

  /** An entry of a hotfix applied without a merge. */
  public LedgerEntry(
      String id,
      String release,
      String edition,
      String build,
      String title,
      HotfixState state,
      Origin origin,
      String runId,
      Optional<String> snapshotRef,
      Instant installedAt,
      List<OwnedFile> files) {
    this(
        id,
        release,
        edition,
        build,
        title,
        state,
        origin,
        runId,
        snapshotRef,
        installedAt,
        files,
        Optional.empty(),
        List.of(),
        List.of());
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

  /** True when this hotfix was recorded from an external application rather than installed here. */
  public boolean recorded() {
    return origin == Origin.RECORDED;
  }

  /** This entry in another state. */
  public LedgerEntry withState(HotfixState newState) {
    return new LedgerEntry(
        id,
        release,
        edition,
        build,
        title,
        newState,
        origin,
        runId,
        snapshotRef,
        installedAt,
        files,
        mergeId,
        baselines,
        kept);
  }
}

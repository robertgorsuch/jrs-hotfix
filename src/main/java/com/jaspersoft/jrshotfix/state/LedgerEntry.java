package com.jaspersoft.jrshotfix.state;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One row of the ledger: a hotfix this installation knows about, installed by this tool or recorded
 * from an external application (spec, jrs-hotfix design). Invariant: {@code files} is immutable and
 * lists every file the hotfix owns, in the order the hotfix applied them.
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
    List<OwnedFile> files) {
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
  }

  /** True when this hotfix was recorded from an external application rather than installed here. */
  public boolean recorded() {
    return origin == Origin.RECORDED;
  }
}

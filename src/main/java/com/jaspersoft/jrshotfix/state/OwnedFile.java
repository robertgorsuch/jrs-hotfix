package com.jaspersoft.jrshotfix.state;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One file a ledger entry's hotfix owns. Invariant: {@code action} is {@code add}, {@code replace}
 * or {@code delete}; {@code path} is the absolute path the hotfix touched, serialised as a string.
 */
public record OwnedFile(
    Path path, String action, Optional<String> beforeSha256, Optional<String> afterSha256) {
  public OwnedFile {
    requireNonNull(path, "path");
    requireNonNull(action, "action");
    requireNonNull(beforeSha256, "beforeSha256");
    requireNonNull(afterSha256, "afterSha256");
  }
}

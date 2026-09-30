package com.jaspersoft.jrshotfix.state;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One file a ledger entry's hotfix owns. Invariant: {@code action} is {@code add}, {@code replace}
 * or {@code delete}; {@code path} is the absolute path the hotfix touched, serialised as a string;
 * {@code afterSha256} is the hash of what was written, and {@code vendorSha256} the hash of the
 * package's own copy when what was written is that copy merged with the site's file, else empty.
 */
public record OwnedFile(
    Path path,
    String action,
    Optional<String> beforeSha256,
    Optional<String> afterSha256,
    Optional<String> vendorSha256) {
  public OwnedFile {
    requireNonNull(path, "path");
    requireNonNull(action, "action");
    requireNonNull(beforeSha256, "beforeSha256");
    requireNonNull(afterSha256, "afterSha256");
    vendorSha256 = vendorSha256 == null ? Optional.empty() : vendorSha256;
  }

  /** A file written as the package has it. */
  public OwnedFile(
      Path path, String action, Optional<String> beforeSha256, Optional<String> afterSha256) {
    this(path, action, beforeSha256, afterSha256, Optional.empty());
  }

  /** True when what was written is the package's copy merged with the site's file. */
  public boolean wasMerged() {
    return vendorSha256.isPresent() && !vendorSha256.equals(afterSha256);
  }
}

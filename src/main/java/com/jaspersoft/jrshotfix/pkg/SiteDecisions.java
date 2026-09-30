package com.jaspersoft.jrshotfix.pkg;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * What was decided, before a package is read, about the files it ships under the webapp: a prepared
 * merge seen from the reader's side. Invariants: when {@link #active()}, every file the package
 * ships under the webapp has a decision, and the reader neither merges nor keeps anything on rules
 * of its own; when not, there are no decisions and the reader's own rules for the installer-written
 * files apply.
 */
public interface SiteDecisions {

  /** No merge was prepared: the reader decides by its own rules. */
  SiteDecisions NONE =
      new SiteDecisions() {
        @Override
        public boolean active() {
          return false;
        }

        @Override
        public Optional<Decision> of(String packagePath) {
          return Optional.empty();
        }
      };

  boolean active();

  /** The decision for one package path; empty for a path nothing was decided about. */
  Optional<Decision> of(String packagePath);

  /** What happens to one file. */
  enum Kind {
    /** The package's copy lands as it is. */
    PLAIN,
    /** The file on the server stays as it is: nothing lands, nothing is deleted. */
    KEEP,
    /** A merged file lands in place of the package's copy. */
    MERGED
  }

  /**
   * One decision. For {@link Kind#MERGED}, {@code mergedSha256} is the hash of what lands and
   * {@code mergedFile} where its bytes are; {@code reason} says why, for the operator.
   */
  record Decision(
      Kind kind, Optional<String> mergedSha256, Optional<Path> mergedFile, String reason) {
    public Decision {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(mergedSha256);
      Objects.requireNonNull(mergedFile);
      Objects.requireNonNull(reason);
    }

    public static Decision plain() {
      return new Decision(Kind.PLAIN, Optional.empty(), Optional.empty(), "");
    }

    public static Decision keep(String reason) {
      return new Decision(Kind.KEEP, Optional.empty(), Optional.empty(), reason);
    }

    public static Decision merged(String sha256, Path file, String reason) {
      return new Decision(Kind.MERGED, Optional.of(sha256), Optional.of(file), reason);
    }
  }
}

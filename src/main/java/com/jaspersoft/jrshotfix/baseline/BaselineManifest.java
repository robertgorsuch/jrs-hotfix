package com.jaspersoft.jrshotfix.baseline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What one baseline knows: the vendor's files of a release as it shipped, or of one hotfix package.
 * Invariants: paths are relative to the webapp, with {@code /}; every file of the source has a row
 * with its hash, mergeable or not; {@code deleted} is only filled for a hotfix and holds the paths
 * its readme deletes, a {@code *} standing for any run of characters inside one name; lists are
 * immutable.
 */
public record BaselineManifest(
    String id,
    Kind kind,
    String release,
    String edition,
    String build,
    Instant createdAt,
    String source,
    List<BaseFile> files,
    List<String> deleted) {

  /** A release as the vendor shipped it, or one hotfix package. */
  public enum Kind {
    RELEASE,
    HOTFIX
  }

  public BaselineManifest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(release, "release");
    Objects.requireNonNull(edition, "edition");
    Objects.requireNonNull(build, "build");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(source, "source");
    files = List.copyOf(files);
    deleted = deleted == null ? List.of() : List.copyOf(deleted);
  }

  /**
   * One vendor file. {@code textSha256} is the hash with line ends normalised, kept for the text
   * classes; {@code payload} says the file's content is stored beside the manifest; {@code
   * installer} says the vendor's copy holds an installer placeholder, so every server differs from
   * it.
   */
  public record BaseFile(
      String path,
      String sha256,
      long size,
      Optional<String> textSha256,
      boolean payload,
      boolean installer) {
    public BaseFile {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(sha256, "sha256");
      textSha256 = textSha256 == null ? Optional.empty() : textSha256;
    }
  }

  /** The id of the release baseline for one build of one release and edition. */
  public static String releaseId(String release, String edition, String build) {
    return "release-" + release + "-" + edition + "-" + build;
  }

  /** True when this hotfix's readme deletes {@code path}. */
  public boolean deletes(String path) {
    for (String d : deleted) {
      if (d.indexOf('*') < 0 ? d.equals(path) : glob(d).matcher(path).matches()) {
        return true;
      }
    }
    return false;
  }

  private static Pattern glob(String glob) {
    StringBuilder regex = new StringBuilder();
    for (String part : glob.split("\\*", -1)) {
      if (regex.length() > 0) {
        regex.append("[^/]*");
      }
      regex.append(Pattern.quote(part));
    }
    return Pattern.compile(regex.toString());
  }
}

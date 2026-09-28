package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.platform.FileOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One package entry resolved onto the server: the absolute target and its hash before the hotfix
 * (empty when absent). Invariants: hashes are captured at plan time and become fingerprint inputs,
 * so a target that changes between plan and run is refused by the Runner; the target is the one
 * path this entry touches.
 */
public record FileTarget(PackageContents.Entry entry, Path target, Optional<String> before) {

  public Action action() {
    return entry.action();
  }

  public String packagePath() {
    return entry.path();
  }

  /** Hash the target must have after the swap; empty for deletes. */
  public Optional<String> after() {
    return entry.sha256();
  }

  public boolean existedBefore() {
    return before.isPresent();
  }

  /** Files that exist now and will be replaced or deleted, so the snapshot must hold them. */
  public List<Path> snapshotPaths() {
    return existedBefore() ? List.of(target) : List.of();
  }

  /** Every path this entry touches. */
  public List<Path> touched() {
    return List.of(target);
  }

  public static List<FileTarget> resolve(
      PackageContents contents, PackagePaths paths, FileOps files) {
    List<FileTarget> out = new ArrayList<>();
    for (PackageContents.Entry entry : contents.entries()) {
      Path target = paths.resolve(entry.path());
      out.add(new FileTarget(entry, target, hashOf(files, target)));
    }
    return List.copyOf(out);
  }

  /** Hash of an existing regular file, empty when it is absent. */
  public static Optional<String> hashOf(FileOps files, Path path) {
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(files.sha256(path));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot hash " + path, e);
    }
  }
}

package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The resolved inputs of one apply plan. Invariants: targets are absolute and immutable; the
 * package file is the one the operator named, absolute and normalised; a target's staged copy sits
 * at its package path under the run's staging directory; {@code merge} is the prepared merge the
 * contents were read with, empty for an apply without a baseline.
 */
public record ApplyInput(
    Path packageFile,
    PackageContents contents,
    PackagePaths paths,
    List<FileTarget> targets,
    Optional<MergeDoc> merge) {

  public ApplyInput {
    packageFile = packageFile.toAbsolutePath().normalize();
    Objects.requireNonNull(contents, "contents");
    Objects.requireNonNull(paths, "paths");
    targets = List.copyOf(targets);
    Objects.requireNonNull(merge, "merge");
  }

  public Path stagingDir(Context ctx) {
    return ctx.home().stagingDir(ctx.runId());
  }

  // java.io.File.separatorChar is a constant, not file I/O
  public Path staged(Context ctx, FileTarget t) {
    return stagingDir(ctx).resolve(t.packagePath().replace('/', java.io.File.separatorChar));
  }

  public List<Path> snapshotPaths() {
    List<Path> out = new ArrayList<>();
    for (FileTarget t : targets) {
      out.addAll(t.snapshotPaths());
    }
    return out;
  }

  /**
   * The directory the snapshot is taken relative to: the installation tree when no target lies
   * under the webapp (beside a WAR, only those are swapped in place), else the directory both trees
   * share.
   */
  public Path snapshotBase() {
    return targets.stream().noneMatch(t -> t.packagePath().startsWith(PackagePaths.WEBAPPS_PREFIX))
        ? paths.installDir()
        : paths.commonBase();
  }

  public List<Path> touched() {
    return targets.stream().map(FileTarget::target).toList();
  }
}

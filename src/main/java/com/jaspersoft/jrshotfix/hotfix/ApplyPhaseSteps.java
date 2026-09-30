package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.Action;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackageStager;
import com.jaspersoft.jrshotfix.pkg.SiteSettings;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.snapshot.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The apply phase of the apply plan: staging straight out of the package, and the swap itself.
 * Invariants: every step re-checks the state on disk before it acts, so re-execution after a crash
 * converges; staging runs before the service stop, so the outage is only the swap; the swap
 * restores from the run's snapshot when compensated.
 */
final class ApplyPhaseSteps {

  private ApplyPhaseSteps() {}

  /**
   * Step 3: extract the payload into the run's staging directory, put the merged file in the place
   * of a settings file this server has values of its own in, and verify every hash. The package's
   * files are also written as the hotfix's baseline here, before the outage.
   */
  static final class StageFiles extends ApplySteps.ReadOnly {
    /**
     * Staging writes a tree under the run directory, so it is a mutation the runner must
     * compensate; as a read-only step its clean-up would never be called.
     */
    @Override
    public boolean mutating() {
      return true;
    }

    StageFiles(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.STAGE_FILES;
    }

    @Override
    public String title() {
      return "stage the package's files";
    }

    @Override
    public String phase() {
      return ApplySteps.APPLY;
    }

    @Override
    public String detail() {
      return "runs/{runId}/staging, extracted from the package, sha256 verified";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    /**
     * One pass over the package for every add or replace not already staged at its hash, so a
     * resume re-extracts only what is missing or damaged.
     */
    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      Map<String, FileTarget> wanted = new HashMap<>();
      try {
        for (FileTarget t : in.targets()) {
          if (t.action() != Action.DELETE
              && !FileTarget.hashOf(files, in.staged(ctx, t))
                  .map(h -> h.equals(t.after().orElse("")))
                  .orElse(false)) {
            wanted.put(t.packagePath(), t);
          }
        }
        // the package's files as the hotfix's baseline, while the package is being read anyway and
        // the service is still up: the record step then needs the package no more
        rt.baselines().addHotfix(in.packageFile(), in.contents(), rt.settings().webappName());
        if (wanted.isEmpty()) {
          return StepResult.ok();
        }
        PackageStager.stage(
            in.packageFile(),
            in.contents(),
            wanted.keySet(),
            in.stagingDir(ctx),
            path -> in.staged(ctx, wanted.get(path)),
            ctx.cancel());
        for (FileTarget t : wanted.values()) {
          if (t.entry().merged()) {
            Optional<StepResult> refused = merge(ctx, t);
            if (refused.isPresent()) {
              return refused.get();
            }
          }
          Optional<String> actual = FileTarget.hashOf(files, in.staged(ctx, t));
          if (!actual.equals(t.after())) {
            return Failures.recoverable(
                "staged "
                    + t.packagePath()
                    + " hashes to "
                    + actual.orElse("nothing (not in the package)")
                    + ", expected "
                    + t.after().orElse("?"),
                "the package changed since it was planned; plan again");
          }
        }
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot stage from " + in.packageFile() + ": " + e.getMessage(),
            "check free space under " + in.stagingDir(ctx));
      }
      return StepResult.ok();
    }

    /**
     * Puts the merged file in the place of the staged payload. The payload must be the one that was
     * planned. With a prepared merge, the merged file is the workspace's; without one, for a
     * settings file this server has values of its own in, the merge with the server's file as it is
     * now must give the file that was planned, or the server's file has changed and the plan no
     * longer holds. The swap then moves the merged file as it moves any other, and the snapshot
     * holds the server's file for a rollback.
     */
    private Optional<StepResult> merge(Context ctx, FileTarget t) throws IOException {
      Path staged = in.staged(ctx, t);
      Optional<String> payload = FileTarget.hashOf(rt.files(), staged);
      if (!payload.equals(t.entry().packageSha256())) {
        return Optional.of(
            Failures.recoverable(
                "staged "
                    + t.packagePath()
                    + " hashes to "
                    + payload.orElse("nothing (not in the package)")
                    + ", expected "
                    + t.entry().packageSha256().orElse("?"),
                "the package changed since it was planned; plan again"));
      }
      if (t.entry().mergedFile().isPresent()) {
        // a prepared merge holds the file to install; the hash check after this says it is the
        // one that was planned
        Path merged = t.entry().mergedFile().get();
        if (!Files.isRegularFile(merged)) {
          return Optional.of(
              Failures.recoverable(
                  "the merged file for " + t.packagePath() + " is gone: " + merged,
                  "prepare the merge again, then apply with it"));
        }
        Files.copy(merged, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Durability.sync(staged);
        return Optional.empty();
      }
      Optional<byte[]> theirs;
      try (InputStream file = Files.newInputStream(staged)) {
        theirs = SiteSettings.bounded(file);
      }
      Optional<SiteSettings.Merged> merged =
          theirs.isEmpty() ? Optional.empty() : SiteSettings.merge(t.target(), theirs.get());
      if (merged.isEmpty() || !Optional.of(merged.get().sha256()).equals(t.after())) {
        return Optional.of(
            Failures.recoverable(
                t.target()
                    + " changed since the plan was made, so the file planned from it and the"
                    + " package's is no longer the one to install",
                "run the command again; it plans from the file as it is now"));
      }
      Files.write(staged, merged.get().bytes());
      Durability.sync(staged);
      return Optional.empty();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Trees.deleteRecursively(in.stagingDir(ctx));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot remove staging: " + e.getMessage(), "delete " + in.stagingDir(ctx));
      }
    }
  }

  /** Step 5: rename staged files into place, delete listed files; compensation restores. */
  static final class AtomicSwap implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    AtomicSwap(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return ApplySteps.ATOMIC_SWAP;
    }

    @Override
    public String title() {
      return "swap " + in.targets().size() + " file(s) into place";
    }

    @Override
    public String phase() {
      return ApplySteps.APPLY;
    }

    @Override
    public String detail() {
      return "per-file rename, ACLs preserved; files already at the target hash are skipped";
    }

    /**
     * Every add or replace must have its staged copy, or already be in place from a swap that was
     * interrupted part-way. Existence only, so the outage is not spent re-reading the payload:
     * staging verified each hash and the postcheck re-hashes what landed. No touched file may still
     * be locked once the service is down.
     */
    @Override
    public CheckResult precheck(Context ctx) {
      FileOps files = rt.files();
      List<String> unstaged = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        if (t.action() == Action.DELETE || Files.isRegularFile(in.staged(ctx, t))) {
          continue;
        }
        String expected = t.after().orElse("");
        if (!FileTarget.hashOf(files, t.target()).map(expected::equals).orElse(false)) {
          unstaged.add(t.packagePath());
        }
      }
      if (!unstaged.isEmpty()) {
        return CheckResult.fail(
            "not staged and not in place: " + String.join(", ", unstaged),
            "stage-files did not run for this run or its staging tree was removed by hand; roll"
                + " the run back and apply the package again");
      }
      List<String> locked = new ArrayList<>();
      for (Path p : in.touched()) {
        if (Files.isRegularFile(p) && files.isLocked(p)) {
          locked.add(p + files.lockHolder(p).map(h -> " (held by " + h + ")").orElse(""));
        }
      }
      if (!locked.isEmpty()) {
        return CheckResult.fail(
            "still locked after the service stop: " + String.join(", ", locked),
            "end the process holding the file, then run again");
      }
      // no holder found is not the same as no holder when the scan is blind
      return files
          .lockInspectionLimit()
          .map(limit -> CheckResult.warn("no locked file found, but " + limit))
          .orElseGet(CheckResult::pass);
    }

    /**
     * After the swap every landed file must still hash to what the package holds and every deletion
     * must have happened, so nothing changed under the swap is recorded as installed.
     */
    @Override
    public CheckResult postcheck(Context ctx) {
      FileOps files = rt.files();
      List<String> wrong = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        switch (t.action()) {
          case ADD, REPLACE -> {
            String expected = t.after().orElse("");
            Optional<String> actual = FileTarget.hashOf(files, t.target());
            if (actual.isEmpty()) {
              wrong.add(t.target() + " is missing after the swap");
            } else if (!actual.get().equals(expected)) {
              wrong.add(
                  t.target()
                      + " hash is "
                      + actual.get()
                      + " after the swap, expected "
                      + expected);
            }
          }
          case DELETE -> {
            if (Files.exists(t.target())) {
              wrong.add(t.target() + " should have been deleted but still exists");
            }
          }
        }
      }
      return wrong.isEmpty()
          ? CheckResult.pass()
          : CheckResult.fail(String.join("; ", wrong), "the run is rolled back from the snapshot");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      for (FileTarget t : in.targets()) {
        ctx.cancel().checkpoint();
        try {
          switch (t.action()) {
            case ADD, REPLACE -> {
              String expected = t.after().orElse("");
              if (!FileTarget.hashOf(files, t.target()).map(expected::equals).orElse(false)) {
                Path staged = in.staged(ctx, t);
                if (!Files.isRegularFile(staged)) {
                  return Failures.recoverable(
                      "staged copy of " + t.packagePath() + " is missing",
                      "run again; stage-files recreates it",
                      List.of(t.target()),
                      List.of());
                }
                Files.createDirectories(t.target().getParent());
                files.atomicReplace(staged, t.target());
                String actual = files.sha256(t.target());
                if (!actual.equals(expected)) {
                  return Failures.recoverable(
                      t.target() + " hashes to " + actual + " after the swap, expected " + expected,
                      "the run is rolled back from the snapshot",
                      List.of(t.target()),
                      backups(ctx));
                }
              }
            }
            case DELETE -> Files.deleteIfExists(t.target());
          }
        } catch (IOException | UncheckedIOException e) {
          return Failures.recoverable(
              "cannot swap " + t.target() + ": " + e.getMessage(),
              "the run is rolled back from the snapshot",
              List.of(t.target()),
              backups(ctx));
        }
      }
      return StepResult.ok();
    }

    /**
     * Puts the swapped files back: whatever the snapshot holds is restored, and anything this run
     * created that the snapshot does not hold is removed. "Created" is decided by the snapshot too,
     * so a file the plan believed absent but which existed at swap time is restored rather than
     * deleted.
     */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      List<Path> affected = new ArrayList<>();
      try {
        PriorState before = PriorState.of(rt.snapshots(), ctx, ApplySteps.SNAPSHOT);
        for (FileTarget t : in.targets()) {
          if (t.action() == Action.DELETE) {
            continue;
          }
          boolean existed =
              before.known() ? before.before(t.target()).isPresent() : t.existedBefore();
          if (!existed) {
            affected.add(t.target());
            Files.deleteIfExists(t.target());
          }
        }
        Optional<Snapshot> snapshot = rt.snapshots().find(ctx.runId(), ApplySteps.SNAPSHOT);
        if (snapshot.isPresent()) {
          rt.snapshots().restore(snapshot.get());
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot restore the original files: " + Failures.describe(e),
            "restore the snapshot by hand",
            affected,
            backups(ctx));
      }
    }

    private List<Path> backups(Context ctx) {
      return List.of(rt.home().snapshots().resolve(ctx.runId()).resolve(ApplySteps.SNAPSHOT));
    }
  }
}

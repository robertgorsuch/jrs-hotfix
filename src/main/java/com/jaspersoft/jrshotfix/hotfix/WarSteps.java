package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.Action;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.DiskSpace;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.war.WarFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The steps of an apply whose target is a WAR (0.2 design, section 7), or a deployed or exploded
 * webapp directory turned into one (0.7 design, section 2.2): the input is never modified, the
 * hotfixed WAR is written beside the output path and renamed only once checked, and there is no
 * undo (a WAR has no server to put back): the output's own build file and the hotfix baseline in
 * the home say what it carries. Invariants: every step re-checks the state on disk before it acts,
 * so a resumed run converges; the only files written outside the home are the output and its
 * temporary name; there is no service, no snapshot and no rollback.
 */
final class WarSteps {

  static final String PHASE_ASSEMBLE = "assemble";
  static final String PREFLIGHT = "preflight-war";
  static final String ASSEMBLE = "assemble-war";
  static final String CHECK = "check-war";
  static final String RECORD = "record-war";

  private WarSteps() {}

  /**
   * What a WAR apply was asked to do, beside the package: with {@code --generic}, the files that
   * take the vendor's copy ({@code vendorCopies}, webapp path to the baseline's file) and those
   * left out because the vendor has none ({@code vendorDropped}).
   */
  record Target(
      Path war,
      Path out,
      String webappName,
      Map<String, Path> vendorCopies,
      Set<String> vendorDropped) {
    Target {
      war = war.toAbsolutePath().normalize();
      out = out.toAbsolutePath().normalize();
      vendorCopies = Map.copyOf(vendorCopies);
      vendorDropped = Set.copyOf(vendorDropped);
    }

    Target(Path war, Path out, String webappName) {
      this(war, out, webappName, Map.of(), Set.of());
    }

    Path temporary() {
      return WarFile.temporary(out);
    }

    String prefix() {
      return PackagePaths.WEBAPPS_PREFIX + webappName + "/";
    }

    /** The webapp path of a target under the WAR; empty for a file of the installation tree. */
    Optional<String> pathOf(FileTarget t) {
      return t.packagePath().startsWith(prefix())
          ? Optional.of(t.packagePath().substring(prefix().length()))
          : Optional.empty();
    }
  }

  /** Step 1: the WAR fits the package, the output is free, there is room. Mutates nothing. */
  static final class Preflight extends ApplySteps.ReadOnlyStep {
    private final Target target;

    Preflight(HotfixRuntime rt, ApplyInput in, Target target) {
      super(rt, in);
      this.target = target;
    }

    @Override
    public String id() {
      return PREFLIGHT;
    }

    @Override
    public String title() {
      return "check the WAR and the output";
    }

    @Override
    public String phase() {
      return ApplySteps.VERIFY;
    }

    @Override
    public String detail() {
      return "release and edition of the WAR, not hotfixed already, output absent, free space";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> problems =
          new ArrayList<>(HotfixPlans.applicability(rt, in.contents(), in.targets()));
      if (Files.exists(target.out())) {
        problems.add(target.out() + " exists already; the output is never overwritten");
      }
      if (!Files.exists(target.war())) {
        problems.add(target.war() + " is gone");
      }
      long size = 0;
      try {
        size = WarFile.size(target.war()) + Files.size(in.packageFile());
      } catch (IOException e) {
        problems.add("cannot size the inputs: " + e.getMessage());
      }
      Path outDir = target.out().getParent();
      if (outDir == null || !Files.isDirectory(outDir)) {
        problems.add("the directory of " + target.out() + " does not exist");
      } else {
        problems.addAll(
            DiskSpace.problems(
                rt.files(),
                List.of(
                    new DiskSpace.Need("staging", rt.home().root(), size),
                    new DiskSpace.Need("the output WAR", outDir, size))));
      }
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            String.join("; ", problems),
            "fix the listed problems, then run again; nothing was written");
      }
      List<String> warnings = HotfixPlans.buildWarnings(rt, in.contents());
      return warnings.isEmpty()
          ? CheckResult.pass()
          : CheckResult.warn(String.join("; ", warnings));
    }
  }

  /**
   * Step 3: stream the input WAR to the temporary output, without the entries the package replaces
   * or deletes, then append the staged files. Compensation removes the temporary file.
   */
  static final class Assemble extends HotfixStep<ApplyInput> {
    private final Target target;

    Assemble(HotfixRuntime rt, ApplyInput in, Target target) {
      super(rt, in);
      this.target = target;
    }

    @Override
    public String id() {
      return ASSEMBLE;
    }

    @Override
    public String title() {
      return "assemble the hotfixed WAR";
    }

    @Override
    public String phase() {
      return PHASE_ASSEMBLE;
    }

    @Override
    public String detail() {
      long inWar = in.targets().stream().filter(t -> target.pathOf(t).isPresent()).count();
      return target.temporary()
          + ": the input's entries without the "
          + inWar
          + " the package touches, plus the staged files";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> unstaged = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        if (t.action() != Action.DELETE
            && target.pathOf(t).isPresent()
            && !Files.isRegularFile(in.staged(ctx, t))) {
          unstaged.add(t.packagePath());
        }
      }
      return unstaged.isEmpty()
          ? CheckResult.pass()
          : CheckResult.fail(
              "not staged: " + String.join(", ", unstaged),
              "stage-files did not run for this run or its staging tree was removed; run again");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Set<String> dropped = new HashSet<>();
      Map<String, Path> staged = new LinkedHashMap<>();
      int skipped = 0;
      for (FileTarget t : in.targets()) {
        Optional<String> path = target.pathOf(t);
        if (path.isEmpty()) {
          skipped++;
          continue;
        }
        if (t.action() == Action.DELETE) {
          dropped.add(path.get());
        } else {
          staged.put(path.get(), in.staged(ctx, t));
        }
      }
      staged.putAll(target.vendorCopies());
      dropped.addAll(target.vendorDropped());
      if (skipped > 0) {
        log(
            ctx,
            out,
            Event.Log.Level.INFO,
            skipped
                + " file(s) of the installation tree (js-install.zip) are not part of a WAR"
                + " and were left out");
      }
      try {
        int count = WarFile.assemble(target.war(), target.temporary(), dropped, staged);
        log(ctx, out, Event.Log.Level.INFO, count + " entries written to " + target.temporary());
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot assemble " + target.temporary() + ": " + e.getMessage(),
            "check free space beside " + target.out());
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Files.deleteIfExists(target.temporary());
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot remove " + target.temporary() + ": " + e.getMessage(), "delete it by hand");
      }
    }
  }

  /** Step 4: reopen the temporary output and check every entry the plan wrote or dropped. */
  static final class Check extends ApplySteps.ReadOnlyStep {
    private final Target target;

    Check(HotfixRuntime rt, ApplyInput in, Target target) {
      super(rt, in);
      this.target = target;
    }

    @Override
    public String id() {
      return CHECK;
    }

    @Override
    public String title() {
      return "check the hotfixed WAR";
    }

    @Override
    public String phase() {
      return PHASE_ASSEMBLE;
    }

    @Override
    public String detail() {
      return "every written entry hashed, every dropped entry absent, the entry count";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isRegularFile(target.temporary())) {
        return CheckResult.fail(
            target.temporary() + " is missing", "run again; assemble-war writes it again");
      }
      try {
        List<String> problems = WarFile.check(target.temporary(), expected());
        return problems.isEmpty()
            ? CheckResult.pass()
            : CheckResult.fail(
                String.join("; ", problems),
                "the run is undone; nothing was written to " + target.out());
      } catch (IOException e) {
        return CheckResult.fail(
            "cannot read " + target.temporary() + ": " + e.getMessage(), "run again");
      }
    }

    WarFile.Expected expected() throws IOException {
      Map<String, String> hashes = new LinkedHashMap<>();
      Set<String> absent = new HashSet<>();
      Set<String> inputPaths = WarFile.paths(target.war());
      int count = inputPaths.size();
      for (FileTarget t : in.targets()) {
        Optional<String> path = target.pathOf(t);
        if (path.isEmpty()) {
          continue;
        }
        if (t.action() == Action.DELETE) {
          absent.add(path.get());
          if (inputPaths.contains(path.get())) {
            count--;
          }
        } else {
          hashes.put(path.get(), t.after().orElse(""));
          if (!inputPaths.contains(path.get())) {
            count++;
          }
        }
      }
      for (Map.Entry<String, Path> e : target.vendorCopies().entrySet()) {
        hashes.put(e.getKey(), FileTarget.hashOf(rt.files(), e.getValue()).orElse(""));
      }
      for (String path : target.vendorDropped()) {
        absent.add(path);
        if (inputPaths.contains(path)) {
          count--;
        }
      }
      return new WarFile.Expected(hashes, absent, count);
    }
  }

  /**
   * Step 5: give the output its name and keep the package's files as the hotfix's baseline.
   * Compensation removes the output.
   */
  static final class WriteOut extends HotfixStep<ApplyInput> {
    private final Target target;

    WriteOut(HotfixRuntime rt, ApplyInput in, Target target) {
      super(rt, in);
      this.target = target;
    }

    @Override
    public String id() {
      return RECORD;
    }

    @Override
    public String title() {
      return "write " + target.out().getFileName();
    }

    @Override
    public String phase() {
      return ApplySteps.RECORD;
    }

    @Override
    public String detail() {
      return "the hotfixed WAR, and the package's files as the hotfix's baseline";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isRegularFile(target.temporary()) && !Files.isRegularFile(target.out())) {
        return CheckResult.fail(
            target.temporary() + " is missing", "run again; assemble-war writes it again");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      try {
        if (Files.isRegularFile(target.temporary())) {
          Durability.move(target.temporary(), target.out(), StandardCopyOption.ATOMIC_MOVE);
        }
        rt.baselines().addHotfix(in.packageFile(), in.contents(), target.webappName());
        Trees.deleteRecursively(in.stagingDir(ctx));
        audit(ctx, out, "hotfix.war-written", in.contents().id() + " into " + target.out());
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot write the output: " + e.getMessage(),
            "check permissions beside " + target.out());
      }
    }

    @Override
    public boolean rollbackAllOnFailure() {
      return true;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Files.deleteIfExists(target.out());
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot remove " + target.out() + ": " + e.getMessage(), "delete it by hand");
      }
    }
  }
}

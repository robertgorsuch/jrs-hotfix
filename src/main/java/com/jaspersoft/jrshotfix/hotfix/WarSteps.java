package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.pkg.Action;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.DiskSpace;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import com.jaspersoft.jrshotfix.war.WarFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * The steps of an apply whose target is a WAR (0.2 design, section 7), a deployed or exploded
 * webapp directory turned into one (0.7 design, section 2.2), or a build host's distribution, whose
 * WAR is replaced in place (0.7 design, section 2.1): the hotfixed WAR is written beside the output
 * path and renamed only once checked. A WAR written to a new output has no undo of its own (a WAR
 * has no server to put back): the output's own build file and the hotfix baseline in the home say
 * what it carries. A build host's WAR moves into the run's snapshot as {@link SwapWar} replaces it,
 * and is undone with the installation files. Invariants: every step re-checks the state on disk
 * before it acts, so a resumed run converges; the input of a WAR target is never modified; there is
 * no service step.
 */
final class WarSteps {

  static final String PHASE_ASSEMBLE = "assemble";
  static final String PREFLIGHT = "preflight-war";
  static final String ASSEMBLE = "assemble-war";
  static final String CHECK = "check-war";
  static final String RECORD = "record-war";
  static final String SWAP_WAR = "swap-war";
  static final String RESTORE_WAR = "restore-war";

  /** Where a build host's apply keeps the WAR it replaced: this directory of the run's snapshot. */
  static final String WAR_DIR = "war";

  private static final String SWAP_FILE = "war.json";

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

    /** True on a build host: the output is the input, replaced once the hotfixed WAR is checked. */
    boolean inPlace() {
      return war.equals(out);
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

  /**
   * Step 1: the WAR fits the package, the output is free (or, in place, writable), there is room;
   * with {@code installation}, the files of the installation tree the plan swaps can be written and
   * snapshotted too. Mutates nothing.
   */
  static final class Preflight extends ApplySteps.ReadOnlyStep {
    private final Target target;
    private final Optional<ApplyInput> installation;

    Preflight(HotfixRuntime rt, ApplyInput in, Target target, Optional<ApplyInput> installation) {
      super(rt, in);
      this.target = target;
      this.installation = installation;
    }

    @Override
    public String id() {
      return PREFLIGHT;
    }

    @Override
    public String title() {
      return target.inPlace() ? "check the distribution" : "check the WAR and the output";
    }

    @Override
    public String phase() {
      return ApplySteps.VERIFY;
    }

    @Override
    public String detail() {
      return "release and edition of the WAR, not hotfixed already, "
          + (target.inPlace() ? "write access" : "output absent")
          + (installation.isPresent() ? ", the installation tree writable, file owners" : "")
          + ", free space";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> problems =
          new ArrayList<>(
              HotfixPlans.applicability(rt, in.contents(), in.targets(), in.allowOlder()));
      if (!target.inPlace() && Files.exists(target.out())) {
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
        List<DiskSpace.Need> needs = new ArrayList<>();
        needs.add(new DiskSpace.Need("staging", rt.home().root(), size));
        needs.add(new DiskSpace.Need("the output WAR", outDir, size));
        if (target.inPlace()) {
          if (!rt.files().isWritable(outDir)) {
            problems.add(outDir + " is not writable by this account");
          }
          if (!sameVolume(outDir, rt.home().root())) {
            // the WAR moves into the home as the undo, which costs a copy on another volume
            needs.add(new DiskSpace.Need("the replaced WAR", rt.home().root(), size));
          }
        }
        installation.ifPresent(i -> installationProblems(i, problems, needs));
        problems.addAll(DiskSpace.problems(rt.files(), needs));
      }
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            String.join("; ", problems),
            "fix the listed problems, then run again; nothing was written");
      }
      List<String> warnings = HotfixPlans.buildWarnings(rt, in.contents(), in.allowOlder());
      return warnings.isEmpty()
          ? CheckResult.pass()
          : CheckResult.warn(String.join("; ", warnings));
    }

    /** The installation tree must be writable, its files' owners restorable, its snapshot fit. */
    private void installationProblems(
        ApplyInput installation, List<String> problems, List<DiskSpace.Need> needs) {
      Path dir = existing(rt.settings().installDir());
      if (!rt.files().isWritable(dir)) {
        problems.add(dir + " is not writable by this account");
      }
      List<Path> kept = installation.snapshotPaths();
      OwnerRestore.problem(rt.files(), kept).ifPresent(problems::add);
      long bytes = 0;
      for (Path p : kept) {
        try {
          bytes += Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
          problems.add("cannot size " + p + " for the snapshot: " + e.getMessage());
        }
      }
      needs.add(new DiskSpace.Need("snapshot", rt.home().root(), bytes));
    }
  }

  /** {@code dir}, or its nearest ancestor that exists: where a directory not made yet would be. */
  private static Path existing(Path dir) {
    Path p = dir.toAbsolutePath().normalize();
    while (!Files.exists(p) && p.getParent() != null) {
      p = p.getParent();
    }
    return p;
  }

  private static boolean sameVolume(Path a, Path b) {
    try {
      return Files.getFileStore(existing(a)).equals(Files.getFileStore(existing(b)));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Step 3: stream the input WAR to the temporary output, without the entries the package replaces
   * or deletes, then append the staged files. Compensation removes the temporary file.
   */
  static final class Assemble extends HotfixStep<ApplyInput> {
    private final Target target;
    private final String phase;

    Assemble(HotfixRuntime rt, ApplyInput in, Target target) {
      this(rt, in, target, PHASE_ASSEMBLE);
    }

    /** With {@code phase}: the apply phase where installation files are swapped around it. */
    Assemble(HotfixRuntime rt, ApplyInput in, Target target, String phase) {
      super(rt, in);
      this.target = target;
      this.phase = phase;
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
      return phase;
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
    private final String phase;

    Check(HotfixRuntime rt, ApplyInput in, Target target) {
      this(rt, in, target, PHASE_ASSEMBLE);
    }

    Check(HotfixRuntime rt, ApplyInput in, Target target, String phase) {
      super(rt, in);
      this.target = target;
      this.phase = phase;
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
      return phase;
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

  /**
   * What {@link SwapWar} replaced, {@code war.json} in the run's snapshot beside the earlier WAR:
   * the WAR's path, its hash before and the hotfixed one's. Written before anything is moved, so an
   * interrupted swap is finished or undone from it.
   */
  record Swap(String war, String before, String after) {

    /** The swap recorded in the snapshot {@code snapshotDir}; empty when no WAR was swapped. */
    static Optional<Swap> read(Path snapshotDir) throws IOException {
      Path file = snapshotDir.resolve(WAR_DIR).resolve(SWAP_FILE);
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      return Optional.of(
          Json.mapper().readValue(Files.readString(file, StandardCharsets.UTF_8), Swap.class));
    }

    void write(Path snapshotDir) throws IOException {
      Durability.writeAtomically(
          snapshotDir.resolve(WAR_DIR).resolve(SWAP_FILE), Json.writePretty(this));
    }

    /** The WAR as the undo lists it, after the installation files. */
    OwnedFile owned() {
      return new OwnedFile(Path.of(war), "replace", Optional.of(before), Optional.of(after));
    }
  }

  /**
   * A build host's step 7 (0.7 design, section 2.1): the distribution's WAR moves into the run's
   * snapshot, beside the installation files it took, and the checked WAR takes its name. Moved, not
   * copied: on the home's volume the undo costs no second copy. The hashes are recorded before
   * anything moves. Compensation puts the earlier WAR back.
   */
  static final class SwapWar extends HotfixStep<ApplyInput> {
    private final Target target;

    SwapWar(HotfixRuntime rt, ApplyInput in, Target target) {
      super(rt, in);
      this.target = target;
    }

    @Override
    public String id() {
      return SWAP_WAR;
    }

    @Override
    public String title() {
      return "replace " + target.war().getFileName();
    }

    @Override
    public String phase() {
      return ApplySteps.APPLY;
    }

    @Override
    public String detail() {
      return "the earlier WAR into the snapshot, the hotfixed one into its place";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      try {
        if (Files.isRegularFile(target.temporary())
            || Swap.read(snapshotDir(ctx)).map(s -> hashesTo(s.after())).orElse(false)) {
          return CheckResult.pass();
        }
      } catch (IOException e) {
        return CheckResult.fail("cannot read the swap of the WAR: " + e.getMessage(), "run again");
      }
      return CheckResult.fail(
          target.temporary() + " is missing", "run again; assemble-war writes it again");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path dir = snapshotDir(ctx);
      Path kept = kept(dir);
      try {
        Optional<Swap> recorded = Swap.read(dir);
        if (recorded.isEmpty()) {
          Swap swap =
              new Swap(
                  target.war().toString(),
                  rt.files().sha256(target.war()),
                  rt.files().sha256(target.temporary()));
          swap.write(dir);
          recorded = Optional.of(swap);
        }
        if (Files.isRegularFile(target.war()) && !Files.exists(kept)) {
          Durability.move(target.war(), kept);
        }
        if (Files.isRegularFile(target.temporary())) {
          Durability.move(target.temporary(), target.war(), StandardCopyOption.ATOMIC_MOVE);
        }
        if (!hashesTo(recorded.get().after())) {
          return Failures.recoverable(
              target.war() + " is not the hotfixed WAR after the swap",
              "the run is rolled back; the earlier WAR is put back from " + kept,
              List.of(target.war()),
              List.of(kept));
        }
        log(ctx, out, Event.Log.Level.INFO, target.war() + " replaced; the earlier one is " + kept);
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot replace " + target.war() + ": " + e.getMessage(),
            "the run is rolled back; the earlier WAR is put back from " + kept,
            List.of(target.war()),
            List.of(kept));
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Path dir = snapshotDir(ctx);
      Path kept = kept(dir);
      try {
        if (Files.isRegularFile(kept)) {
          // the WAR at the name now is the hotfixed one, or none
          Files.deleteIfExists(target.war());
          Durability.move(kept, target.war(), StandardCopyOption.ATOMIC_MOVE);
        }
        Files.deleteIfExists(dir.resolve(WAR_DIR).resolve(SWAP_FILE));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot put the earlier WAR back: " + e.getMessage(),
            "move " + kept + " to " + target.war() + " by hand");
      }
    }

    private Path snapshotDir(Context ctx) {
      return ApplySteps.snapshotDir(rt.home(), ctx.runId());
    }

    private Path kept(Path snapshotDir) {
      return snapshotDir.resolve(WAR_DIR).resolve(target.war().getFileName().toString());
    }

    private boolean hashesTo(String sha256) {
      return FileTarget.hashOf(rt.files(), target.war()).map(sha256::equals).orElse(false);
    }
  }

  /**
   * A build host's rollback: the WAR the latest apply replaced comes back from {@code undo/war/},
   * and the hotfixed one takes its place there, so the undo the rollback uses up carries it away,
   * and it is deleted with it when the run ends. Compensation swaps them back.
   */
  static final class RestoreWar extends HotfixStep<RollbackSteps.Input> {
    private final OwnedFile war;

    RestoreWar(HotfixRuntime rt, RollbackSteps.Input in) {
      super(rt, in);
      this.war = in.undo().war().orElseThrow();
    }

    @Override
    public String id() {
      return RESTORE_WAR;
    }

    @Override
    public String title() {
      return "put back the earlier " + war.path().getFileName();
    }

    @Override
    public String phase() {
      return RollbackSteps.PHASE;
    }

    @Override
    public String detail() {
      return kept() + " back to " + war.path();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (Files.isRegularFile(kept()) || restored()) {
        return CheckResult.pass();
      }
      return CheckResult.fail(
          kept() + " is missing", "restore the jrs-hotfix home from a backup, then run again");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path aside = aside();
      try {
        if (!Files.isRegularFile(kept()) && restored()) {
          return StepResult.ok();
        }
        if (Files.isRegularFile(war.path()) && !Files.exists(aside)) {
          Files.createDirectories(aside.getParent());
          Durability.move(war.path(), aside);
        }
        Durability.move(kept(), war.path(), StandardCopyOption.ATOMIC_MOVE);
        if (!restored()) {
          return Failures.recoverable(
              war.path() + " is not the earlier WAR after the restore",
              "the rollback is undone; check " + war.path(),
              List.of(war.path()),
              List.of(aside));
        }
        log(ctx, out, Event.Log.Level.INFO, war.path() + " put back; the hotfixed one is " + aside);
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot put back " + war.path() + ": " + e.getMessage(),
            "the rollback is undone; the hotfixed WAR is " + aside,
            List.of(war.path()),
            List.of(aside));
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Path aside = aside();
      try {
        if (Files.isRegularFile(aside)) {
          if (Files.isRegularFile(war.path()) && !Files.exists(kept())) {
            Durability.move(war.path(), kept());
          }
          Durability.move(aside, war.path(), StandardCopyOption.ATOMIC_MOVE);
        }
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot put the hotfixed WAR back: " + e.getMessage(),
            "move " + aside + " to " + war.path() + " by hand");
      }
    }

    /** The earlier WAR, in the undo. */
    private Path kept() {
      return rt.home().undo().resolve(WAR_DIR).resolve(war.path().getFileName().toString());
    }

    /** Where the hotfixed WAR waits while the rollback runs: in the undo it uses up. */
    private Path aside() {
      return kept().resolveSibling(war.path().getFileName() + ".hotfixed");
    }

    private boolean restored() {
      return FileTarget.hashOf(rt.files(), war.path()).equals(war.beforeSha256());
    }
  }
}

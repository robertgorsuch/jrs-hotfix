package com.jaspersoft.jrshotfix.hotfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.baseline.BaselineManifest;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.PlanFingerprint;
import com.jaspersoft.jrshotfix.engine.PlanSummary;
import com.jaspersoft.jrshotfix.engine.RunIds;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.home.JrsVersion;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import com.jaspersoft.jrshotfix.pkg.Action;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.SiteDecisions;
import com.jaspersoft.jrshotfix.scan.Scan;
import com.jaspersoft.jrshotfix.service.ServiceSteps;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import com.jaspersoft.jrshotfix.state.UndoRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds every plan and answers every read-only question. Invariants: planning touches nothing
 * outside the home's run log; a plan is rebuilt from its arguments alone, so recovery can compare
 * fingerprints; refusals are {@link HotfixException}s with a remediation.
 */
public final class HotfixPlans {
  public static final String APPLY = "hotfix.apply";

  /** The operation of an apply whose target is a WAR; rebuilt as {@link #APPLY} is. */
  public static final String APPLY_WAR = "hotfix.apply-war";

  public static final String ROLLBACK = "hotfix.rollback";

  /** The audit kind the front end writes to the run log when the package checksum is confirmed. */
  public static final String AUDIT_CHECKSUM_CONFIRMED = ApplySteps.AUDIT_CHECKSUM_CONFIRMED;

  /**
   * Marks a plan summary warning as one of the package readme's manual steps, so {@link
   * #notesOf(Plan)} can find it again without a new field on the copied {@link PlanSummary}.
   */
  public static final String NOTE_PREFIX = "readme: ";

  /**
   * Marks a plan summary warning as a line of the package readme itself, carried as the readme has
   * it. The plan preview shows the first lines of such a block; {@link #notesOf(Plan)} returns them
   * all.
   */
  public static final String QUOTE_PREFIX = "readme> ";

  private static final String AS_PUBLISHED =
      "point jrs-hotfix at the hotfix ZIP as support published it";

  private final HotfixRuntime rt;

  public HotfixPlans(HotfixRuntime rt) {
    this.rt = Objects.requireNonNull(rt, "rt");
  }

  /**
   * What {@code apply} was asked to do; stored with the run so the plan can be rebuilt. {@code
   * mergeId} names the prepared merge that says what happens to the files this site changed; empty
   * means there was no baseline to compare with, and the package is applied as 0.1 applied it.
   */
  public record ApplyArgs(
      Path packageFile,
      boolean checksumConfirmed,
      Optional<String> mergeId,
      Optional<Path> war,
      Optional<Path> out,
      boolean keepSuperseded) {
    public ApplyArgs {
      Objects.requireNonNull(packageFile, "packageFile");
      Objects.requireNonNull(mergeId, "mergeId");
      Objects.requireNonNull(war, "war");
      Objects.requireNonNull(out, "out");
      if (war.isPresent() != out.isPresent()) {
        throw new IllegalArgumentException("--war and --out go together");
      }
    }

    public ApplyArgs(
        Path packageFile,
        boolean checksumConfirmed,
        Optional<String> mergeId,
        Optional<Path> war,
        Optional<Path> out) {
      this(packageFile, checksumConfirmed, mergeId, war, out, false);
    }

    public ApplyArgs(Path packageFile, boolean checksumConfirmed, Optional<String> mergeId) {
      this(packageFile, checksumConfirmed, mergeId, Optional.empty(), Optional.empty());
    }

    /** These arguments with superseded libraries left where they are. */
    public ApplyArgs keepingSuperseded() {
      return new ApplyArgs(packageFile, checksumConfirmed, mergeId, war, out, true);
    }

    public ApplyArgs(Path packageFile, boolean checksumConfirmed) {
      this(packageFile, checksumConfirmed, Optional.empty());
    }

    /** These arguments with a WAR as the target. */
    public ApplyArgs intoWar(Path warFile, Path outFile) {
      return new ApplyArgs(
          packageFile,
          checksumConfirmed,
          mergeId,
          Optional.of(warFile),
          Optional.of(outFile),
          keepSuperseded);
    }
  }

  /**
   * The arguments an apply of {@code packageFile} runs with on this server. Without a baseline
   * there is no merge. With one that fits, the newest merge still valid for this package is used,
   * or a new one prepared, and the apply is refused (exit 2) while a file in it waits for the
   * operator. With baselines that do not fit, the apply is refused rather than run blind. A merge
   * named by the operator is taken as given; {@link #planApply} checks it.
   */
  public ApplyArgs resolveApply(
      Path packageFile,
      boolean checksumConfirmed,
      Optional<String> mergeId,
      Optional<MergeWorkspace.OnConflict> asked,
      MergeWorkspace.OnConflict fallback) {
    if (mergeId.isPresent()) {
      return new ApplyArgs(packageFile, checksumConfirmed, mergeId);
    }
    BaseView.Resolution resolution = rt.baseView();
    if (resolution.view().isEmpty()) {
      if (resolution.present()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            resolution.problem()
                + "; without it jrs-hotfix cannot tell this site's changes from the vendor's",
            resolution.remediation()
                + "; or remove the baselines with `jrs-hotfix baseline remove <id>` to apply"
                + " without comparing, which replaces every file the package ships");
      }
      return new ApplyArgs(packageFile, checksumConfirmed);
    }
    MergeDoc doc = prepareMerge(resolution.view().get(), packageFile, asked, fallback, true);
    if (!doc.blocking().isEmpty()) {
      throw MergePlans.blocked(doc);
    }
    return new ApplyArgs(packageFile, checksumConfirmed, Optional.of(doc.id()));
  }

  /**
   * A merge of {@code packageFile} into this server: a new one, or with {@code reuse} the newest
   * that is still valid. Refuses (exit 2) when no baseline fits this installation. Writes under the
   * home only.
   */
  public MergeDoc prepareMerge(
      Path packageFile,
      Optional<MergeWorkspace.OnConflict> asked,
      MergeWorkspace.OnConflict fallback,
      boolean reuse) {
    BaseView.Resolution resolution = rt.baseView();
    BaseView view =
        resolution
            .view()
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK, resolution.problem(), resolution.remediation()));
    return prepareMerge(view, packageFile, asked, fallback, reuse);
  }

  private MergeDoc prepareMerge(
      BaseView view,
      Path packageFile,
      Optional<MergeWorkspace.OnConflict> asked,
      MergeWorkspace.OnConflict fallback,
      boolean reuse) {
    Path file = packageFile.toAbsolutePath().normalize();
    PackageContents contents = readPackage(file);
    MergePlans merges = new MergePlans(rt);
    if (reuse) {
      Optional<MergeDoc> existing = merges.reusable(contents, view, asked);
      if (existing.isPresent()) {
        return existing.get();
      }
    }
    return merges.prepare(file, contents, view, asked.orElse(fallback));
  }

  public Plan planApply(Path packageFile, boolean checksumConfirmed) {
    return planApply(new ApplyArgs(packageFile, checksumConfirmed));
  }

  /**
   * The nine-step apply plan: preflight, snapshot, stage (before the outage), stop, swap, clear the
   * JSP cache, start, wait, and keep the snapshot as the undo.
   */
  public Plan planApply(ApplyArgs args) {
    if (args.war().isPresent()) {
      return planApplyWar(args);
    }
    Prepared p = prepareApply(args, "changed", List.of());
    ApplyInput in = p.in();
    List<String> warnings = p.warnings();
    Scan.externalAuthWarning(in.contents(), rt.settings().webappDir()).ifPresent(warnings::add);
    warnings.add(
        "the service is stopped for the swap; this node only, other cluster nodes are not touched");

    List<Step> steps = new ArrayList<>();
    steps.add(new ApplySteps.Preflight(rt, in));
    steps.add(new ApplyPhaseSteps.TakeSnapshot(rt, in));
    steps.add(new ApplyPhaseSteps.StageFiles(rt, in));
    steps.add(ServiceSteps.stop(rt, ApplySteps.APPLY, ServiceSteps.STOP));
    steps.add(new ApplyPhaseSteps.AtomicSwap(rt, in));
    steps.add(new JspCacheStep(rt, ApplySteps.APPLY, JspCacheStep.ID));
    steps.add(ServiceSteps.start(rt, ApplySteps.APPLY, ServiceSteps.START));
    steps.add(ServiceSteps.waitForServer(rt, ApplySteps.APPLY, ServiceSteps.WAIT));
    steps.add(new RecordSteps.PromoteUndo(rt, in));

    Path snapshotDir = ApplySteps.snapshotDir(rt.home(), "{runId}");
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(ApplySteps.VERIFY, "nothing mutated");
    rollbackPoints.put(ApplySteps.BACKUP, "snapshot written, server untouched");
    rollbackPoints.put(ApplySteps.APPLY, "restore " + snapshotDir + ", restart service");
    rollbackPoints.put(
        ApplySteps.RECORD, "restore " + snapshotDir + ", restart service, the previous undo kept");
    PlanSummary summary =
        new PlanSummary(
            APPLY,
            in.contents().id() + " " + in.contents().title(),
            in.touched(),
            List.of(),
            true,
            List.of(snapshotDir),
            rollbackPoints,
            "official-package",
            warnings,
            p.changes());
    return new Plan(
        "hotfix-apply-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(p.inputs()));
  }

  /**
   * What an apply into the server and one into a WAR share: the input, the plan summary's warnings
   * and changes so far, and the fingerprint inputs so far. Each list and map is the caller's to add
   * to.
   */
  private record Prepared(
      ApplyInput in, List<String> warnings, List<String> changes, Map<String, String> inputs) {}

  /**
   * Reads the package with the merge {@code args} names and resolves its targets. The warnings
   * start with what preflight will refuse, since the preview runs no step: the applicability
   * problems, then {@code refusals}, each said to be refused "before anything is {@code mutation}".
   */
  private Prepared prepareApply(ApplyArgs args, String mutation, List<String> refusals) {
    Path file = args.packageFile().toAbsolutePath().normalize();
    MergePlans merges = new MergePlans(rt);
    Optional<MergeDoc> merge = args.mergeId().map(merges::forApply);
    PackageContents contents =
        readPackage(file, merge.map(merges::decisions).orElse(SiteDecisions.NONE), args);
    merge.ifPresent(m -> merges.check(m, contents));
    List<FileTarget> targets = FileTarget.resolve(contents, rt.paths(), rt.files());
    ApplyInput in = new ApplyInput(file, contents, rt.paths(), targets, merge);

    List<String> warnings = new ArrayList<>();
    String refused = "this plan will be refused before anything is " + mutation + ": ";
    for (String problem : applicability(rt, contents, targets)) {
      warnings.add(refused + problem);
    }
    for (String problem : refusals) {
      warnings.add(refused + problem);
    }
    warnings.addAll(buildWarnings(rt, contents));
    warnings.add(
        "package "
            + file.getFileName()
            + " (sha256 "
            + contents.sha256()
            + "); compare it with the checksum on the support portal"
            + (args.checksumConfirmed() ? " (confirmed)" : ""));
    for (PackageContents.Note note : contents.notes()) {
      warnings.add((note.quoted() ? QUOTE_PREFIX : NOTE_PREFIX) + note.text());
    }
    List<String> changes = new ArrayList<>(applyChanges(targets));
    changes.addAll(supersededChanges(contents));
    merge.ifPresent(m -> changes.addAll(MergePlans.changes(m)));

    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("package", contents.sha256());
    inputs.put("settings", rt.settings().fingerprintInput());
    inputs.put("installed", JrsVersion.ofWebapp(rt.settings().webappDir()).orElse("unknown"));
    for (FileTarget t : targets) {
      inputs.put("target:" + t.packagePath(), t.before().orElse("absent"));
      if (t.entry().merged()) {
        inputs.put("merged:" + t.packagePath(), t.after().orElse("absent"));
      }
    }
    if (merge.isPresent()) {
      // a merge edited after the run began is another plan: recovery compares these two
      inputs.put(MERGE_INPUT, merge.get().id());
      inputs.put(
          MERGE_DOC_INPUT,
          FileTarget.hashOf(rt.files(), rt.merges().docFile(merge.get().id())).orElse("absent"));
    }
    return new Prepared(in, warnings, changes, inputs);
  }

  /**
   * The five-step plan that hotfixes a WAR instead of a server (0.2 design, section 7): preflight,
   * stage, assemble the output from the input and the staged files, check it, name it and write its
   * record. The runtime must be turned towards the WAR (its unpacked copy is the webapp); the input
   * WAR is never modified, and there is no service, no snapshot and no rollback.
   */
  private Plan planApplyWar(ApplyArgs args) {
    WarSteps.Target target =
        new WarSteps.Target(
            args.war().orElseThrow(), args.out().orElseThrow(), rt.settings().webappName());
    Prepared p =
        prepareApply(
            args,
            "written",
            Files.exists(target.out()) ? List.of(target.out() + " exists") : List.of());
    ApplyInput in = p.in();
    List<String> warnings = p.warnings();
    long outside = in.targets().stream().filter(t -> target.pathOf(t).isEmpty()).count();
    if (outside > 0) {
      warnings.add(
          outside
              + " file(s) of the package belong to the installation tree (js-install.zip), not to"
              + " a WAR, and are left out; apply them on the server the WAR is deployed to");
    }
    warnings.add(
        "no server is touched: "
            + target.war().getFileName()
            + " is read, "
            + target.out().getFileName()
            + " is written; the package's files go into the home as the hotfix's baseline");

    List<Step> steps = new ArrayList<>();
    steps.add(new WarSteps.Preflight(rt, in, target));
    steps.add(new ApplyPhaseSteps.StageFiles(rt, in));
    steps.add(new WarSteps.Assemble(rt, in, target));
    steps.add(new WarSteps.Check(rt, in, target));
    steps.add(new WarSteps.WriteOut(rt, in, target));

    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(ApplySteps.VERIFY, "nothing written");
    rollbackPoints.put(ApplySteps.APPLY, "staging removed; the input WAR was never modified");
    rollbackPoints.put(WarSteps.PHASE_ASSEMBLE, "the temporary output removed");
    rollbackPoints.put(ApplySteps.RECORD, "the output and its record removed");
    PlanSummary summary =
        new PlanSummary(
            APPLY_WAR,
            in.contents().id() + " into " + target.out().getFileName(),
            in.touched(),
            List.of(),
            false,
            List.of(),
            rollbackPoints,
            "official-package",
            warnings,
            p.changes());

    Map<String, String> inputs = p.inputs();
    inputs.put("war", FileTarget.hashOf(rt.files(), target.war()).orElse("absent"));
    inputs.put("out", target.out().toString());
    return new Plan(
        "hotfix-apply-war-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  /** The fingerprint input that names the merge an apply was planned with. */
  public static final String MERGE_INPUT = "merge";

  /** The fingerprint input that holds the hash of that merge's {@code merge.json}. */
  public static final String MERGE_DOC_INPUT = "merge.json";

  /**
   * Reads an official package against this installation; refuses (exit 2) a file that is absent or
   * unreadable and (exit 6) one that is not an official package. Writes nothing.
   */
  public PackageContents readPackage(Path packageFile) {
    return readPackage(packageFile, SiteDecisions.NONE, Optional.empty());
  }

  private PackageContents readPackage(Path packageFile, SiteDecisions decisions, ApplyArgs args) {
    return readPackage(packageFile, decisions, Optional.of(args));
  }

  /**
   * The webapp paths known to be the vendor's: every file of every baseline, and every file the
   * latest apply wrote under the webapp (0.6 design, section 5). A library among them that the
   * package supersedes is deleted; one outside them is the site's and stays.
   */
  static Set<String> vendorFiles(HotfixRuntime rt) {
    Set<String> known = new HashSet<>();
    for (BaselineManifest b : rt.baselines().list()) {
      b.files().forEach(f -> known.add(f.path()));
    }
    Path webapp = rt.settings().webappDir();
    for (OwnedFile f : rt.undo().read().map(UndoRecord::files).orElse(List.of())) {
      Path p = f.path().toAbsolutePath().normalize();
      if (p.startsWith(webapp)) {
        known.add(webapp.relativize(p).toString().replace('\\', '/'));
      }
    }
    return known;
  }

  private PackageContents readPackage(
      Path packageFile, SiteDecisions decisions, Optional<ApplyArgs> args) {
    Path file = packageFile.toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      throw new HotfixException(HotfixException.PRECHECK, file + " does not exist", AS_PUBLISHED);
    }
    if (!OfficialPackage.looksOfficial(file)) {
      throw new HotfixException(
          HotfixException.UNSUPPORTED, file + OfficialPackage.NEITHER_SHAPE_SHORT, AS_PUBLISHED);
    }
    boolean keep = args.map(ApplyArgs::keepSuperseded).orElse(false);
    OfficialPackage.Superseded superseded =
        new OfficialPackage.Superseded(vendorFiles(rt)::contains, !keep);
    try {
      return OfficialPackage.read(
          file, rt.paths(), rt.settings().webappName(), decisions, superseded);
    } catch (IOException | UncheckedIOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot read " + file + ": " + e.getMessage(),
          "check the download; it must be the ZIP as support published it",
          e);
    }
  }

  /** The merge of {@code id}; refuses (exit 2) an id this home does not have. */
  public MergeDoc merge(String id) {
    return rt.merges()
        .load(id)
        .orElseThrow(
            () ->
                new HotfixException(
                    HotfixException.PRECHECK,
                    "unknown merge " + id,
                    "run `jrs-hotfix merge list` for the merges in this home"));
  }

  /** The webapp paths of {@code doc} that are no longer as it found them, nor as it leaves them. */
  public List<String> mergeChangedSince(MergeDoc doc) {
    return new MergePlans(rt).changedSince(doc);
  }

  /** The hotfix the latest apply installed with merge {@code id}, if it did. */
  public Optional<String> installedWith(String id) {
    return rt.undo().read().filter(u -> u.mergeId().equals(Optional.of(id))).map(UndoRecord::id);
  }

  /** The runtime these plans are built over. */
  public HotfixRuntime runtime() {
    return rt;
  }

  /** Where a file of a package lands, as the plan preview groups them. */
  private enum Area {
    WEBAPP_LIBRARIES("webapp libraries"),
    WEBAPP_SETTINGS("webapp settings"),
    WEBAPP_OTHER("webapp scripts and pages"),
    INSTALLATION("installation tree");

    private final String label;

    Area(String label) {
      this.label = label;
    }

    static Area of(String packagePath) {
      String p = packagePath.toLowerCase(Locale.ROOT);
      if (!p.startsWith(PackagePaths.WEBAPPS_PREFIX)) {
        return INSTALLATION;
      }
      if (p.contains("/web-inf/lib/")) {
        return WEBAPP_LIBRARIES;
      }
      return p.endsWith(".xml") || p.endsWith(".properties") ? WEBAPP_SETTINGS : WEBAPP_OTHER;
    }
  }

  /**
   * What the apply plan does to its files, for the preview: the totals, then one line per area that
   * has files, then where every path can be read.
   */
  private static List<String> applyChanges(List<FileTarget> targets) {
    Map<Area, int[]> byArea = new EnumMap<>(Area.class);
    int[] total = new int[3];
    for (FileTarget t : targets) {
      int[] counts = byArea.computeIfAbsent(Area.of(t.packagePath()), a -> new int[3]);
      int action =
          switch (t.action()) {
            case ADD -> 0;
            case REPLACE -> 1;
            case DELETE -> 2;
          };
      counts[action]++;
      total[action]++;
    }
    List<String> out = new ArrayList<>();
    out.add(total[0] + " added, " + total[1] + " replaced, " + total[2] + " deleted");
    int width = byArea.keySet().stream().mapToInt(a -> a.label.length()).max().orElse(0);
    for (Map.Entry<Area, int[]> e : byArea.entrySet()) {
      int[] c = e.getValue();
      List<String> parts = new ArrayList<>();
      if (c[0] > 0) {
        parts.add(c[0] + " added");
      }
      if (c[1] > 0) {
        parts.add(c[1] + " replaced");
      }
      if (c[2] > 0) {
        parts.add(c[2] + " deleted");
      }
      out.add(
          String.format(
              Locale.ROOT,
              "%-" + width + "s  %d: %s",
              e.getKey().label,
              c[0] + c[1] + c[2],
              String.join(", ", parts)));
    }
    out.add("every path: jrs-hotfix verify <package.zip>");
    return out;
  }

  /** The preview's heading for the libraries deleted as superseded, when there are any. */
  private static List<String> supersededChanges(PackageContents contents) {
    if (contents.superseded().isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    out.add("Superseded libraries deleted (" + contents.superseded().size() + ")");
    for (String path : contents.superseded()) {
      out.add("  " + path.substring(path.lastIndexOf('/') + 1));
    }
    return out;
  }

  /** What the rollback plan does to the files of the hotfix, for the preview: the totals. */
  private static List<String> rollbackChanges(UndoRecord undo) {
    int restored = 0;
    int removed = 0;
    int putBack = 0;
    for (OwnedFile f : undo.files()) {
      switch (f.action()) {
        case "add" -> removed++;
        case "delete" -> putBack++;
        default -> restored++;
      }
    }
    return List.of(restored + " restored, " + removed + " removed, " + putBack + " put back");
  }

  /**
   * What a rollback uses up, stored with the run so the plan can be rebuilt: the apply run whose
   * undo it restores.
   */
  public record RollbackArgs(String undoRunId) {
    public RollbackArgs {
      Objects.requireNonNull(undoRunId, "undoRunId");
    }
  }

  /**
   * The rollback of the latest apply (0.6 design, section 3): stop, restore the undo's snapshot,
   * clear the JSP cache, start, wait, use the undo up. Refuses (exit 2) when there is nothing to
   * undo; the first stop refuses before the outage when the snapshot is damaged or a file changed
   * since the apply.
   */
  public Plan planRollback() {
    UndoRecord undo =
        rt.undo()
            .read()
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        "there is nothing to undo: no hotfix has been applied with jrs-hotfix since"
                            + " the last rollback",
                        "run `jrs-hotfix list` for the build this server states"));
    return planRollback(undo);
  }

  /**
   * The rollback that uses up the undo of run {@code args.undoRunId()}, for recheck and recovery.
   */
  public Plan planRollback(RollbackArgs args) {
    UndoRecord undo =
        rt.undo()
            .find(args.undoRunId())
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        "the undo of run "
                            + args.undoRunId()
                            + " is gone: an apply or a rollback has run since",
                        "run `jrs-hotfix list`; a rollback undoes the latest apply only"));
    return planRollback(undo);
  }

  private Plan planRollback(UndoRecord undo) {
    RollbackSteps.Input in = new RollbackSteps.Input(undo);
    RollbackSteps.RestoreSnapshot restore = new RollbackSteps.RestoreSnapshot(rt, in);
    List<Step> steps = new ArrayList<>();
    steps.add(RollbackSteps.stop(rt, in, restore));
    steps.add(restore);
    steps.add(new JspCacheStep(rt, RollbackSteps.PHASE, JspCacheStep.ID));
    steps.add(ServiceSteps.start(rt, RollbackSteps.PHASE, ServiceSteps.START));
    steps.add(ServiceSteps.waitForServer(rt, RollbackSteps.PHASE, ServiceSteps.WAIT));
    steps.add(new RollbackSteps.DiscardUndo(rt, in));

    List<String> warnings = new ArrayList<>();
    List<String> changed = RollbackSteps.changedSinceApply(rt, undo);
    if (!changed.isEmpty()) {
      warnings.add(
          "this plan will be refused before anything is changed: "
              + RollbackSteps.changedProblem(undo, changed));
    }
    Map<String, String> fingerprint = new LinkedHashMap<>();
    fingerprint.put("settings", rt.settings().fingerprintInput());
    fingerprint.put("hotfix:" + undo.id(), undo.runId());
    for (OwnedFile f : undo.files()) {
      fingerprint.put("file:" + f.path(), FileTarget.hashOf(rt.files(), f.path()).orElse("absent"));
    }
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(
        RollbackSteps.PHASE, "re-apply from the pre-rollback snapshot, restart service");
    PlanSummary summary =
        new PlanSummary(
            ROLLBACK,
            undo.id(),
            in.touched(),
            List.of(),
            true,
            List.of(rt.home().undo()),
            rollbackPoints,
            "snapshot",
            warnings,
            rollbackChanges(undo));
    return new Plan(
        "hotfix-rollback-" + RunIds.next(rt.clock()),
        steps,
        summary,
        PlanFingerprint.of(fingerprint));
  }

  /**
   * What {@code verify} found: whether the package could be read, what it is, whether it applies
   * here and why not, and the files it would add, replace and delete. Invariant: lists are
   * immutable; an unreadable package has empty identity fields and its reason in {@code problems}.
   */
  public record VerifyReport(
      boolean readable,
      String id,
      String title,
      String release,
      String edition,
      boolean applicable,
      List<String> problems,
      List<String> adds,
      List<String> replaces,
      List<String> deletes,
      List<String> notes) {
    public VerifyReport {
      problems = List.copyOf(problems);
      adds = List.copyOf(adds);
      replaces = List.copyOf(replaces);
      deletes = List.copyOf(deletes);
      notes = List.copyOf(notes);
    }

    public boolean ok() {
      return readable && applicable;
    }
  }

  /** Reads a package and checks it against this installation; writes nothing, never throws. */
  public VerifyReport verify(Path packageFile) {
    Path file = packageFile.toAbsolutePath().normalize();
    if (!Files.isRegularFile(file) || !OfficialPackage.looksOfficial(file)) {
      return unreadable(file + OfficialPackage.NEITHER_SHAPE_SHORT);
    }
    PackageContents c;
    try {
      // as the apply reads it: what the undo and the baselines know decides the superseded ones
      c = readPackage(file);
    } catch (HotfixException e) {
      return unreadable(e.getMessage());
    } catch (UncheckedIOException e) {
      return unreadable("cannot read " + file + ": " + e.getMessage());
    }
    List<String> problems;
    try {
      problems = applicability(rt, c, FileTarget.resolve(c, rt.paths(), rt.files()));
    } catch (UncheckedIOException e) {
      return unreadable("cannot read this installation: " + e.getMessage());
    }
    return new VerifyReport(
        true,
        c.id(),
        c.title(),
        c.release(),
        c.edition(),
        problems.isEmpty(),
        problems,
        paths(c.adds()),
        paths(c.replaces()),
        paths(c.deletes()),
        notes(c, asApplied(c)));
  }

  /**
   * What is said about a package beyond its own notes: what the build the webapp states implies,
   * and, when the latest apply installed it, which of its files were merged with this site's or
   * left as the site has them. A merged file that still has the hash the apply wrote is as it
   * should be, not a mismatch with the package.
   */
  private List<String> asApplied(PackageContents c) {
    List<String> out = new ArrayList<>(buildWarnings(rt, c));
    Optional<UndoRecord> entry = rt.undo().read().filter(u -> u.id().equals(c.id()));
    if (entry.isEmpty()) {
      return out;
    }
    for (OwnedFile f : entry.get().files()) {
      if (f.wasMerged()) {
        out.add(
            FileTarget.hashOf(rt.files(), f.path()).equals(f.afterSha256())
                ? f.path() + ": merged by jrs-hotfix with this site's file"
                : f.path() + ": merged by jrs-hotfix, and changed since");
      }
    }
    for (UndoRecord.KeptFile k : entry.get().kept()) {
      out.add(k.path() + ": kept as the site has it (" + k.reason() + ")");
    }
    return out;
  }

  private static List<String> notes(PackageContents c, List<String> warnings) {
    List<String> notes = new ArrayList<>(warnings);
    notes.addAll(c.noteLines());
    return notes;
  }

  private static VerifyReport unreadable(String problem) {
    return new VerifyReport(
        false, "", "", "", "", false, List.of(problem), List.of(), List.of(), List.of(), List.of());
  }

  private static List<String> paths(List<PackageContents.Entry> entries) {
    return entries.stream().map(PackageContents.Entry::path).toList();
  }

  /**
   * Why the package does not apply here, empty when it does: the installed release must equal the
   * package's, the edition must match the webapp name, and the build the webapp states must be
   * older than the package's ({@link BuildCheck}); when the webapp states none, the package must
   * not be in place already, file by file. Shared by {@code verify} and the apply plan's preflight;
   * {@code targets} are the package's files as they were on disk when the caller resolved them.
   */
  static List<String> applicability(HotfixRuntime rt, PackageContents c, List<FileTarget> targets) {
    List<String> problems = new ArrayList<>();
    String installed = JrsVersion.ofWebapp(rt.settings().webappDir()).orElse("");
    if (!installed.equals(c.release())) {
      problems.add(
          "the package is for release "
              + c.release()
              + " but "
              + rt.settings().webappDir()
              + " is "
              + (installed.isEmpty() ? "unknown" : installed));
    }
    boolean pro = rt.settings().webappName().endsWith("-pro");
    if (c.edition().equals("PRO") != pro) {
      problems.add(
          "the package is for the "
              + c.edition()
              + " edition but the webapp is "
              + rt.settings().webappName());
    }
    problems.addAll(c.conflicts());
    if (problems.isEmpty()) {
      problems.addAll(BuildCheck.of(rt.settings().webappDir(), c).problems());
    }
    if (problems.isEmpty() && inPlace(targets)) {
      problems.add(
          c.id()
              + " is already on this server: every file of the package is in place with the"
              + " package's content and nothing is left to delete");
    }
    return problems;
  }

  /** What the build the webapp states says without refusing the package: that it is unreadable. */
  static List<String> buildWarnings(HotfixRuntime rt, PackageContents c) {
    return BuildCheck.of(rt.settings().webappDir(), c).warnings();
  }

  /**
   * True when applying would change nothing: every add and replace is on disk at the hash the
   * package would leave there, and no deletion is left. An empty list is not "in place".
   */
  static boolean inPlace(List<FileTarget> targets) {
    return !targets.isEmpty()
        && targets.stream()
            .allMatch(t -> t.action() != Action.DELETE && t.before().equals(t.after()));
  }

  /** The latest apply, which {@code rollback} would undo; empty when there is nothing to undo. */
  public Optional<UndoRecord> undo() {
    return rt.undo().read();
  }

  /**
   * The package readme's manual steps carried in {@code plan}'s summary warnings, in order and
   * whole, with {@link #NOTE_PREFIX} and {@link #QUOTE_PREFIX} stripped; empty for a rollback plan,
   * which never adds any. Never executed by this tool.
   */
  public static List<String> notesOf(Plan plan) {
    List<String> notes = new ArrayList<>();
    for (String warning : plan.summary().warnings()) {
      if (warning.startsWith(NOTE_PREFIX)) {
        notes.add(warning.substring(NOTE_PREFIX.length()));
      } else if (warning.startsWith(QUOTE_PREFIX)) {
        notes.add(warning.substring(QUOTE_PREFIX.length()));
      }
    }
    return notes;
  }

  /**
   * Rebuilds the plan a run was started with from its stored operation and arguments, so recovery
   * can compare fingerprints.
   *
   * @throws IllegalArgumentException for an operation this tool does not plan
   */
  public Plan rebuild(String operation, String argsJson) {
    return switch (operation) {
      case APPLY, APPLY_WAR -> planApply(applyArgs(argsJson));
      case ROLLBACK -> planRollback(rollbackArgs(argsJson));
      default -> throw new IllegalArgumentException("unknown operation " + operation);
    };
  }

  public static String applyArgsJson(ApplyArgs a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("packageFile", a.packageFile().toString());
    m.put("checksumConfirmed", a.checksumConfirmed());
    a.mergeId().ifPresent(id -> m.put("mergeId", id));
    a.war().ifPresent(w -> m.put("war", w.toString()));
    a.out().ifPresent(o -> m.put("out", o.toString()));
    if (a.keepSuperseded()) {
      m.put("keepSuperseded", true);
    }
    return Json.write(m);
  }

  public static ApplyArgs applyArgs(String json) {
    JsonNode n;
    try {
      n = Json.mapper().readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the apply arguments: " + e.getMessage(), e);
    }
    return new ApplyArgs(
        Path.of(n.get("packageFile").asText()),
        n.get("checksumConfirmed").asBoolean(),
        n.hasNonNull("mergeId") ? Optional.of(n.get("mergeId").asText()) : Optional.empty(),
        n.hasNonNull("war") ? Optional.of(Path.of(n.get("war").asText())) : Optional.empty(),
        n.hasNonNull("out") ? Optional.of(Path.of(n.get("out").asText())) : Optional.empty(),
        n.path("keepSuperseded").asBoolean(false));
  }

  public static String rollbackArgsJson(RollbackArgs a) {
    return Json.write(Map.of("undoRunId", a.undoRunId()));
  }

  public static RollbackArgs rollbackArgs(String json) {
    JsonNode n;
    try {
      n = Json.mapper().readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the rollback arguments: " + e.getMessage(), e);
    }
    if (!n.hasNonNull("undoRunId")) {
      // a rollback stored by 0.1 to 0.5 names a hotfix and a chain from the ledger
      throw new HotfixException(
          HotfixException.PRECHECK,
          "this rollback was started by jrs-hotfix 0.5 or earlier",
          "finish or undo it with that version (`runs resume` or `runs rollback`), then use this"
              + " one");
    }
    return new RollbackArgs(n.get("undoRunId").asText());
  }
}

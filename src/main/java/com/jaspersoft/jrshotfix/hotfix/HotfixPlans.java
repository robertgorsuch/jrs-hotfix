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
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.Ledger;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.Origin;
import com.jaspersoft.jrshotfix.state.OwnedFile;
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

  /** The run id of a ledger entry written by {@link #record}: no run installed it. */
  public static final String RECORDED_RUN_ID = "recorded";

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
      Optional<Path> out) {
    public ApplyArgs {
      Objects.requireNonNull(packageFile, "packageFile");
      Objects.requireNonNull(mergeId, "mergeId");
      Objects.requireNonNull(war, "war");
      Objects.requireNonNull(out, "out");
      if (war.isPresent() != out.isPresent()) {
        throw new IllegalArgumentException("--war and --out go together");
      }
    }

    public ApplyArgs(Path packageFile, boolean checksumConfirmed, Optional<String> mergeId) {
      this(packageFile, checksumConfirmed, mergeId, Optional.empty(), Optional.empty());
    }

    public ApplyArgs(Path packageFile, boolean checksumConfirmed) {
      this(packageFile, checksumConfirmed, Optional.empty());
    }

    /** These arguments with a WAR as the target. */
    public ApplyArgs intoWar(Path warFile, Path outFile) {
      return new ApplyArgs(
          packageFile, checksumConfirmed, mergeId, Optional.of(warFile), Optional.of(outFile));
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
   * JSP cache, start, wait, record.
   */
  public Plan planApply(ApplyArgs args) {
    if (args.war().isPresent()) {
      return planApplyWar(args);
    }
    Path file = args.packageFile().toAbsolutePath().normalize();
    MergePlans merges = new MergePlans(rt);
    Optional<MergeDoc> merge = args.mergeId().map(merges::forApply);
    PackageContents contents =
        merge.isPresent() ? readPackage(file, merges.decisions(merge.get())) : readPackage(file);
    merge.ifPresent(m -> merges.check(m, contents));
    List<FileTarget> targets = FileTarget.resolve(contents, rt.paths(), rt.files());
    ApplyInput in = new ApplyInput(file, contents, rt.paths(), targets, merge);
    List<String> warnings = new ArrayList<>();
    // the preview runs no step, so what preflight will refuse is said here, first
    for (String problem : applicability(rt, contents, targets)) {
      warnings.add("this plan will be refused before anything is changed: " + problem);
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
    Scan.externalAuthWarning(contents, rt.settings().webappDir()).ifPresent(warnings::add);
    warnings.add(
        "the service is stopped for the swap; this node only, other cluster nodes are not touched");
    List<String> changes = new ArrayList<>(applyChanges(targets));
    merge.ifPresent(m -> changes.addAll(MergePlans.changes(m)));

    List<Step> steps = new ArrayList<>();
    steps.add(new ApplySteps.Preflight(rt, in));
    steps.add(new BackupSteps.TakeSnapshot(rt, in));
    steps.add(new ApplyPhaseSteps.StageFiles(rt, in));
    steps.add(ServiceSteps.stop(rt, ApplySteps.APPLY, ServiceSteps.STOP));
    steps.add(new ApplyPhaseSteps.AtomicSwap(rt, in));
    steps.add(new JspCacheStep(rt, ApplySteps.APPLY, JspCacheStep.ID));
    steps.add(ServiceSteps.start(rt, ApplySteps.APPLY, ServiceSteps.START));
    steps.add(ServiceSteps.waitForServer(rt, ApplySteps.APPLY, ServiceSteps.WAIT));
    steps.add(new RecordSteps.RecordInstalled(rt, in));

    Path snapshotDir = rt.home().snapshots().resolve("{runId}").resolve(ApplySteps.SNAPSHOT);
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(ApplySteps.VERIFY, "nothing mutated");
    rollbackPoints.put(ApplySteps.BACKUP, "snapshot written, server untouched");
    rollbackPoints.put(ApplySteps.APPLY, "restore " + snapshotDir + ", restart service");
    rollbackPoints.put(
        ApplySteps.RECORD,
        "restore " + snapshotDir + ", restart service, ledger entry marked rolled back");
    PlanSummary summary =
        new PlanSummary(
            APPLY,
            contents.id() + " " + contents.title(),
            in.touched(),
            List.of(),
            true,
            List.of(snapshotDir),
            rollbackPoints,
            "official-package",
            warnings,
            changes);

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
    return new Plan(
        "hotfix-apply-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  /**
   * The five-step plan that hotfixes a WAR instead of a server (0.2 design, section 7): preflight,
   * stage, assemble the output from the input and the staged files, check it, name it and write its
   * record. The runtime must be turned towards the WAR (its unpacked copy is the webapp); the input
   * WAR is never modified, and there is no service, no snapshot and no rollback.
   */
  private Plan planApplyWar(ApplyArgs args) {
    Path file = args.packageFile().toAbsolutePath().normalize();
    WarSteps.Target target =
        new WarSteps.Target(
            args.war().orElseThrow(), args.out().orElseThrow(), rt.settings().webappName());
    MergePlans merges = new MergePlans(rt);
    Optional<MergeDoc> merge = args.mergeId().map(merges::forApply);
    PackageContents contents =
        merge.isPresent() ? readPackage(file, merges.decisions(merge.get())) : readPackage(file);
    merge.ifPresent(m -> merges.check(m, contents));
    List<FileTarget> targets = FileTarget.resolve(contents, rt.paths(), rt.files());
    ApplyInput in = new ApplyInput(file, contents, rt.paths(), targets, merge);
    List<String> warnings = new ArrayList<>();
    for (String problem : applicability(rt, contents, targets)) {
      warnings.add("this plan will be refused before anything is written: " + problem);
    }
    if (Files.exists(target.out())) {
      warnings.add(
          "this plan will be refused before anything is written: " + target.out() + " exists");
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
    long outside = targets.stream().filter(t -> target.pathOf(t).isEmpty()).count();
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
            + " is written beside its record "
            + target.sidecar().getFileName());
    List<String> changes = new ArrayList<>(applyChanges(targets));
    merge.ifPresent(m -> changes.addAll(MergePlans.changes(m)));

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
            contents.id() + " into " + target.out().getFileName(),
            in.touched(),
            List.of(),
            false,
            List.of(),
            rollbackPoints,
            "official-package",
            warnings,
            changes);

    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("package", contents.sha256());
    inputs.put("settings", rt.settings().fingerprintInput());
    inputs.put("installed", JrsVersion.ofWebapp(rt.settings().webappDir()).orElse("unknown"));
    inputs.put("war", FileTarget.hashOf(rt.files(), target.war()).orElse("absent"));
    inputs.put("out", target.out().toString());
    for (FileTarget t : targets) {
      inputs.put("target:" + t.packagePath(), t.before().orElse("absent"));
      if (t.entry().merged()) {
        inputs.put("merged:" + t.packagePath(), t.after().orElse("absent"));
      }
    }
    if (merge.isPresent()) {
      inputs.put(MERGE_INPUT, merge.get().id());
      inputs.put(
          MERGE_DOC_INPUT,
          FileTarget.hashOf(rt.files(), rt.merges().docFile(merge.get().id())).orElse("absent"));
    }
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
    return readPackage(packageFile, SiteDecisions.NONE);
  }

  private PackageContents readPackage(Path packageFile, SiteDecisions decisions) {
    Path file = packageFile.toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      throw new HotfixException(HotfixException.PRECHECK, file + " does not exist", AS_PUBLISHED);
    }
    if (!OfficialPackage.looksOfficial(file)) {
      throw new HotfixException(
          HotfixException.UNSUPPORTED, file + OfficialPackage.NEITHER_SHAPE_SHORT, AS_PUBLISHED);
    }
    try {
      return OfficialPackage.read(
          file, rt.paths(), rt.settings().webappName(), rt.files(), decisions);
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
                    "run `jrs-hotfix merge status` for the merges in this home"));
  }

  /** The webapp paths of {@code doc} that are no longer as it found them, nor as it leaves them. */
  public List<String> mergeChangedSince(MergeDoc doc) {
    return new MergePlans(rt).changedSince(doc);
  }

  /** The installed hotfix that was applied with merge {@code id}, if any. */
  public Optional<String> installedWith(String id) {
    return rt.ledger().installed().stream()
        .filter(e -> e.mergeId().equals(Optional.of(id)))
        .map(LedgerEntry::id)
        .findFirst();
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

  /** What the rollback plan does to the files of its hotfixes, for the preview: the totals. */
  private static List<String> rollbackChanges(List<RollbackSteps.Input> inputs) {
    int restored = 0;
    int removed = 0;
    int putBack = 0;
    for (RollbackSteps.Input in : inputs) {
      for (OwnedFile f : in.hotfix().files()) {
        switch (f.action()) {
          case "add" -> removed++;
          case "delete" -> putBack++;
          default -> restored++;
        }
      }
    }
    return List.of(restored + " restored, " + removed + " removed, " + putBack + " put back");
  }

  /**
   * What {@code rollback} was asked to do; stored with the run so the plan can be rebuilt. {@code
   * chain} is the rollback order computed at plan time, newest first and the target last; empty
   * means "compute it from the ledger".
   */
  public record RollbackArgs(String hotfixId, boolean cascade, List<String> chain) {
    public RollbackArgs {
      Objects.requireNonNull(hotfixId, "hotfixId");
      chain = List.copyOf(chain);
    }

    public RollbackArgs(String hotfixId, boolean cascade) {
      this(hotfixId, cascade, List.of());
    }
  }

  /** A rollback plan and the arguments that rebuild exactly it: {@code args.chain()} is filled. */
  public record ResolvedRollback(Plan plan, RollbackArgs args) {
    public ResolvedRollback {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(args, "args");
    }
  }

  /**
   * Per hotfix, newest first and the target last: stop, restore the snapshot, clear the JSP cache,
   * start, wait, mark rolled back. Refuses a recorded entry, as target or anywhere in the chain
   * (nothing to restore), and, unless cascading, a hotfix whose files a later installed hotfix also
   * owns. The first stop refuses before the outage when any snapshot of the chain is missing.
   */
  public Plan planRollback(RollbackArgs args) {
    return resolveRollback(args).plan();
  }

  /**
   * As {@link #planRollback}, also returning the arguments with the computed chain, which is what a
   * run must store. With a non-empty {@code args.chain()} (a rebuild for recovery) the state-based
   * refusals are skipped, because the partial run may already have changed the ledger, and the
   * chain is used as given; every id must still have a ledger entry.
   */
  public ResolvedRollback resolveRollback(RollbackArgs args) {
    Ledger ledger = rt.ledger();
    List<String> chain;
    LedgerEntry target;
    if (args.chain().isEmpty()) {
      target =
          ledger
              .find(args.hotfixId())
              .orElseThrow(
                  () ->
                      new HotfixException(
                          HotfixException.PRECHECK,
                          "unknown hotfix " + args.hotfixId(),
                          "run jrs-hotfix list"));
      if (target.state() != HotfixState.INSTALLED) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            args.hotfixId() + " is not installed (state " + target.state() + ")",
            "run jrs-hotfix list");
      }
      refuseRecorded(target);
      chain = RollbackChain.of(ledger, target, args.cascade());
      for (String id : chain) {
        // a recorded entry owns files, so it can be a later blocker the cascade would take off
        refuseRecorded(ledger.find(id).orElseThrow());
      }
    } else {
      chain = args.chain();
      for (String id : chain) {
        if (ledger.find(id).isEmpty()) {
          throw new HotfixException(
              HotfixException.PRECHECK,
              id + " is no longer in the ledger",
              "the run cannot be rebuilt; restore ledger.json from a backup or remove the run"
                  + " directory by hand");
        }
      }
      target =
          ledger
              .find(args.hotfixId())
              .orElseThrow(
                  () ->
                      new HotfixException(
                          HotfixException.PRECHECK,
                          args.hotfixId() + " is no longer in the ledger",
                          "the run cannot be rebuilt; restore ledger.json from a backup or remove"
                              + " the run directory by hand"));
    }
    List<RollbackSteps.Input> inputs = new ArrayList<>();
    List<RollbackSteps.RestoreSnapshot> restores = new ArrayList<>();
    for (String id : chain) {
      LedgerEntry hotfix = ledger.find(id).orElseThrow();
      String suffix = chain.size() > 1 ? ":" + id : "";
      String phase = chain.size() > 1 ? RollbackSteps.PHASE + ":" + id : RollbackSteps.PHASE;
      RollbackSteps.Input in = new RollbackSteps.Input(hotfix, phase, suffix);
      inputs.add(in);
      restores.add(new RollbackSteps.RestoreSnapshot(rt, in));
    }

    List<Step> steps = new ArrayList<>();
    List<Path> touched = new ArrayList<>();
    List<Path> backups = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> fingerprint = new LinkedHashMap<>();
    fingerprint.put("settings", rt.settings().fingerprintInput());
    for (int i = 0; i < inputs.size(); i++) {
      RollbackSteps.Input in = inputs.get(i);
      LedgerEntry hotfix = in.hotfix();
      Step stop = ServiceSteps.stop(rt, in.phase(), ServiceSteps.STOP + in.suffix());
      // the first stop is the start of the outage: every snapshot the chain needs is checked first
      steps.add(i == 0 ? new RollbackSteps.CheckedStop(rt, stop, restores) : stop);
      steps.add(restores.get(i));
      steps.add(new JspCacheStep(rt, in.phase(), JspCacheStep.ID + in.suffix()));
      steps.add(ServiceSteps.start(rt, in.phase(), ServiceSteps.START + in.suffix()));
      steps.add(ServiceSteps.waitForServer(rt, in.phase(), ServiceSteps.WAIT + in.suffix()));
      steps.add(new RollbackSteps.RecordRolledBack(rt, in));
      touched.addAll(in.touched());
      backups.add(rt.home().snapshots().resolve(hotfix.runId()).resolve(ApplySteps.SNAPSHOT));
      fingerprint.put("hotfix:" + in.id(), hotfix.runId());
      for (OwnedFile f : hotfix.files()) {
        fingerprint.put(
            "file:" + f.path(), FileTarget.hashOf(rt.files(), f.path()).orElse("absent"));
      }
      if (!in.id().equals(target.id())) {
        warnings.add(
            in.id()
                + " is rolled back first because it owns files "
                + target.id()
                + " also owns (--cascade)");
      }
    }
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(
        RollbackSteps.PHASE, "re-apply from the pre-rollback snapshot, restart service");
    PlanSummary summary =
        new PlanSummary(
            ROLLBACK,
            String.join(", ", chain),
            touched,
            List.of(),
            true,
            backups,
            rollbackPoints,
            "snapshot",
            warnings,
            rollbackChanges(inputs));
    Plan plan =
        new Plan(
            "hotfix-rollback-" + RunIds.next(rt.clock()),
            steps,
            summary,
            PlanFingerprint.of(fingerprint));
    return new ResolvedRollback(plan, new RollbackArgs(args.hotfixId(), args.cascade(), chain));
  }

  private static void refuseRecorded(LedgerEntry hotfix) {
    if (hotfix.recorded()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          hotfix.id() + " was applied by hand and only recorded; there is no snapshot to put back",
          "remove the hotfix by hand following the vendor's readme; the entry stays as the"
              + " inventory of this server");
    }
  }

  /**
   * Takes a hotfix out of the ledger because the webapp no longer holds it: it was redeployed from
   * a WAR, or the hotfix was removed by hand. Touches nothing on the server; the entry's snapshot
   * stays until {@code runs prune}, and the hotfix's baseline stays too. Refuses (exit 2) an id the
   * ledger does not have.
   */
  public LedgerEntry forget(String id) {
    LedgerEntry entry =
        rt.ledger()
            .find(id)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        "unknown hotfix " + id,
                        "run `jrs-hotfix list` for the ids"));
    rt.ledger().delete(id);
    return entry;
  }

  /**
   * Records a hotfix applied by hand from its package. Touches nothing on the server: the entry's
   * files are the package's adds and replaces, with the hash on disk now as the before-hash and the
   * package's payload hash as the after-hash; there is no snapshot, so a recorded entry cannot be
   * rolled back by this tool. The package's files become the hotfix's baseline, so a later scan or
   * merge knows them as the vendor's.
   */
  public LedgerEntry record(Path packageFile) {
    PackageContents c = readPackage(packageFile);
    Optional<LedgerEntry> existing = rt.ledger().find(c.id());
    if (existing.isPresent()) {
      LedgerEntry h = existing.get();
      throw new HotfixException(
          HotfixException.PRECHECK,
          c.id()
              + " is already in the ledger ("
              + h.state()
              + ", "
              + (h.recorded() ? "recorded" : "applied by jrs-hotfix in run " + h.runId())
              + ")",
          "run jrs-hotfix list; a rolled-back entry must be removed with `jrs-hotfix runs prune`"
              + " before the same id is recorded again");
    }
    List<OwnedFile> files = new ArrayList<>();
    for (PackageContents.Entry e : c.entries()) {
      if (e.action() == Action.DELETE) {
        continue;
      }
      Path target = rt.paths().resolve(e.path());
      files.add(
          new OwnedFile(
              target,
              e.action().name().toLowerCase(Locale.ROOT),
              FileTarget.hashOf(rt.files(), target),
              e.sha256()));
    }
    LedgerEntry entry =
        new LedgerEntry(
            c.id(),
            c.release(),
            c.edition(),
            c.build(),
            c.title(),
            HotfixState.INSTALLED,
            Origin.RECORDED,
            RECORDED_RUN_ID,
            Optional.empty(),
            rt.clock().instant(),
            files);
    rt.ledger().recordInstalled(entry);
    try {
      rt.baselines()
          .addHotfix(packageFile.toAbsolutePath().normalize(), c, rt.settings().webappName());
    } catch (IOException | UncheckedIOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          c.id() + " is recorded, but its baseline could not be written: " + e.getMessage(),
          "check free space under "
              + rt.home().baselines()
              + ", then run `jrs-hotfix baseline add <package.zip>`",
          e);
    }
    return entry;
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
      c = OfficialPackage.read(file, rt.paths(), rt.settings().webappName(), rt.files());
    } catch (HotfixException e) {
      return unreadable(e.getMessage());
    } catch (IOException | UncheckedIOException e) {
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
   * and, when the hotfix is installed, which of its files were merged with this site's or left as
   * the site has them. A merged file that still has the hash the apply wrote is as it should be,
   * not a mismatch with the package.
   */
  private List<String> asApplied(PackageContents c) {
    List<String> out = new ArrayList<>(buildWarnings(rt, c));
    Optional<LedgerEntry> entry =
        rt.ledger().find(c.id()).filter(e -> e.state() == HotfixState.INSTALLED);
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
    for (LedgerEntry.KeptFile k : entry.get().kept()) {
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
   * package's, the edition must match the webapp name, the hotfix must not be installed already, by
   * the ledger or, when the ledger does not know it, by the build the webapp states or by the files
   * themselves, and the webapp must not be older than the ledger says ({@link BuildCheck}). Shared
   * by {@code verify} and the apply plan's preflight; {@code targets} are the package's files as
   * they were on disk when the caller resolved them.
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
    List<String> build = problems.isEmpty() ? buildCheck(rt, c).problems() : List.of();
    if (!build.isEmpty()) {
      // the build says the webapp is not what the ledger describes: "already installed" would
      // be the ledger's word against the webapp's, so only the build is reported
      problems.addAll(build);
    } else if (rt.ledger()
        .find(c.id())
        .filter(e -> e.state() == HotfixState.INSTALLED)
        .isPresent()) {
      problems.add(c.id() + " is already installed");
    } else if (problems.isEmpty() && inPlace(targets)) {
      problems.add(
          c.id()
              + " is already on this server: every file of the package is in place with the"
              + " package's content and nothing is left to delete, but the ledger does not list"
              + " it as installed; it was applied by hand or by another tool, so run `jrs-hotfix"
              + " record <package.zip>` and the ledger will know it");
    }
    return problems;
  }

  private static BuildCheck buildCheck(HotfixRuntime rt, PackageContents c) {
    Set<String> releaseBuilds = new HashSet<>();
    for (BaselineManifest b : rt.baselines().list()) {
      if (b.kind() == BaselineManifest.Kind.RELEASE && b.release().equals(c.release())) {
        releaseBuilds.add(b.build());
      }
    }
    return BuildCheck.of(rt, c, releaseBuilds);
  }

  /**
   * What the build the webapp states says without refusing the package: that it cannot be read, or
   * that a hotfix was applied outside this tool.
   */
  static List<String> buildWarnings(HotfixRuntime rt, PackageContents c) {
    return buildCheck(rt, c).warnings();
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

  /** Every ledger entry, installed and rolled back alike, in install order. */
  public List<LedgerEntry> list() {
    return rt.ledger().all();
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
        n.hasNonNull("out") ? Optional.of(Path.of(n.get("out").asText())) : Optional.empty());
  }

  public static String rollbackArgsJson(RollbackArgs a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("hotfixId", a.hotfixId());
    m.put("cascade", a.cascade());
    m.put("chain", a.chain());
    return Json.write(m);
  }

  public static RollbackArgs rollbackArgs(String json) {
    JsonNode n;
    try {
      n = Json.mapper().readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the rollback arguments: " + e.getMessage(), e);
    }
    List<String> chain = new ArrayList<>();
    for (JsonNode id : n.path("chain")) {
      chain.add(id.asText());
    }
    return new RollbackArgs(n.get("hotfixId").asText(), n.get("cascade").asBoolean(), chain);
  }
}

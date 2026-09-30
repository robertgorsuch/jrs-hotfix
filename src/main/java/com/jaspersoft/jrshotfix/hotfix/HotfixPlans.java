package com.jaspersoft.jrshotfix.hotfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.PlanFingerprint;
import com.jaspersoft.jrshotfix.engine.PlanSummary;
import com.jaspersoft.jrshotfix.engine.RunIds;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.home.JrsVersion;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.pkg.Action;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds every plan and answers every read-only question. Invariants: planning touches nothing
 * outside the home's run log; a plan is rebuilt from its arguments alone, so recovery can compare
 * fingerprints; refusals are {@link HotfixException}s with a remediation.
 */
public final class HotfixPlans {
  public static final String APPLY = "hotfix.apply";
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

  /** What {@code apply} was asked to do; stored with the run so the plan can be rebuilt. */
  public record ApplyArgs(Path packageFile, boolean checksumConfirmed) {
    public ApplyArgs {
      Objects.requireNonNull(packageFile, "packageFile");
    }
  }

  public Plan planApply(Path packageFile, boolean checksumConfirmed) {
    return planApply(new ApplyArgs(packageFile, checksumConfirmed));
  }

  /**
   * The nine-step apply plan: preflight, snapshot, stage (before the outage), stop, swap, clear the
   * JSP cache, start, wait, record.
   */
  public Plan planApply(ApplyArgs args) {
    Path file = args.packageFile().toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      throw new HotfixException(HotfixException.PRECHECK, file + " does not exist", AS_PUBLISHED);
    }
    if (!OfficialPackage.looksOfficial(file)) {
      throw new HotfixException(
          HotfixException.UNSUPPORTED, file + OfficialPackage.NEITHER_SHAPE_SHORT, AS_PUBLISHED);
    }
    PackageContents contents;
    try {
      contents = OfficialPackage.read(file, rt.paths(), rt.settings().webappName(), rt.files());
    } catch (IOException | UncheckedIOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot read " + file + ": " + e.getMessage(),
          "check the download; it must be the ZIP as support published it",
          e);
    }
    List<FileTarget> targets = FileTarget.resolve(contents, rt.paths(), rt.files());
    ApplyInput in = new ApplyInput(file, contents, rt.paths(), targets);
    List<String> warnings = new ArrayList<>();
    // the preview runs no step, so what preflight will refuse is said here, first
    for (String problem : applicability(rt, contents, targets)) {
      warnings.add("this plan will be refused before anything is changed: " + problem);
    }
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
    warnings.add(
        "the service is stopped for the swap; this node only, other cluster nodes are not touched");

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
            applyChanges(targets));

    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("package", contents.sha256());
    inputs.put("settings", rt.settings().fingerprintInput());
    inputs.put("installed", JrsVersion.ofWebapp(rt.settings().webappDir()).orElse("unknown"));
    for (FileTarget t : targets) {
      inputs.put("target:" + t.packagePath(), t.before().orElse("absent"));
    }
    return new Plan(
        "hotfix-apply-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
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
   * Records a hotfix applied by hand from its package. Touches nothing on the server: the entry's
   * files are the package's adds and replaces, with the hash on disk now as the before-hash and the
   * package's payload hash as the after-hash; there is no snapshot, so a recorded entry cannot be
   * rolled back by this tool.
   */
  public LedgerEntry record(Path packageFile) {
    Path file = packageFile.toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          file + " does not exist",
          "point jrs-hotfix record at the hotfix ZIP as support published it");
    }
    if (!OfficialPackage.looksOfficial(file)) {
      throw new HotfixException(
          HotfixException.UNSUPPORTED, file + OfficialPackage.NEITHER_SHAPE_SHORT, AS_PUBLISHED);
    }
    PackageContents c;
    try {
      c = OfficialPackage.read(file, rt.paths(), rt.settings().webappName(), rt.files());
    } catch (IOException | UncheckedIOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot read " + file + ": " + e.getMessage(),
          "check the file, then run again",
          e);
    }
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
        c.noteLines());
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
   * package's, the edition must match the webapp name, and the hotfix must not be installed
   * already, by the ledger or, when the ledger does not know it, by the files themselves. Shared by
   * {@code verify} and the apply plan's preflight; {@code targets} are the package's files as they
   * were on disk when the caller resolved them.
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
    if (rt.ledger().find(c.id()).filter(e -> e.state() == HotfixState.INSTALLED).isPresent()) {
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
      case APPLY -> planApply(applyArgs(argsJson));
      case ROLLBACK -> planRollback(rollbackArgs(argsJson));
      default -> throw new IllegalArgumentException("unknown operation " + operation);
    };
  }

  public static String applyArgsJson(ApplyArgs a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("packageFile", a.packageFile().toString());
    m.put("checksumConfirmed", a.checksumConfirmed());
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
        Path.of(n.get("packageFile").asText()), n.get("checksumConfirmed").asBoolean());
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

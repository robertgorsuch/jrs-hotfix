package com.jaspersoft.jrshotfix.hotfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.PlanFingerprint;
import com.jaspersoft.jrshotfix.engine.PlanSummary;
import com.jaspersoft.jrshotfix.engine.RunIds;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.home.JrsVersion;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.service.ServiceSteps;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.Ledger;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds every plan and answers every read-only question. Invariants: planning touches nothing
 * outside the home's run log; a plan is rebuilt from its arguments alone, so recovery can compare
 * fingerprints; refusals are {@link HotfixException}s with a remediation.
 */
public final class HotfixPlans {
  public static final String APPLY = "hotfix.apply";
  public static final String ROLLBACK = "hotfix.rollback";

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
   * The eight-step apply plan: preflight, snapshot, stage (before the outage), stop, swap, start,
   * wait, record.
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
    warnings.add(
        "package "
            + file.getFileName()
            + " (sha256 "
            + contents.sha256()
            + "); compare it with the checksum on the support portal"
            + (args.checksumConfirmed() ? " (confirmed)" : ""));
    warnings.addAll(contents.notes());
    warnings.add(
        "the service is stopped for the swap; this node only, other cluster nodes are not touched");

    List<Step> steps = new ArrayList<>();
    steps.add(new ApplySteps.Preflight(rt, in));
    steps.add(new BackupSteps.TakeSnapshot(rt, in));
    steps.add(new ApplyPhaseSteps.StageFiles(rt, in));
    steps.add(ServiceSteps.stop(rt, ApplySteps.APPLY, ServiceSteps.STOP));
    steps.add(new ApplyPhaseSteps.AtomicSwap(rt, in));
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
            warnings);

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

  /** What {@code rollback} was asked to do; stored with the run so the plan can be rebuilt. */
  public record RollbackArgs(String hotfixId, boolean cascade) {
    public RollbackArgs {
      Objects.requireNonNull(hotfixId, "hotfixId");
    }
  }

  /**
   * Per hotfix, newest first and the target last: stop, restore the snapshot, start, wait, mark
   * rolled back. Refuses a recorded entry (nothing to restore) and, unless cascading, a hotfix
   * whose files a later installed hotfix also owns. The first stop refuses before the outage when
   * any snapshot of the chain is missing.
   */
  public Plan planRollback(RollbackArgs args) {
    Ledger ledger = rt.ledger();
    LedgerEntry target =
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
    if (target.recorded()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          args.hotfixId()
              + " was applied by hand and only recorded; there is no snapshot to put back",
          "remove the hotfix by hand following the vendor's readme; the entry stays as the"
              + " inventory of this server");
    }
    List<String> chain = RollbackChain.of(ledger, target, args.cascade());
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
      steps.add(i == 0 ? new RollbackSteps.CheckedStop(stop, restores) : stop);
      steps.add(restores.get(i));
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
            warnings);
    return new Plan(
        "hotfix-rollback-" + RunIds.next(rt.clock()),
        steps,
        summary,
        PlanFingerprint.of(fingerprint));
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
    return Json.write(m);
  }

  public static RollbackArgs rollbackArgs(String json) {
    JsonNode n;
    try {
      n = Json.mapper().readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the rollback arguments: " + e.getMessage(), e);
    }
    return new RollbackArgs(n.get("hotfixId").asText(), n.get("cascade").asBoolean());
  }
}

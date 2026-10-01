package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix apply <package.zip>}. Invariants: at a terminal without {@code --yes} the
 * operator confirms the package checksum against the support portal before anything is planned, and
 * a "no" is exit 2 with nothing touched; {@code --yes} skips only the questions, never a check;
 * whether the checksum was confirmed or skipped is written to the run log; on a server with a
 * baseline the package is applied with a prepared merge, and never while a file in it waits for a
 * decision.
 */
@Command(
    name = "apply",
    mixinStandardHelpOptions = true,
    description = "Apply an official hotfix package: snapshot, stop, swap, start, record.",
    footer = {
      "",
      "Example:",
      "  jrs-hotfix apply C:\\Downloads\\hotfix_JRSPro10.0.0_20260730.zip --plan",
      "  jrs-hotfix apply <zip> --war jasperserver-pro.war --out jasperserver-pro-hotfixed.war"
    })
final class ApplyCommand extends AppCommand {

  @Parameters(index = "0", paramLabel = "<package.zip>", description = "The hotfix ZIP.")
  Path file;

  @Option(
      names = "--plan",
      description = "Show the plan and stop; nothing is changed on the server.")
  boolean plan;

  @Option(
      names = "--merge",
      paramLabel = "<mergeId>",
      description =
          "Apply with this prepared merge. Without it, on a server with a baseline, the newest"
              + " merge still valid for the package is used, or one is prepared.")
  String merge;

  @Option(
      names = "--on-conflict",
      paramLabel = "<rule>",
      description =
          "When a merge is prepared here, a properties key both changed: ask, mine, theirs or"
              + " fail. Default: the setting merge.onConflict, else ask at a terminal and fail"
              + " otherwise.")
  MergeWorkspace.OnConflict onConflict;

  @Option(
      names = "--keep-superseded",
      description =
          "Leave a library under WEB-INF/lib that is an older version of one the package brings"
              + " and that no readme list names. By default such a library is deleted when the"
              + " ledger or a baseline knows it as the vendor's; one the site added is never.")
  boolean keepSuperseded;

  @Option(
      names = "--war",
      paramLabel = "<in.war>",
      description =
          "Hotfix this WAR instead of a server: no service, no snapshot; the input is never"
              + " modified. Needs --out. The home is --home, else jrs-hotfix beside the WAR.")
  Path war;

  @Option(
      names = "--out",
      paramLabel = "<out.war>",
      description = "Where the hotfixed WAR is written.")
  Path out;

  @Override
  public Integer call() {
    if ((war == null) != (out == null)) {
      return ExitCodes.fail(
          err(), ExitCodes.USAGE, "--war and --out go together", Optional.empty());
    }
    Bootstrap boot = war == null ? open() : open().forWar(war);
    boolean confirmed = false;
    if (!global().yes() && !plan && boot.interactive() && Files.isRegularFile(file)) {
      String sha;
      try {
        sha = boot.platform().files().sha256(file);
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot read " + file + ": " + e.getMessage(),
            "check the file, then run again",
            e);
      }
      out().println("package " + file.getFileName() + "  sha256 " + sha);
      confirmed = Prompter.yes(out(), "Does this checksum match the support portal? [y/N] ", false);
      if (!confirmed) {
        return ExitCodes.fail(
            err(),
            ExitCodes.PRECHECK_FAILED,
            "checksum not confirmed; nothing has changed",
            Optional.of("download the package again from the support portal"));
      }
    }
    // with a baseline, what this site changed is compared, kept and merged before anything is
    // planned; a file that waits for the operator stops the apply here, with exit 2
    HotfixPlans plans = boot.plans();
    HotfixPlans.ApplyArgs args =
        plans.resolveApply(
            file,
            confirmed,
            Optional.ofNullable(merge),
            Optional.ofNullable(onConflict),
            MergeCommand.fallback(boot));
    if (war != null) {
      args = args.intoWar(war, out);
    }
    if (keepSuperseded) {
      args = args.keepingSuperseded();
    }
    Plan p = plans.planApply(args);
    List<String> audit =
        global().yes()
            ? List.of(HotfixPlans.AUDIT_CHECKSUM_CONFIRMED + " skipped with --yes")
            : List.of(HotfixPlans.AUDIT_CHECKSUM_CONFIRMED + " confirmed by the operator");
    return executor(boot)
        .execute(
            p,
            war == null ? HotfixPlans.APPLY : HotfixPlans.APPLY_WAR,
            HotfixPlans.applyArgsJson(args),
            plan,
            audit);
  }
}

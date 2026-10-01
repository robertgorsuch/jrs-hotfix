package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.merge.Diff;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import com.jaspersoft.jrshotfix.merge.Text;
import com.jaspersoft.jrshotfix.platform.UserPaths;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix merge}: preparing a merge of a hotfix into a customized server before the
 * outage, and resolving what it could not merge by itself. Invariants: no command here touches the
 * installation; {@code status} and {@code show} write nothing at all, the others write under the
 * home only and take the run lock; {@code status <id>} exits 0 only when no file waits for a
 * decision.
 */
@Command(
    name = "merge",
    mixinStandardHelpOptions = true,
    description =
        "Merge a hotfix with what this site changed, before the outage: prepare, see what waits"
            + " for a decision, resolve it.",
    subcommands = {
      MergeCommand.Prepare.class,
      MergeCommand.Status.class,
      MergeCommand.Show.class,
      MergeCommand.Resolve.class,
      MergeCommand.Discard.class
    })
final class MergeCommand extends GroupCommand {

  /** The conflict rule to use: the option, else the setting, else by whether someone can answer. */
  static MergeWorkspace.OnConflict fallback(Bootstrap boot) {
    return boot.settings()
        .flatMap(s -> s.mergeOnConflict())
        .map(MergeWorkspace.OnConflict::of)
        .orElse(
            boot.interactive() ? MergeWorkspace.OnConflict.ASK : MergeWorkspace.OnConflict.FAIL);
  }

  private static void printReport(PrintWriter out, Bootstrap boot, MergeDoc doc) {
    for (String line : MergeWorkspace.report(doc)) {
      out.println(boot.redactor().redact(line));
    }
  }

  private static void printNext(PrintWriter out, MergeDoc doc) {
    if (doc.blocking().isEmpty()) {
      out.println(
          "nothing waits for a decision: `jrs-hotfix apply <package.zip> --merge "
              + doc.id()
              + "` applies the hotfix with this merge");
    } else {
      out.println(
          "`jrs-hotfix merge show "
              + doc.id()
              + " <path>` shows a file's two changes; resolve each with `jrs-hotfix merge resolve "
              + doc.id()
              + " <path> --merged | --mine | --theirs`");
    }
  }

  /** {@code merge prepare <package.zip>}. */
  @Command(
      name = "prepare",
      mixinStandardHelpOptions = true,
      description =
          "Compare a hotfix package with this site's changes and merge what both changed, into a"
              + " workspace in the home. Changes nothing on the server.",
      footer = {"", "Example:", "  jrs-hotfix merge prepare <zip> --on-conflict ask"})
  static final class Prepare extends AppCommand {
    @Parameters(index = "0", paramLabel = "<package.zip>", description = "The hotfix ZIP.")
    Path file;

    @Option(
        names = "--on-conflict",
        paramLabel = "<rule>",
        description =
            "A properties key both changed: ask (the file waits for you), mine, theirs, or fail"
                + " (as ask, and exit 2). Default: the setting merge.onConflict, else ask at a"
                + " terminal and fail otherwise.")
    MergeWorkspace.OnConflict onConflict;

    @Option(
        names = "--war",
        paramLabel = "<file.war>",
        description =
            "Merge into this WAR instead of the server; the same WAR is then given to `apply"
                + " --war`. The home is --home, else jrs-hotfix beside the WAR.")
    Path war;

    @Override
    public Integer call() {
      Bootstrap boot = war == null ? open() : open().forWar(war);
      return executor(boot).mutate("merge prepare", () -> prepare(boot));
    }

    private int prepare(Bootstrap boot) {
      MergeWorkspace.OnConflict fallback = fallback(boot);
      MergeDoc doc =
          boot.plans().prepareMerge(file, Optional.ofNullable(onConflict), fallback, false);
      PrintWriter out = out();
      printReport(out, boot, doc);
      out.println("workspace: " + boot.runtime().merges().dir(doc.id()));
      printNext(out, doc);
      out.flush();
      MergeWorkspace.OnConflict used = onConflict == null ? fallback : onConflict;
      return used == MergeWorkspace.OnConflict.FAIL && !doc.blocking().isEmpty()
          ? ExitCodes.PRECHECK_FAILED
          : ExitCodes.SUCCESS;
    }
  }

  /** {@code merge status [<mergeId>]}. */
  @Command(
      name = "status",
      mixinStandardHelpOptions = true,
      description =
          "Without an id, list the merges in the home. With one, list its files and their states;"
              + " exit 0 when none waits for a decision, 2 otherwise.",
      footer = {"", "Example:", "  jrs-hotfix merge status <id>"})
  static final class Status extends AppCommand {
    @Parameters(index = "0", arity = "0..1", paramLabel = "<mergeId>", description = "The merge.")
    String id;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      HotfixPlans plans = boot.plans();
      PrintWriter out = out();
      if (id == null) {
        List<MergeDoc> all = plans.runtime().merges().list();
        if (all.isEmpty()) {
          out.println("no merges; `jrs-hotfix merge prepare <package.zip>` prepares one");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        TextTable table = table();
        table.row("ID", "HOTFIX", "PREPARED", "FILES", "WAITING");
        for (MergeDoc doc : all) {
          table.row(
              doc.id(),
              doc.hotfixId(),
              doc.createdAt().toString(),
              String.valueOf(doc.files().size()),
              String.valueOf(doc.blocking().size()));
        }
        table.printTo(out);
        out.flush();
        return ExitCodes.SUCCESS;
      }
      MergeDoc doc = plans.merge(id);
      printReport(out, boot, doc);
      List<String> changed = plans.mergeChangedSince(doc);
      if (!changed.isEmpty()) {
        out.println(
            "! changed on the server since this merge was prepared: "
                + String.join(", ", changed)
                + "; prepare it again before applying");
      }
      printNext(out, doc);
      out.flush();
      return doc.blocking().isEmpty() ? ExitCodes.SUCCESS : ExitCodes.PRECHECK_FAILED;
    }
  }

  /** {@code merge show <mergeId> <path>}. */
  @Command(
      name = "show",
      mixinStandardHelpOptions = true,
      description =
          "Show one file of a merge: what this site changed, what the hotfix changed, and the"
              + " merged text where there is one.",
      footer = {"", "Example:", "  jrs-hotfix merge show <id> WEB-INF/web.xml"})
  static final class Show extends AppCommand {
    @Parameters(index = "0", paramLabel = "<mergeId>", description = "The merge.")
    String id;

    @Parameters(index = "1", paramLabel = "<path>", description = "The file, as status lists it.")
    String path;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      HotfixPlans plans = boot.plans();
      MergeDoc doc = plans.merge(id);
      MergeDoc.Item item = item(doc, path);
      MergeWorkspace merges = plans.runtime().merges();
      List<String> lines = new ArrayList<>();
      lines.add(item.path() + "  " + item.state() + "  (" + item.verdict() + ")");
      if (!item.note().isEmpty()) {
        lines.add("  " + item.note());
      }
      item.checks().forEach(c -> lines.add("  ! " + c));
      Optional<List<String>> base = read(merges.side(id, item.path(), MergeWorkspace.BASE));
      Optional<List<String>> mine = read(merges.side(id, item.path(), MergeWorkspace.MINE));
      Optional<List<String>> theirs = read(merges.side(id, item.path(), MergeWorkspace.THEIRS));
      if (theirs.isEmpty() && mine.isEmpty()) {
        lines.add("  this file needed no merge, so its content was not copied into the workspace");
      } else {
        List<String> was = base.orElse(List.of());
        lines.add("");
        lines.add(
            "What this site changed" + (base.isEmpty() ? " (the vendor had no such file)" : ""));
        lines.addAll(diff("base", "mine (on the server)", was, mine));
        lines.add("");
        lines.add("What the hotfix changed");
        lines.addAll(diff("base", "theirs (the hotfix)", was, theirs));
        Path merged = merges.side(doc.id(), item.path(), MergeWorkspace.MERGED);
        if (Files.isRegularFile(merged)) {
          lines.add("");
          lines.add("The merged file: " + merged);
          lines.addAll(
              diff("mine (on the server)", "merged", mine.orElse(List.of()), read(merged)));
        }
      }
      StringBuilder text = new StringBuilder();
      for (String line : lines) {
        text.append(boot.redactor().redact(line)).append(System.lineSeparator());
      }
      out().print(text);
      out().flush();
      return ExitCodes.SUCCESS;
    }

    private static Optional<List<String>> read(Path file) {
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      try {
        return Optional.of(Text.of(Files.readAllBytes(file)).lines());
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot read " + file + ": " + e.getMessage(),
            "check the merge's directory",
            e);
      }
    }

    private static List<String> diff(
        String nameA, String nameB, List<String> a, Optional<List<String>> b) {
      if (b.isEmpty()) {
        return List.of("  (no such file)");
      }
      List<String> diff = Diff.unified(nameA, nameB, a, b.get());
      return diff.isEmpty() ? List.of("  (nothing)") : diff;
    }
  }

  private static MergeDoc.Item item(MergeDoc doc, String path) {
    String wanted = path.replace('\\', '/');
    return doc.file(wanted)
        .orElseThrow(
            () ->
                new HotfixException(
                    HotfixException.PRECHECK,
                    "merge " + doc.id() + " has no file " + path,
                    "run `jrs-hotfix merge status " + doc.id() + "` for the paths"));
  }

  /** {@code merge resolve <mergeId> <path> --merged [<file>] | --mine | --theirs}. */
  @Command(
      name = "resolve",
      mixinStandardHelpOptions = true,
      description =
          "Decide one file of a merge: --merged takes the merged file of the workspace (or the"
              + " file given) once it passes its checks, --mine keeps the server's file, --theirs"
              + " takes the hotfix's.",
      footer = {"", "Example:", "  jrs-hotfix merge resolve <id> WEB-INF/web.xml --merged"})
  static final class Resolve extends AppCommand {
    @Parameters(index = "0", paramLabel = "<mergeId>", description = "The merge.")
    String id;

    @Parameters(index = "1", paramLabel = "<path>", description = "The file, as status lists it.")
    String path;

    @Option(
        names = "--merged",
        arity = "0..1",
        fallbackValue = "",
        paramLabel = "<file>",
        description = "Install the merged text: the workspace's merged file, or <file>.")
    String merged;

    @Option(
        names = "--mine",
        description =
            "Keep the server's file as it is; for a script, stylesheet or binary file the hotfix's"
                + " change in it is then not installed.")
    boolean mine;

    @Option(names = "--theirs", description = "Take the hotfix's file.")
    boolean theirs;

    @Override
    public Integer call() {
      int chosen = (merged != null ? 1 : 0) + (mine ? 1 : 0) + (theirs ? 1 : 0);
      if (chosen != 1) {
        return ExitCodes.fail(
            err(),
            ExitCodes.USAGE,
            "give exactly one of --merged, --mine and --theirs",
            Optional.empty());
      }
      Bootstrap boot = open();
      return executor(boot).mutate("merge resolve", () -> resolve(boot));
    }

    private int resolve(Bootstrap boot) {
      MergeWorkspace.Choice choice =
          mine
              ? MergeWorkspace.Choice.MINE
              : theirs ? MergeWorkspace.Choice.THEIRS : MergeWorkspace.Choice.MERGED;
      Optional<Path> file =
          merged == null || merged.isEmpty()
              ? Optional.empty()
              : Optional.of(Path.of(UserPaths.expand(merged, Env.vars())));
      // the runtime is opened so a home without settings is refused as everywhere else
      boot.plans().merge(id);
      String wanted = path.replace('\\', '/');
      MergeDoc doc;
      try {
        doc =
            boot.runtime()
                .merges()
                .resolve(id, wanted, choice, file, System.getProperty("user.name"));
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot record the decision: " + e.getMessage(),
            "check permissions under " + boot.home().merges(),
            e);
      }
      PrintWriter out = out();
      MergeDoc.Item item = doc.file(wanted).orElseThrow();
      out.println(
          item.path() + ": " + item.state().name().toLowerCase(Locale.ROOT).replace('_', ' '));
      out.println(doc.blocking().size() + " file(s) still wait for a decision");
      printNext(out, doc);
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code merge discard <mergeId>}. */
  @Command(
      name = "discard",
      mixinStandardHelpOptions = true,
      description = "Remove one merge and its workspace from the home.",
      footer = {"", "Example:", "  jrs-hotfix merge discard <id>"})
  static final class Discard extends AppCommand {
    @Parameters(index = "0", paramLabel = "<mergeId>", description = "The merge.")
    String id;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      return executor(boot).mutate("merge discard", () -> discard(boot));
    }

    private int discard(Bootstrap boot) {
      HotfixPlans plans = boot.plans();
      Optional<String> user = plans.installedWith(id);
      if (user.isPresent()) {
        return ExitCodes.fail(
            err(),
            ExitCodes.PRECHECK_FAILED,
            "merge " + id + " is the record of how " + user.get() + " was applied",
            Optional.of("it goes when that hotfix is rolled back and pruned"));
      }
      try {
        if (!plans.runtime().merges().discard(id)) {
          return ExitCodes.fail(
              err(),
              ExitCodes.PRECHECK_FAILED,
              "unknown merge " + id,
              Optional.of("run `jrs-hotfix merge status`"));
        }
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot remove merge " + id + ": " + e.getMessage(),
            "check permissions under " + boot.home().merges(),
            e);
      }
      out().println("discarded " + id);
      out().flush();
      return ExitCodes.SUCCESS;
    }
  }
}

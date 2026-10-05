package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.baseline.BaselineManifest;
import com.jaspersoft.jrshotfix.baseline.BaselineStore;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix baseline}: the vendor's files this installation is compared with. Invariants:
 * {@code list} is read-only; {@code add}, {@code import} and {@code remove} write under the home
 * only and take the run lock; {@code import} only reads the other home; nothing under the
 * installation is touched by any of them.
 */
@Command(
    name = "baseline",
    mixinStandardHelpOptions = true,
    description =
        "The vendor's files this server is compared with: list them, add a release's WAR or a"
            + " hotfix package, copy them from another home, remove one.",
    subcommands = {
      BaselineCommand.ListBaselines.class,
      BaselineCommand.Add.class,
      BaselineCommand.Import.class,
      BaselineCommand.Remove.class
    })
final class BaselineCommand extends GroupCommand {

  /** {@code baseline list}. */
  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      description = "List the baselines in the home.",
      footer = {"", "Example:", "  jrs-hotfix baseline list"})
  static final class ListBaselines extends AppCommand {
    @Override
    public Integer call() {
      List<BaselineManifest> all = open().runtimeOrWarLike().baselines().list();
      PrintWriter out = out();
      if (all.isEmpty()) {
        out.println(
            "no baselines; `jrs-hotfix baseline add <jasperserver-pro.war>` adds the release's");
        out.flush();
        return ExitCodes.SUCCESS;
      }
      TextTable table = table();
      table.row("ID", "KIND", "RELEASE", "BUILD", "FILES", "MERGEABLE", "ADDED");
      for (BaselineManifest b : all) {
        table.row(
            b.id(),
            b.kind().name().toLowerCase(Locale.ROOT),
            b.release() + " " + b.edition(),
            b.build(),
            String.valueOf(b.files().size()),
            String.valueOf(b.files().stream().filter(BaselineManifest.BaseFile::payload).count()),
            b.createdAt().toString());
      }
      table.printTo(out);
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code baseline add <war | dir | package.zip>}. */
  @Command(
      name = "add",
      mixinStandardHelpOptions = true,
      description =
          "Add a baseline: the vendor's jasperserver-pro.war of the installed release (or the"
              + " directory that holds it, or an unpacked copy), or the official ZIP of a hotfix"
              + " that is on the server. Changes nothing on the server.",
      footer = {"", "Example:", "  jrs-hotfix baseline add C:\\Downloads\\jasperserver-pro.war"})
  static final class Add extends AppCommand {
    @Parameters(
        index = "0",
        paramLabel = "<source>",
        description = "A WAR, a directory, or a hotfix ZIP.")
    Path source;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      return executor(boot).mutate("baseline add", () -> add(boot));
    }

    private int add(Bootstrap boot) {
      // a home made for WARs may have no settings yet: the WAR's own settings serve
      HotfixPlans plans = new HotfixPlans(boot.runtimeOrWarLike());
      BaselineStore store = plans.runtime().baselines();
      if (!Files.exists(source)) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            source + " does not exist",
            "point `jrs-hotfix baseline add` at the vendor's WAR or at a hotfix ZIP");
      }
      BaselineManifest added;
      try {
        if (Files.isRegularFile(source) && OfficialPackage.looksOfficial(source)) {
          added =
              store.addHotfix(
                  source.toAbsolutePath().normalize(),
                  plans.readPackage(source),
                  plans.runtime().settings().webappName());
        } else {
          added = store.addRelease(source);
        }
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot read " + source + ": " + e.getMessage(),
            "check the file and the free space under " + boot.home().baselines(),
            e);
      }
      PrintWriter out = out();
      out.println(
          "added "
              + added.id()
              + ": "
              + added.files().size()
              + " files, "
              + added.files().stream().filter(BaselineManifest.BaseFile::payload).count()
              + " kept for merging; nothing was changed on the server");
      List<String> installer =
          added.files().stream()
              .filter(BaselineManifest.BaseFile::installer)
              .map(BaselineManifest.BaseFile::path)
              .toList();
      if (!installer.isEmpty()) {
        out.println("written by the installer for each server:");
        installer.forEach(p -> out.println("  " + p));
      }
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code baseline import <home>}. */
  @Command(
      name = "import",
      mixinStandardHelpOptions = true,
      description =
          "Copy into this home the baselines another home holds and this one does not, such as the"
              + " server's into a WAR's own home. Changes nothing on the server or in the other home.",
      footer = {
        "",
        "Example:",
        "  jrs-hotfix baseline import C:\\Jaspersoft\\jasperreports-server\\jrs-hotfix"
            + " --home C:\\builds\\jrs-hotfix"
      })
  static final class Import extends AppCommand {
    @Parameters(
        index = "0",
        paramLabel = "<home>",
        description = "The other home: the jrs-hotfix directory that holds its baselines.")
    Path from;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      return executor(boot).mutate("baseline import", () -> importFrom(boot));
    }

    private int importFrom(Bootstrap boot) {
      Home other = new Home(from.toAbsolutePath().normalize());
      if (!Files.isDirectory(other.baselines())) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            from + " holds no baselines",
            "name the home of the server whose baselines you want, the jrs-hotfix directory beside"
                + " its installation");
      }
      BaselineStore store = new HotfixPlans(boot.runtimeOrWarLike()).runtime().baselines();
      List<String> copied;
      try {
        copied = store.importFrom(new BaselineStore(other, boot.clock()));
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot copy the baselines of " + from + ": " + e.getMessage(),
            "check the rights and free space under " + boot.home().baselines(),
            e);
      }
      PrintWriter out = out();
      out.println(
          copied.isEmpty()
              ? "nothing to copy: this home already holds every baseline of " + from
              : "copied " + String.join(", ", copied) + " from " + from);
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code baseline remove <id>}. */
  @Command(
      name = "remove",
      mixinStandardHelpOptions = true,
      description = "Remove one baseline from the home.",
      footer = {"", "Example:", "  jrs-hotfix baseline remove <id>"})
  static final class Remove extends AppCommand {
    @Parameters(
        index = "0",
        paramLabel = "<id>",
        description = "The id, as `baseline list` has it.")
    String id;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      return executor(boot).mutate("baseline remove", () -> remove(boot));
    }

    private int remove(Bootstrap boot) {
      try {
        if (!boot.runtimeOrWarLike().baselines().remove(id)) {
          return ExitCodes.fail(
              err(),
              ExitCodes.PRECHECK_FAILED,
              "unknown baseline " + id,
              Optional.of("run `jrs-hotfix baseline list`"));
        }
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot remove baseline " + id + ": " + e.getMessage(),
            "check permissions under " + boot.home().baselines(),
            e);
      }
      out().println("removed " + id);
      out().flush();
      return ExitCodes.SUCCESS;
    }
  }
}

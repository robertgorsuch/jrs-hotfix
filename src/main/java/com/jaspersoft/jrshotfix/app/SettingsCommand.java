package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.home.Detection;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.platform.InstallScan;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-hotfix settings}: what the tool knows about this installation. Invariants: {@code
 * show} is read-only; {@code set} changes one known key and refuses anything else with exit 1 and
 * the key list; {@code detect} never replaces existing settings without {@code --yes}.
 */
@Command(
    name = "settings",
    mixinStandardHelpOptions = true,
    description = "Show, change or detect the settings for this installation.",
    subcommands = {
      SettingsCommand.Show.class,
      SettingsCommand.Set.class,
      SettingsCommand.Detect.class
    })
final class SettingsCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Mixin GlobalOptions global;

  @Override
  public Integer call() {
    spec.commandLine().usage(spec.commandLine().getErr());
    return ExitCodes.USAGE;
  }

  static Settings required(Bootstrap boot) {
    return boot.settings()
        .orElseThrow(
            () ->
                new HotfixException(
                    HotfixException.PRECHECK,
                    "no settings yet",
                    "run `jrs-hotfix settings detect`, or start jrs-hotfix at a terminal for the"
                        + " wizard"));
  }

  static void print(PrintWriter out, Home home, Settings settings) {
    TextTable table = new TextTable();
    for (Map.Entry<String, String> e : settings.keys().entrySet()) {
      table.row(e.getKey(), e.getValue().isEmpty() ? "-" : e.getValue());
    }
    table.row("(home)", home.root().toString());
    for (String line : table.lines()) {
      out.println(line);
    }
    out.flush();
  }

  /** {@code settings show}. */
  @Command(name = "show", mixinStandardHelpOptions = true, description = "Show every setting.")
  static final class Show extends AppCommand {
    @Override
    public Integer call() {
      Bootstrap boot = open();
      print(out(), boot.home(), required(boot));
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code settings set <key> <value>}. */
  @Command(name = "set", mixinStandardHelpOptions = true, description = "Change one setting.")
  static final class Set extends AppCommand {
    @Parameters(
        index = "0",
        paramLabel = "<key>",
        description = "A key as `settings show` lists it.")
    String key;

    @Parameters(index = "1", paramLabel = "<value>", description = "The new value; \"\" clears.")
    String value;

    @Override
    public Integer call() {
      Bootstrap boot = open();
      Settings current = required(boot);
      Settings updated;
      try {
        updated = current.withKey(key, value);
      } catch (IllegalArgumentException e) {
        return ExitCodes.fail(
            err(),
            ExitCodes.USAGE,
            ExitCodes.messageOf(e),
            ExitCodes.messageOf(e).contains(String.join(", ", Settings.KEYS))
                ? Optional.empty()
                : Optional.of("keys: " + String.join(", ", Settings.KEYS)));
      }
      SettingsStore.save(boot.home(), updated);
      out().println(key + " = " + updated.keys().getOrDefault(key, value));
      out().flush();
      return ExitCodes.SUCCESS;
    }
  }

  /** {@code settings detect}. */
  @Command(
      name = "detect",
      mixinStandardHelpOptions = true,
      description = "Find the JasperReports Server installation and write settings for it.")
  static final class Detect extends AppCommand {
    @Override
    public Integer call() {
      Bootstrap boot = open();
      if (boot.settings().isPresent() && !global.yes()) {
        return ExitCodes.fail(
            err(),
            ExitCodes.PRECHECK_FAILED,
            "settings exist already in " + boot.home().root(),
            Optional.of(
                "pass --yes to replace them, or change one with `jrs-hotfix settings set`"));
      }
      // Task 13 brings the interactive wizard; until then a terminal gets the same first match
      InstallScan scan = Detection.candidates(boot.platform());
      for (Path candidate : scan.candidates()) {
        Optional<Settings> found = Detection.defaults(boot.platform(), candidate);
        if (found.isPresent()) {
          Home home = boot.homeFor(candidate);
          SettingsStore.save(home, found.get());
          out().println("settings written to " + home.settingsFile());
          print(out(), home, found.get());
          return ExitCodes.SUCCESS;
        }
      }
      return ExitCodes.fail(
          err(),
          ExitCodes.PRECHECK_FAILED,
          "no JasperReports Server installation found"
              + scan.processScanLimit().map(l -> " (" + l + ")").orElse(""),
          Optional.of("run jrs-hotfix on the server where JasperReports Server is installed"));
    }
  }
}

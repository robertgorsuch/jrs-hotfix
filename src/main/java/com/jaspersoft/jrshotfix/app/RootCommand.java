package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.Version;
import com.jaspersoft.jrshotfix.engine.RunRecord;
import com.jaspersoft.jrshotfix.home.JrsVersion;
import com.jaspersoft.jrshotfix.home.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code jrs-hotfix} command. Invariants: without a subcommand, an operator at a terminal (and
 * without {@code --non-interactive}) gets the {@link Menu}, whose entries run in this process
 * through a command line built with the same opener; anyone else gets the usage and exit 1.
 */
@Command(
    name = "jrs-hotfix",
    header = "jrs-hotfix - JasperReports Server hotfix tool (Jaspersoft)",
    mixinStandardHelpOptions = true,
    versionProvider = RootCommand.VersionProvider.class,
    subcommands = {
      ApplyCommand.class,
      RollbackCommand.class,
      VerifyCommand.class,
      ListCommand.class,
      RecordCommand.class,
      RunsCommand.class,
      SettingsCommand.class
    })
final class RootCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Mixin GlobalOptions global;

  /** Set by {@link Main}'s factory, so the menu's commands open the same way this one would. */
  Bootstrap.Opener opener = Bootstrap.DEFAULT;

  @Override
  public Integer call() {
    if (!global.nonInteractive() && Terminal.present()) {
      return new Menu(
              spec.commandLine().getOut(),
              passOn(global),
              this::runCommand,
              this::pendingRuns,
              this::settings,
              this::installedRelease)
          .run();
    }
    spec.commandLine().usage(spec.commandLine().getErr());
    return ExitCodes.USAGE;
  }

  private int runCommand(String[] args) {
    return Main.commandLine(spec.commandLine().getOut(), spec.commandLine().getErr(), opener)
        .execute(args);
  }

  /** Ids of runs that need recovery; empty when the home or its journal cannot be read. */
  private List<String> pendingRuns() {
    try {
      return new RunService(opener.open(global))
          .pendingRuns().stream().map(RunRecord::runId).toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /** The settings as they are now; empty when there are none or they cannot be read. */
  private Optional<Settings> settings() {
    try {
      return opener.open(global).settings();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The configured webapp's release and edition, e.g. {@code 10.0.0 PRO}, for the menu header. */
  private String installedRelease() {
    return settings()
        .map(
            s ->
                JrsVersion.ofWebapp(s.webappDir()).orElse("unknown")
                    + " "
                    + (s.webappName().endsWith("-pro") ? "PRO" : "CE"))
        .orElse("unknown");
  }

  /** The global options given with a bare {@code jrs-hotfix}, passed on to every menu command. */
  static List<String> passOn(GlobalOptions global) {
    List<String> args = new ArrayList<>();
    global.home().ifPresent(h -> args.addAll(List.of("--home", h.toString())));
    if (global.noColor()) {
      args.add("--no-color");
    }
    if (global.noPager()) {
      args.add("--no-pager");
    }
    if (global.ascii()) {
      args.add("--ascii");
    }
    return args;
  }

  /** {@code --version}: the product, its version and the vendor line. */
  static final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
      Version v = Version.current();
      return new String[] {v.product() + " " + v.version(), v.vendor()};
    }
  }
}

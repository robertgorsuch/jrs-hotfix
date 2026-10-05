package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.Version;
import com.jaspersoft.jrshotfix.engine.RunRecord;
import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * The {@code jrs-hotfix} command. Invariants: without a subcommand, an operator at a terminal (and
 * without {@code --non-interactive}) gets the {@link Menu}, whose entries run in this process
 * through a command line built with the same opener and are handed the home the menu resolved once;
 * anyone else gets the usage and exit 1.
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
      CompareCommand.class,
      ScanCommand.class,
      BaselineCommand.class,
      MergeCommand.class,
      RunsCommand.class,
      SettingsCommand.class
    })
final class RootCommand extends AppCommand {

  /**
   * The global options, which picocli inherits into every subcommand ({@link AppCommand#global}).
   */
  @Mixin GlobalOptions global;

  @Option(names = "--docs", description = "Print the documentation page.")
  boolean docs;

  @Override
  public Integer call() {
    if (docs) {
      out().print(EmbeddedDoc.text());
      out().flush();
      return ExitCodes.SUCCESS;
    }
    if (!global.nonInteractive() && Terminal.present()) {
      return menu(out(), this::runCommand).run();
    }
    spec.commandLine().usage(err());
    return ExitCodes.USAGE;
  }

  /**
   * The menu over one {@link Session}: the bootstrap is opened once, not per entry, and every
   * command the menu runs is handed the home it resolved.
   */
  Menu menu(PrintWriter out, Function<String[], Integer> runner) {
    Session session = new Session(opener, global);
    return new Menu(
        out,
        session::args,
        passOn(global),
        runner,
        session::pendingRuns,
        session::settings,
        session::installedRelease,
        session::refresh,
        session::waitingMerge);
  }

  private int runCommand(String[] args) {
    return Main.commandLine(out(), err(), opener).execute(args);
  }

  /** The global options given with a bare {@code jrs-hotfix}, passed on to every menu command. */
  static List<String> passOn(GlobalOptions global) {
    List<String> args = new ArrayList<>();
    global.home().ifPresent(h -> args.addAll(List.of("--home", h.toString())));
    if (global.noColor()) {
      args.add("--no-color");
    }
    return args;
  }

  /**
   * What the menu knows about the installation, read once and again only after the settings may
   * have changed. Invariants: the opener runs at most once between two {@link #refresh} calls, so
   * the menu costs one install scan, not one per entry; a bootstrap that cannot be opened reads as
   * no settings and no pending runs.
   */
  static final class Session {
    private final Bootstrap.Opener opener;
    private final GlobalOptions global;
    private boolean opened;
    private Optional<Bootstrap> boot = Optional.empty();
    private Optional<String> release = Optional.empty();

    Session(Bootstrap.Opener opener, GlobalOptions global) {
      this.opener = Objects.requireNonNull(opener, "opener");
      this.global = Objects.requireNonNull(global, "global");
    }

    private Optional<Bootstrap> boot() {
      if (!opened) {
        opened = true;
        try {
          boot = Optional.of(opener.open(global));
        } catch (RuntimeException e) {
          boot = Optional.empty();
        }
      }
      return boot;
    }

    /** Reads the settings and the home again on next use. */
    void refresh() {
      opened = false;
      boot = Optional.empty();
      release = Optional.empty();
    }

    /** The options to pass on, naming the resolved home once there are settings in it. */
    List<String> args() {
      List<String> args = passOn(global);
      if (global.home().isEmpty()) {
        boot()
            .filter(b -> b.settings().isPresent())
            .ifPresent(b -> args.addAll(0, List.of("--home", b.home().root().toString())));
      }
      return args;
    }

    /** Ids of runs that need recovery; empty when the home or its journal cannot be read. */
    List<String> pendingRuns() {
      try {
        return boot()
            .map(b -> new RunService(b).pendingRuns().stream().map(RunRecord::runId).toList())
            .orElse(List.of());
      } catch (RuntimeException e) {
        return List.of();
      }
    }

    /** The settings as they are now; empty when there are none or they cannot be read. */
    Optional<Settings> settings() {
      return boot().flatMap(Bootstrap::settings);
    }

    /**
     * The newest merge in the home prepared from exactly this package (by its SHA-256) whose files
     * still wait for a decision; empty when there is none, or the home or the package cannot be
     * read.
     */
    Optional<Menu.WaitingMerge> waitingMerge(Path pkg) {
      try {
        Optional<Bootstrap> b = boot().filter(x -> x.settings().isPresent());
        if (b.isEmpty()) {
          return Optional.empty();
        }
        String sha = b.get().platform().files().sha256(pkg);
        return b.get().plans().runtime().merges().list().stream()
            .filter(d -> d.packageSha256().equalsIgnoreCase(sha) && !d.blocking().isEmpty())
            .findFirst()
            .map(
                d ->
                    new Menu.WaitingMerge(
                        d.id(), d.blocking().stream().map(MergeDoc.Item::path).toList()));
      } catch (IOException | RuntimeException e) {
        return Optional.empty();
      }
    }

    /**
     * The configured webapp's release, edition and build, e.g. {@code 10.0.0 PRO, build
     * 20260730_0457}, for the header.
     */
    String installedRelease() {
      if (release.isEmpty()) {
        release =
            Optional.of(
                settings().map(s -> InstalledBuild.describe(s.webappDir())).orElse("unknown"));
      }
      return release.get();
    }
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

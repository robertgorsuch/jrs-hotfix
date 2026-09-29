package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.HomeResolver;
import com.jaspersoft.jrshotfix.home.LastHome;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.hotfix.HotfixRuntime;
import com.jaspersoft.jrshotfix.platform.NativeTempDir;
import com.jaspersoft.jrshotfix.platform.OperatorPrompt;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.Platforms;
import com.jaspersoft.jrshotfix.redact.Redactor;
import com.jaspersoft.jrshotfix.service.ServerProbe;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.Ledger;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * What every command opens first: the home, the settings in it, the platform and whether a person
 * is at the terminal. Invariants: the home comes from {@code --home}, then {@code JRS_HOTFIX_HOME},
 * then {@code ./jrs-hotfix} when it holds settings, then the home used last ({@link LastHome})
 * while its settings still exist, then the one detected installation whose {@code jrs-hotfix/}
 * holds settings (more than one is refused, naming each, until {@code --home} chooses), else {@code
 * ./jrs-hotfix}; a home with settings is remembered as the last one; the home directory exists once
 * {@link #open} returns and native temporary files go below it; the platform is bound to the
 * configured install directory when there are settings; a command that needs the hotfix runtime
 * without settings fails with a precheck telling the operator how to create them.
 */
final class Bootstrap {

  /** How a command obtains its bootstrap; tests substitute the platform through it. */
  @FunctionalInterface
  interface Opener {
    Bootstrap open(GlobalOptions options);
  }

  static final String NO_SETTINGS = "no settings found";

  static final String NO_SETTINGS_REMEDIATION =
      "pass `--home <installDir>/jrs-hotfix` or set `JRS_HOTFIX_HOME`, or run `jrs-hotfix settings"
          + " detect` to set up this installation";

  static final Opener DEFAULT = options -> open(options, Env.vars(), Clock.systemUTC());

  private final Home home;
  private final Optional<Settings> settings;
  private final Platform platform;
  private final Redactor redactor;
  private final Clock clock;
  private final boolean interactive;
  private final boolean explicitHome;
  private final Path lastHome;

  private Bootstrap(
      Home home,
      Optional<Settings> settings,
      Platform platform,
      Redactor redactor,
      Clock clock,
      boolean interactive,
      boolean explicitHome,
      Path lastHome) {
    this.home = home;
    this.settings = settings;
    this.platform = platform;
    this.redactor = redactor;
    this.clock = clock;
    this.interactive = interactive;
    this.explicitHome = explicitHome;
    this.lastHome = lastHome;
  }

  static Bootstrap open(GlobalOptions options, Map<String, String> env, Clock clock) {
    return open(options, env, clock, Platforms::detect);
  }

  static Bootstrap open(
      GlobalOptions options,
      Map<String, String> env,
      Clock clock,
      Function<OperatorPrompt, Platform> detect) {
    boolean interactive = !options.nonInteractive() && Terminal.present();
    OperatorPrompt prompt = interactive ? new ConsolePrompt() : OperatorPrompt.nonInteractive();
    Platform detected = detect.apply(prompt);
    boolean explicit =
        options.home().isPresent()
            || (env.containsKey(HomeResolver.ENV) && !env.get(HomeResolver.ENV).isBlank());
    Path pointer = LastHome.file(env, detected.os() == Platform.OsFamily.WINDOWS);
    // the home needs the install dir and the install dir is in the settings: so the flag or the
    // environment first, else ./jrs-hotfix with settings, else the home used last while its
    // settings are still there, else the one detected installation whose jrs-hotfix/ holds
    // settings; two or more of those is a question only the operator can answer
    Home home = HomeResolver.resolve(options.home(), env, Optional.empty());
    Optional<Settings> settings = SettingsStore.load(home);
    if (settings.isEmpty() && !explicit) {
      Optional<Home> last =
          LastHome.read(pointer).filter(h -> Files.isRegularFile(h.settingsFile()));
      if (last.isPresent()) {
        home = last.get();
        settings = SettingsStore.load(home);
      } else {
        Map<Home, Settings> found = new LinkedHashMap<>();
        for (Path c : detected.scanInstallDirs().candidates()) {
          Home h = HomeResolver.resolve(Optional.empty(), env, Optional.of(c));
          SettingsStore.load(h).ifPresent(s -> found.putIfAbsent(h, s));
        }
        if (found.size() > 1) {
          throw new HotfixException(
              HotfixException.PRECHECK,
              "more than one installation has settings: "
                  + String.join(
                      ", ", found.keySet().stream().map(h -> h.root().toString()).toList()),
              "choose one with `--home <installDir>/jrs-hotfix` (or set JRS_HOTFIX_HOME)");
        }
        for (Map.Entry<Home, Settings> e : found.entrySet()) {
          home = e.getKey();
          settings = Optional.of(e.getValue());
        }
      }
    }
    if (settings.isPresent()) {
      LastHome.write(pointer, home);
    }
    try {
      Files.createDirectories(home.root());
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot create the home " + home.root() + ": " + e.getMessage(),
          "check permissions or pass --home",
          e);
    }
    NativeTempDir.use(home.nativeTemp());
    Platform platform = settings.map(s -> detected.withInstallDir(s.installDir())).orElse(detected);
    return new Bootstrap(
        home, settings, platform, Redactor.global(), clock, interactive, explicit, pointer);
  }

  Home home() {
    return home;
  }

  Optional<Settings> settings() {
    return settings;
  }

  Platform platform() {
    return platform;
  }

  Redactor redactor() {
    return redactor;
  }

  Clock clock() {
    return clock;
  }

  /** True when a person can answer questions: a terminal, and no {@code --non-interactive}. */
  boolean interactive() {
    return interactive;
  }

  /**
   * Where settings for {@code installDir} belong: the explicit home when {@code --home} or {@code
   * JRS_HOTFIX_HOME} named one, else {@code installDir/jrs-hotfix}.
   */
  Home homeFor(Path installDir) {
    return explicitHome ? home : new Home(installDir.resolve("jrs-hotfix"));
  }

  /** Makes {@code h} the home a later command finds without {@code --home}; never fails. */
  void remember(Home h) {
    LastHome.write(lastHome, h);
  }

  HotfixRuntime runtime() {
    Settings s =
        settings.orElseThrow(
            () ->
                new HotfixException(
                    HotfixException.PRECHECK, NO_SETTINGS, NO_SETTINGS_REMEDIATION));
    return new HotfixRuntime(
        home,
        s,
        platform,
        new Ledger(home),
        new SnapshotStore(home, platform.files(), clock),
        clock,
        Sleeper.system(),
        ServerProbe.http(s.baseUrl()));
  }

  HotfixPlans plans() {
    return new HotfixPlans(runtime());
  }

  /** Instructions for the manual service kind, on the console, answered with Enter. */
  static final class ConsolePrompt implements OperatorPrompt {
    @Override
    public void instruct(String message) {
      Console console =
          Terminal.console()
              .orElseThrow(
                  () -> new IllegalStateException("no console for operator prompt: " + message));
      console.printf("%s%n", Redactor.global().redact(message));
      console.printf("Press Enter when done... ");
      console.readLine();
    }

    @Override
    public boolean interactive() {
      return true;
    }
  }

  static Opener opener(Function<OperatorPrompt, Platform> detect, Map<String, String> env) {
    Objects.requireNonNull(detect, "detect");
    return options -> open(options, env, Clock.systemUTC(), detect);
  }
}

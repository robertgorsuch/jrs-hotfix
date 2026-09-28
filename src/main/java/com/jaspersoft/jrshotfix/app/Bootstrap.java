package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.HomeResolver;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * What every command opens first: the home, the settings in it, the platform and whether a person
 * is at the terminal. Invariants: the home comes from {@code --home}, then {@code JRS_HOTFIX_HOME},
 * then the first detected installation whose {@code jrs-hotfix/} holds settings, else {@code
 * ./jrs-hotfix}; the home directory exists once {@link #open} returns and native temporary files go
 * below it; the platform is bound to the configured install directory when there are settings; a
 * command that needs the hotfix runtime without settings fails with a precheck telling the operator
 * how to create them.
 */
final class Bootstrap {

  /** How a command obtains its bootstrap; tests substitute the platform through it. */
  @FunctionalInterface
  interface Opener {
    Bootstrap open(GlobalOptions options);
  }

  static final Opener DEFAULT = options -> open(options, Env.vars(), Clock.systemUTC());

  private final Home home;
  private final Optional<Settings> settings;
  private final Platform platform;
  private final Redactor redactor;
  private final Clock clock;
  private final boolean interactive;
  private final boolean explicitHome;

  private Bootstrap(
      Home home,
      Optional<Settings> settings,
      Platform platform,
      Redactor redactor,
      Clock clock,
      boolean interactive,
      boolean explicitHome) {
    this.home = home;
    this.settings = settings;
    this.platform = platform;
    this.redactor = redactor;
    this.clock = clock;
    this.interactive = interactive;
    this.explicitHome = explicitHome;
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
    // the home needs the install dir and the install dir is in the settings: so the flag or the
    // environment first, else the first detected installation whose jrs-hotfix/ holds settings
    Home home = HomeResolver.resolve(options.home(), env, Optional.empty());
    Optional<Settings> settings = SettingsStore.load(home);
    if (settings.isEmpty() && !explicit) {
      for (Path c : detected.scanInstallDirs().candidates()) {
        Home h = HomeResolver.resolve(Optional.empty(), env, Optional.of(c));
        Optional<Settings> s = SettingsStore.load(h);
        if (s.isPresent()) {
          home = h;
          settings = s;
          break;
        }
      }
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
    return new Bootstrap(home, settings, platform, Redactor.global(), clock, interactive, explicit);
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

  HotfixRuntime runtime() {
    Settings s =
        settings.orElseThrow(
            () ->
                new HotfixException(
                    HotfixException.PRECHECK,
                    "no settings yet",
                    "run `jrs-hotfix settings detect`, or start jrs-hotfix at a terminal for the"
                        + " wizard"));
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

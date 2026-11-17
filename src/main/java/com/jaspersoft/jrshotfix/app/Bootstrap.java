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
import com.jaspersoft.jrshotfix.platform.DefaultHome;
import com.jaspersoft.jrshotfix.platform.NativeTempDir;
import com.jaspersoft.jrshotfix.platform.OperatorPrompt;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.Platforms;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import com.jaspersoft.jrshotfix.redact.Redactor;
import com.jaspersoft.jrshotfix.service.ServerProbe;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.UndoStore;
import com.jaspersoft.jrshotfix.war.WarFile;
import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * ./jrs-hotfix}; a home with settings is remembered as the last one; {@link #open} creates nothing
 * in the home, which is created (with native temporary files below it) only by {@link #ensureHome},
 * when the hotfix runtime is built or a run starts; the platform is bound to the configured install
 * directory when there are settings; a command that needs the hotfix runtime without settings fails
 * with a precheck telling the operator how to create them.
 */
final class Bootstrap {

  /** How a command obtains its bootstrap; tests substitute the platform through it. */
  @FunctionalInterface
  interface Opener {
    Bootstrap open(GlobalOptions options);
  }

  private static final String NO_SETTINGS = "no settings found";

  private static final String NO_SETTINGS_REMEDIATION =
      "pass `--home <installDir>/jrs-hotfix` or set `JRS_HOTFIX_HOME`, or run `jrs-hotfix settings"
          + " detect` to set up this installation";

  static final Opener DEFAULT = options -> open(options, Env.vars(), Clock.systemUTC());

  /** Where the home came from; it decides where settings go and what a refusal advises. */
  enum Origin {
    /** {@code --home} or {@code JRS_HOTFIX_HOME}. */
    EXPLICIT,
    /** {@code jrs-hotfix} beside the {@code --war} input, none being given. */
    BESIDE_WAR,
    /** A home that holds settings, found without being named. */
    SETTINGS,
    /** No settings anywhere and none named: {@code ./jrs-hotfix}, a fallback only. */
    FALLBACK,
    /** The home {@code settings detect} is about to write, chosen from the installation. */
    TARGET
  }

  private final Home home;
  private final Optional<Settings> settings;
  private final Platform platform;
  private final Redactor redactor;
  private final Clock clock;
  private final boolean interactive;
  private final Origin origin;
  private final Path lastHome;
  private final boolean turned;

  private Bootstrap(
      Home home,
      Optional<Settings> settings,
      Platform platform,
      Redactor redactor,
      Clock clock,
      boolean interactive,
      Origin origin,
      Path lastHome,
      boolean turned) {
    this.home = home;
    this.settings = settings;
    this.platform = platform;
    this.redactor = redactor;
    this.clock = clock;
    this.interactive = interactive;
    this.origin = origin;
    this.lastHome = lastHome;
    this.turned = turned;
  }

  static Bootstrap open(GlobalOptions options, Map<String, String> env, Clock clock) {
    return open(options, env, clock, Platforms::detect);
  }

  static Bootstrap open(
      GlobalOptions options,
      Map<String, String> env,
      Clock clock,
      Function<OperatorPrompt, Platform> detect) {
    return open(options, env, clock, detect, Path.of(""));
  }

  /** As above, with {@code cwd} as the working directory the fallback home is resolved in. */
  static Bootstrap open(
      GlobalOptions options,
      Map<String, String> env,
      Clock clock,
      Function<OperatorPrompt, Platform> detect,
      Path cwd) {
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
    Home home =
        explicit
            ? HomeResolver.resolve(options.home(), env, Optional.empty())
            : new Home(cwd.resolve(DefaultHome.DIR));
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
    Platform platform = settings.map(s -> detected.withInstallDir(s.installDir())).orElse(detected);
    Origin origin =
        explicit ? Origin.EXPLICIT : settings.isPresent() ? Origin.SETTINGS : Origin.FALLBACK;
    return new Bootstrap(
        home, settings, platform, Redactor.global(), clock, interactive, origin, pointer, false);
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

  /** This bootstrap as if a person were at a terminal: for tests of the questions commands ask. */
  Bootstrap asInteractive() {
    return new Bootstrap(home, settings, platform, redactor, clock, true, origin, lastHome, turned);
  }

  /**
   * True when no settings were found and no home was named, so {@link #home} is only the {@code
   * ./jrs-hotfix} fallback: {@code settings detect} writes elsewhere ({@link #homeFor}) and must
   * neither create nor lock this one.
   */
  boolean fallbackHome() {
    return origin == Origin.FALLBACK;
  }

  /**
   * This bootstrap over {@code target}, the home {@code settings detect} chose for an installation:
   * so its run gates and run lock are taken in the home that is written, not in the fallback.
   * Nothing is created by this.
   */
  Bootstrap at(Home target) {
    Optional<Settings> s = SettingsStore.load(target);
    Platform p = s.map(x -> platform.withInstallDir(x.installDir())).orElse(platform);
    return new Bootstrap(
        target, s, p, redactor, clock, interactive, Origin.TARGET, lastHome, turned);
  }

  private boolean explicitHome() {
    return origin == Origin.EXPLICIT || origin == Origin.BESIDE_WAR;
  }

  /**
   * Where settings for {@code installDir} belong: the explicit home when {@code --home} or {@code
   * JRS_HOTFIX_HOME} named one, else {@code installDir/jrs-hotfix}.
   */
  Home homeFor(Path installDir) {
    return explicitHome() ? home : new Home(installDir.resolve(DefaultHome.DIR));
  }

  /** Makes {@code h} the home a later command finds without {@code --home}; never fails. */
  void remember(Home h) {
    LastHome.write(lastHome, h);
  }

  /**
   * Refuses (exit 2) a home that exists and is not a folder, before anything tries to create the
   * run lock or other files below it. A symbolic link counts as existing even when it dangles, and
   * as a folder only when it leads to one. The usual cause: no settings and no {@code --home}, so
   * the home falls back to {@code ./jrs-hotfix}, and the working directory is the distribution's
   * {@code bin/}, where {@code jrs-hotfix} is the launcher script.
   */
  void requireHomeFolder() {
    Path root = home.root();
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root)) {
      String where = root.toAbsolutePath().normalize().toString();
      throw new HotfixException(
          HotfixException.PRECHECK,
          "the jrs-hotfix home " + where + " is not a folder",
          switch (origin) {
            case EXPLICIT -> "point --home (or JRS_HOTFIX_HOME) at a folder";
            case BESIDE_WAR ->
                "the home for a WAR defaults to jrs-hotfix beside it, and there that is not a"
                    + " folder: pass `--home` with a folder for this WAR";
            case FALLBACK ->
                "no settings were found, so the home defaults to ./jrs-hotfix, and here that is"
                    + " not a folder (the launcher, when this is the tool's bin folder): pass"
                    + " `--home <installDir>/jrs-hotfix`, set JRS_HOTFIX_HOME, or run jrs-hotfix"
                    + " from another folder";
            case SETTINGS, TARGET -> "move " + where + " aside, or pass `--home` with a folder";
          });
    }
  }

  /**
   * Creates the home and points native temporary files below it; called only where something is
   * about to be written there (the hotfix runtime, a run), so a command that finds no settings
   * leaves no directory behind.
   */
  Home ensureHome() {
    requireHomeFolder();
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
    return home;
  }

  /**
   * This bootstrap turned towards a WAR instead of a server (0.2 design, section 7), or towards a
   * deployed or exploded webapp directory (0.7 design, section 2.2): the home is the one given,
   * else {@code jrs-hotfix} beside the input, and beside a deployed webapp's {@code webapps/}
   * rather than inside it, where Tomcat would deploy it; the settings name the copy of the input
   * under the home as the webapp, with no service; the input is unpacked or copied there unless the
   * copy is of this input already; the input itself is never written. A home that has no settings
   * gets these written, so {@code baseline add} and the other commands work in it without {@code
   * --war}; so do settings written for WARs before 0.7, which named a manual service. A server's
   * own settings are never replaced.
   */
  Bootstrap forWar(Path war) {
    return forWar(war, Optional.empty());
  }

  /**
   * As {@link #forWar(Path)}, with {@code installOut} as the installation tree when given: the
   * package's buildomatic and samples files are applied there (0.7 design, section 2.1, "#30
   * without a distribution"). The settings written to the home never name it.
   */
  Bootstrap forWar(Path war, Optional<Path> installOut) {
    Path file = war.toAbsolutePath().normalize();
    if (!Files.isRegularFile(file) && !Files.isDirectory(file)) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          file + " does not exist",
          "point --war at the WAR file, or at a deployed or exploded webapp directory");
    }
    Home warHome = explicitHome() ? home : besideWar(file);
    Settings saved = warSettings(warHome, file);
    Settings s =
        installOut
            .map(d -> saved.withKey("installDir", d.toAbsolutePath().normalize().toString()))
            .orElse(saved);
    Optional<Settings> existing = SettingsStore.load(warHome);
    boolean forWars =
        existing.isEmpty() || existing.get().withKey("service.kind", "none").equals(saved);
    if (installOut.isPresent() && !forWars) {
      // the undo of the installation tree would replace the undo of the home's own installation
      throw new HotfixException(
          HotfixException.PRECHECK,
          warHome.root() + " is the home of " + existing.get().installDir() + ", not one for WARs",
          "pass --home with a directory of its own for this WAR, or leave --home out");
    }
    Bootstrap turned =
        new Bootstrap(
            warHome,
            Optional.of(s),
            platform,
            redactor,
            clock,
            interactive,
            origin == Origin.EXPLICIT ? Origin.EXPLICIT : Origin.BESIDE_WAR,
            lastHome,
            true);
    turned.ensureHome();
    try {
      WarFile.unpack(file, s.webappDir(), platform.files());
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot unpack " + file + ": " + e.getMessage(),
          "check the file and the free space under " + warHome.root(),
          e);
    }
    if (forWars) {
      SettingsStore.save(warHome, saved);
    }
    return turned;
  }

  /**
   * The home a WAR is worked on in when none was given: {@code jrs-hotfix} beside the WAR, or
   * beside the {@code webapps} directory a deployed webapp sits in.
   */
  static Home besideWar(Path war) {
    Path file = war.toAbsolutePath().normalize();
    Path beside = file.getParent();
    if (Files.isDirectory(file)
        && beside.getFileName() != null
        && beside.getFileName().toString().equals("webapps")
        && beside.getParent() != null) {
      beside = beside.getParent();
    }
    return new Home(beside.resolve(DefaultHome.DIR));
  }

  /** The settings a WAR is worked on with: its unpacked copy is the webapp, there is no service. */
  static Settings warSettings(Home warHome, Path war) {
    String name = war.getFileName().toString();
    String stem =
        name.toLowerCase(java.util.Locale.ROOT).endsWith(".war")
            ? name.substring(0, name.length() - 4)
            : name;
    if (!stem.matches("(?i)jasperserver(-pro)?")) {
      stem = "jasperserver-pro";
    }
    Path wars = warHome.root().resolve("wars");
    return new Settings(
        wars,
        wars,
        stem,
        ServiceConfig.Kind.NONE,
        Optional.empty(),
        Optional.empty(),
        60,
        Optional.empty(),
        URI.create("http://localhost/" + stem));
  }

  /**
   * The runtime for a command that needs no server: this home's settings when it has them, else the
   * settings a WAR is worked on with, so {@code baseline add} works in a home made for WARs before
   * any {@code --war} command has been run there. Nothing is written by this.
   */
  HotfixRuntime runtimeOrWarLike() {
    return runtime(
        settings.orElseGet(() -> warSettings(home, home.root().resolve("jasperserver-pro.war"))));
  }

  HotfixRuntime runtime() {
    return runtime(requiredSettings());
  }

  /**
   * The settings; a precheck failure telling the operator how to create them when there are none.
   */
  Settings requiredSettings() {
    return settings.orElseThrow(
        () -> new HotfixException(HotfixException.PRECHECK, NO_SETTINGS, NO_SETTINGS_REMEDIATION));
  }

  /**
   * The runtime over {@code s}. On a build host the distribution's WAR is unpacked first under the
   * home, unless the copy there is of it already, so every command reads the webapp it holds.
   */
  private HotfixRuntime runtime(Settings s) {
    ensureHome();
    Optional<Path> war = s.distributionWar().filter(w -> !turned && Files.isRegularFile(w));
    if (war.isPresent()) {
      try {
        WarFile.unpack(war.get(), s.webappDir(), platform.files());
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "cannot unpack " + war.get() + ": " + e.getMessage(),
            "check the file and the free space under " + home.root(),
            e);
      }
    }
    return new HotfixRuntime(
        home,
        s,
        platform,
        new UndoStore(home),
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
    return opener(detect, env, Path.of(""));
  }

  /** As above, resolving the fallback home in {@code cwd}: for tests of the fallback. */
  static Opener opener(
      Function<OperatorPrompt, Platform> detect, Map<String, String> env, Path cwd) {
    Objects.requireNonNull(detect, "detect");
    Objects.requireNonNull(cwd, "cwd");
    return options -> open(options, env, Clock.systemUTC(), detect, cwd);
  }
}

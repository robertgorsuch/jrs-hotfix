package com.jaspersoft.jrshotfix.platform;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link Platform} for Windows or Linux: controller selection by {@code service.kind}, Tomcat
 * layout detection and candidate de-duplication are the same on both; the OS decides only the
 * system base of the default home and the well-known install locations, while its {@link FileOps}
 * and process finder are given. Invariants: {@link #detectTomcat} is pure inspection (no writes)
 * and returns empty unless a {@code jasperserver} or {@code jasperserver-pro} webapp is found;
 * candidate lists contain only existing directories, each once, running-Tomcat locations first,
 * then the well-known ones (Windows: {@code C:\Jaspersoft} and {@code %ProgramFiles%}; Linux:
 * {@code /opt}, {@code /usr/local} and every home directory). The default home is {@code
 * %ProgramData%\jrs-hotfix} or {@code /var/lib/jrs-hotfix} when it exists or can be created, and
 * {@code ~/.jrs-hotfix} only when no system home exists; a system home this user cannot write to is
 * refused by the resolver, never silently replaced by a per-user one. The Uninstall registry keys
 * were queried too on Windows until 0.4.0: the bundled installer's directory is always one of the
 * well-known ones, and the operator can type any other.
 */
final class OsPlatform implements Platform {

  private static final int ANCESTOR_LEVELS = 4;

  private final OsFamily os;
  private final Arch arch;
  private final ProcessRunner runner;
  private final FileOps files;
  private final OperatorPrompt prompt;
  private final Optional<Path> installDir;
  private final TomcatProcessFinder tomcats;

  /** With the process finder shared by this platform and its file operations (issue #38). */
  OsPlatform(
      OsFamily os,
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      TomcatProcessFinder tomcats) {
    this(os, arch, runner, files, prompt, Optional.empty(), tomcats);
  }

  private OsPlatform(
      OsFamily os,
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      Optional<Path> installDir,
      TomcatProcessFinder tomcats) {
    this.os = requireNonNull(os, "os");
    this.arch = requireNonNull(arch, "arch");
    this.runner = requireNonNull(runner, "runner");
    this.files = requireNonNull(files, "files");
    this.prompt = requireNonNull(prompt, "prompt");
    this.installDir = requireNonNull(installDir, "installDir");
    this.tomcats = requireNonNull(tomcats, "tomcats");
  }

  /** A copy that watches the Tomcat under {@code installDir} for {@code service.kind: manual}. */
  @Override
  public Platform withInstallDir(Path installDir) {
    return new OsPlatform(os, arch, runner, files, prompt, Optional.of(installDir), tomcats);
  }

  @Override
  public OsFamily os() {
    return os;
  }

  @Override
  public Arch arch() {
    return arch;
  }

  @Override
  public FileOps files() {
    return files;
  }

  @Override
  public ProcessRunner processes() {
    return runner;
  }

  @Override
  public Path defaultHome() {
    return homeOrFallback(
        switch (os) {
          case WINDOWS -> Path.of(env("ProgramData", "C:\\ProgramData"));
          case LINUX -> Path.of("/var/lib");
        });
  }

  /** The well-known places to look after the running Tomcats, most likely first. */
  private List<Path> wellKnownInstallDirs() {
    List<Path> candidates = new ArrayList<>();
    switch (os) {
      case WINDOWS -> {
        candidates.addAll(glob(Path.of("C:\\Jaspersoft"), "*"));
        Path programFiles = Path.of(env("ProgramFiles", "C:\\Program Files"));
        candidates.addAll(glob(programFiles, "jasperreports-server*"));
        candidates.addAll(glob(programFiles.resolve("Jaspersoft"), "*"));
      }
      case LINUX -> {
        candidates.addAll(glob(Path.of("/opt"), "jasperreports-server*"));
        candidates.addAll(glob(Path.of("/opt/jaspersoft"), "*"));
        candidates.addAll(glob(Path.of("/usr/local"), "jasperreports-server*"));
        for (Path home : glob(Path.of("/home"), "*")) {
          candidates.addAll(glob(home, "jasperreports-server*"));
        }
      }
    }
    return candidates;
  }

  private static String env(String name, String fallback) {
    return Optional.ofNullable(System.getenv(name)).orElse(fallback);
  }

  @Override
  public RunningTomcats runningTomcats(Path dir) {
    return RunningTomcats.scan(tomcats, requireNonNull(dir, "dir").toAbsolutePath().normalize());
  }

  @Override
  public ServiceController services(ServiceConfig cfg) {
    requireNonNull(cfg, "cfg");
    return switch (cfg.kind()) {
      case WINDOWS_SERVICE -> new WindowsServiceController(runner, required(cfg.name(), "name"));
      case SYSTEMD ->
          new SystemdServiceController(
              runner,
              required(cfg.name(), "name"),
              tomcats,
              installDir,
              PollingServiceController.DEFAULT_POLL_INTERVAL);
      case CTLSCRIPT, CATALINA ->
          new ScriptServiceController(
              runner,
              cfg.kind(),
              required(cfg.scriptPath(), "scriptPath"),
              tomcats,
              PollingServiceController.DEFAULT_POLL_INTERVAL,
              cfg.forceStopAfter(),
              ScriptServiceController.ProcessTerminator.FORCIBLY);
      case MANUAL ->
          new ManualServiceController(
              runner, prompt, installDir, tomcats, PollingServiceController.DEFAULT_POLL_INTERVAL);
    };
  }

  private static <T> T required(Optional<T> value, String field) {
    return value.orElseThrow(
        () -> new IllegalArgumentException("service." + field + " is required for this kind"));
  }

  @Override
  public Optional<TomcatLayout> detectTomcat(Path installDir) {
    Path base = installDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(base)) {
      return Optional.empty();
    }
    Optional<Path> tomcat = findTomcatDir(base);
    if (tomcat.isEmpty()) {
      return Optional.empty();
    }
    Path webapps = tomcat.get().resolve("webapps");
    Optional<Path> webapp =
        Stream.of("jasperserver-pro", "jasperserver")
            .map(webapps::resolve)
            .filter(Files::isDirectory)
            .findFirst();
    if (webapp.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new TomcatLayout(base, tomcat.get(), webapp.get(), webapp.get().getFileName().toString()));
  }

  private static Optional<Path> findTomcatDir(Path base) {
    if (Files.isDirectory(base.resolve("webapps"))) {
      return Optional.of(base);
    }
    List<Path> matches = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(base, Files::isDirectory)) {
      for (Path child : children) {
        String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
        if ((name.startsWith("apache-tomcat") || name.startsWith("tomcat"))
            && Files.isDirectory(child.resolve("webapps"))) {
          matches.add(child);
        }
      }
    } catch (IOException e) {
      Diag.debug("cannot list {}", base, e);
    }
    // field test 3: a Tomcat holding the webapp first, then the highest version by number (a string
    // sort ranks "apache-tomcat-9" above "apache-tomcat-10"), then the first path, so a plain
    // "apache-tomcat" still wins over "tomcat" as it always has
    Comparator<Path> preference =
        Comparator.comparing(OsPlatform::holdsWebapp)
            .thenComparing((a, b) -> NaturalOrder.compareVersions(name(a), name(b)))
            .thenComparing(NaturalOrder.PATHS.reversed());
    return matches.stream().max(preference);
  }

  private static boolean holdsWebapp(Path tomcat) {
    Path webapps = tomcat.resolve("webapps");
    return Files.isDirectory(webapps.resolve("jasperserver-pro"))
        || Files.isDirectory(webapps.resolve("jasperserver"));
  }

  private static String name(Path p) {
    Path file = p.getFileName();
    return file == null ? "" : file.toString();
  }

  @Override
  public List<Path> candidateInstallDirs() {
    return scanInstallDirs().candidates();
  }

  @Override
  public InstallScan scanInstallDirs() {
    FromProcesses fromProcesses = installDirsFromProcesses();
    List<Path> all = new ArrayList<>(fromProcesses.dirs());
    all.addAll(wellKnownInstallDirs());
    return new InstallScan(
        existingUnique(all),
        Set.copyOf(existingUnique(fromProcesses.dirs())),
        fromProcesses.limit());
  }

  /** Install dirs of running Tomcats, and why the scan may have missed one. */
  private record FromProcesses(List<Path> dirs, Optional<String> limit) {}

  /** Install dirs of running Tomcats, derived from catalina.home/base and working directory. */
  private FromProcesses installDirsFromProcesses() {
    List<Path> found = new ArrayList<>();
    List<TomcatProcessFinder.TomcatProcess> running;
    try {
      running = tomcats.find();
    } catch (TomcatScanException e) {
      Diag.debug("no install dirs from running Tomcats: {}", e.getMessage());
      return new FromProcesses(
          found,
          Optional.of(
              "the running-process scan failed ("
                  + e.getMessage()
                  + "), so a running server may not have been seen"));
    }
    long opaque = 0;
    for (TomcatProcessFinder.TomcatProcess tomcat : running) {
      if (tomcat.opaque()) {
        opaque++;
        continue;
      }
      Stream.of(tomcat.catalinaBase(), tomcat.catalinaHome(), tomcat.workingDir())
          .flatMap(Optional::stream)
          .flatMap(p -> installDirAround(p).stream())
          .forEach(found::add);
    }
    // WindowsTomcatProcesses reports another account's JVM (a service running as LocalSystem, seen
    // without elevation) as opaque: it may be the running server, and nothing says where it lives
    Optional<String> limit =
        opaque == 0
            ? Optional.empty()
            : Optional.of(
                opaque
                    + (opaque == 1 ? " Java process" : " Java processes")
                    + " whose command line this account cannot read (not elevated), so a running"
                    + " server may not have been seen; run elevated or pass --install-dir");
    return new FromProcesses(found, limit);
  }

  /**
   * The installation around a running Tomcat's {@code catalina.base}, {@code catalina.home} or
   * working directory. The Tomcat itself is a layout of its own, so the first hit going up is the
   * Tomcat; the installation is the directory above it that holds the vendor's {@code buildomatic}
   * or {@code ctlscript}, when there is one (the bundled installer lays them out that way). A
   * Tomcat with nothing of the kind above it is its own installation.
   */
  private Optional<Path> installDirAround(Path start) {
    Path probe = start.toAbsolutePath().normalize();
    for (int level = 0; level <= ANCESTOR_LEVELS && probe != null; level++) {
      Optional<TomcatLayout> layout = detectTomcat(probe);
      if (layout.isPresent()) {
        Path parent = probe.getParent();
        if (parent != null
            && probe.equals(layout.get().tomcatDir())
            && isInstallationAbove(parent, layout.get().tomcatDir())) {
          return Optional.of(parent);
        }
        return Optional.of(probe);
      }
      probe = probe.getParent();
    }
    return Optional.empty();
  }

  /** True when {@code dir} is the vendor's installation root for the Tomcat under it. */
  private boolean isInstallationAbove(Path dir, Path tomcat) {
    boolean vendorFiles =
        Files.isDirectory(dir.resolve("buildomatic"))
            || Files.isRegularFile(dir.resolve("ctlscript.sh"))
            || Files.isRegularFile(dir.resolve("ctlscript.bat"));
    return vendorFiles && detectTomcat(dir).map(l -> l.tomcatDir().equals(tomcat)).orElse(false);
  }

  /** Existing directories under {@code parent} whose name matches {@code glob}. */
  static List<Path> glob(Path parent, String glob) {
    List<Path> found = new ArrayList<>();
    if (!Files.isDirectory(parent)) {
      return found;
    }
    try (DirectoryStream<Path> children = Files.newDirectoryStream(parent, glob)) {
      for (Path child : children) {
        if (Files.isDirectory(child)) {
          found.add(child);
        }
      }
    } catch (IOException e) {
      Diag.debug("cannot glob {} in {}", glob, parent, e);
    }
    // highest version first by number, not by text (field test 3)
    found.sort(NaturalOrder.PATHS.reversed());
    return found;
  }

  /** Keeps existing directories only, first occurrence wins, order preserved. */
  private List<Path> existingUnique(List<Path> candidates) {
    Map<String, Path> unique = new LinkedHashMap<>();
    for (Path candidate : candidates) {
      Path normalised = candidate.toAbsolutePath().normalize();
      if (Files.isDirectory(normalised)) {
        unique.putIfAbsent(dedupeKey(normalised), normalised);
      }
    }
    return List.copyOf(unique.values());
  }

  private String dedupeKey(Path path) {
    String key = path.toString();
    return os() == OsFamily.WINDOWS ? key.toLowerCase(Locale.ROOT) : key;
  }

  /**
   * The default home under {@code base}, delegating the whole decision to {@link DefaultHome} so
   * that the logging bootstrap and this platform can never disagree. A fallback to the operator's
   * own directory is warned about every time, because it means state and the run lock are no longer
   * shared between operators of the same installation.
   */
  private Path homeOrFallback(Path base) {
    DefaultHome.Choice choice = DefaultHome.choose(base);
    if (choice.systemHomeUnwritable()) {
      Diag.warn(
          "{} exists but is not writable by this user; {} would be used instead, with its own"
              + " state.db and run lock",
          choice.systemHome(),
          choice.home());
    } else if (choice.perUser()) {
      Diag.warn(
          "no writable {}; using the per-user home {}, which no other operator's runs share",
          choice.systemHome(),
          choice.home());
    }
    return choice.home();
  }
}

package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.home.Detection;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.platform.InstallScan;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import com.jaspersoft.jrshotfix.platform.UserPaths;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The interactive {@code settings detect}: finds the installations on this host, lets the operator
 * pick one or type a directory, shows the detected defaults and asks to confirm each service value
 * before saving. Invariants: nothing is written until every value has been answered, so end of
 * input saves nothing and returns empty; an answer that is not acceptable is asked again; the saved
 * settings hold exactly the keys {@link Settings#keys()} lists, with the value of any service field
 * the chosen kind does not use cleared; the settings are saved in the home {@code homeFor} names
 * for the chosen directory.
 */
final class SettingsWizard {

  private static final List<String> KINDS =
      Arrays.stream(ServiceConfig.Kind.values()).map(SettingsWizard::kindName).toList();

  private static final String KINDS_HINT = "please answer " + String.join(", ", KINDS);

  private final PrintWriter out;
  private final Platform platform;
  private final Function<Path, Home> homeFor;

  /** A wizard that saves into {@code home} whatever directory is chosen. */
  SettingsWizard(PrintWriter out, Platform platform, Home home) {
    this(out, platform, dir -> home);
  }

  /** A wizard that saves into the home {@code homeFor} gives for the chosen directory. */
  SettingsWizard(PrintWriter out, Platform platform, Function<Path, Home> homeFor) {
    this.out = Objects.requireNonNull(out, "out");
    this.platform = Objects.requireNonNull(platform, "platform");
    this.homeFor = Objects.requireNonNull(homeFor, "homeFor");
  }

  /** Runs the wizard; the saved settings, or empty when input ended before they were complete. */
  Optional<Settings> run() {
    InstallScan scan = Detection.candidates(platform);
    Optional<Settings> detected = pickInstallation(scan);
    if (detected.isEmpty()) {
      return Optional.empty();
    }
    Settings s = detected.get();
    out.println();
    out.println("Detected for " + s.installDir() + ":");
    printKeys(s);
    out.println();
    out.println("Confirm each value; press Enter to keep it.");
    Optional<Settings> confirmed = confirm(s);
    if (confirmed.isEmpty()) {
      out.println("Nothing was saved.");
      return Optional.empty();
    }
    Home home = homeFor.apply(confirmed.get().installDir());
    SettingsStore.save(home, confirmed.get());
    out.println();
    out.println("settings written to " + home.settingsFile());
    SettingsCommand.print(out, home, confirmed.get());
    return confirmed;
  }

  // ---- steps ------------------------------------------------------------------------------------

  /** The defaults for the directory the operator picks or types; empty at end of input. */
  private Optional<Settings> pickInstallation(InstallScan scan) {
    List<Path> candidates = scan.candidates();
    out.println();
    if (candidates.isEmpty()) {
      out.println("No JasperReports Server installation was found on this host.");
    } else {
      out.println("JasperReports Server installations found on this host:");
      for (int i = 0; i < candidates.size(); i++) {
        Path c = candidates.get(i);
        out.println("  " + (i + 1) + ") " + c + (scan.isRunning(c) ? " (running)" : ""));
      }
      out.println("  p) Enter a path");
    }
    scan.processScanLimit().ifPresent(limit -> out.println("  note: " + limit));
    while (true) {
      Optional<Path> dir = candidates.isEmpty() ? typedDirectory() : chosen(candidates);
      if (dir.isEmpty()) {
        return Optional.empty();
      }
      Optional<Settings> defaults = Detection.defaults(platform, dir.get());
      if (defaults.isPresent()) {
        return defaults;
      }
      out.println("  no Tomcat webapp found under " + dir.get());
    }
  }

  /** A listed candidate by number, or a typed directory after {@code p}; empty at end of input. */
  private Optional<Path> chosen(List<Path> candidates) {
    String range = candidates.size() == 1 ? "1" : "1-" + candidates.size();
    String hint =
        candidates.size() == 1
            ? "  please type 1, or p"
            : "  please type a number from 1 to " + candidates.size() + ", or p";
    while (true) {
      Optional<String> answer = Prompter.line(out, "Choose [" + range + ", p]: ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      String a = answer.get().toLowerCase(Locale.ROOT);
      if (a.equals("p")) {
        return typedDirectory();
      }
      Optional<Integer> n = number(a).filter(i -> i >= 1 && i <= candidates.size());
      if (n.isPresent()) {
        return Optional.of(candidates.get(n.get() - 1));
      }
      out.println(hint);
    }
  }

  /** An existing directory the operator types; empty at end of input. */
  private Optional<Path> typedDirectory() {
    while (true) {
      Optional<String> answer = Prompter.path(out, "JasperReports Server install directory: ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      if (answer.get().isEmpty()) {
        continue;
      }
      try {
        Path dir = Path.of(UserPaths.expand(answer.get(), Env.vars())).toAbsolutePath().normalize();
        if (Files.isDirectory(dir)) {
          return Optional.of(dir);
        }
      } catch (InvalidPathException e) {
        // falls through to the hint
      }
      out.println("  no such directory: " + answer.get());
    }
  }

  /** The service values and the base URL, each confirmed or changed; empty at end of input. */
  private Optional<Settings> confirm(Settings detected) {
    Settings s = detected;
    Optional<String> kind =
        ask("service.kind", kindName(s.serviceKind()), KINDS, v -> KINDS.contains(v), KINDS_HINT);
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    boolean kindChanged = !kind.get().equals(kindName(s.serviceKind()));
    s = s.withKey("service.kind", kind.get());
    if (kindChanged) {
      // the other kind's name or script means nothing for this one
      s = s.withKey("service.name", "").withKey("service.scriptPath", "");
    }
    switch (s.serviceKind()) {
      case WINDOWS_SERVICE, SYSTEMD -> {
        Optional<String> name =
            ask(
                "service.name",
                s.serviceName().orElse(""),
                List.of(),
                v -> !v.isBlank(),
                "a service name is needed for " + kind.get());
        if (name.isEmpty()) {
          return Optional.empty();
        }
        s = s.withKey("service.name", name.get()).withKey("service.scriptPath", "");
      }
      case CTLSCRIPT, CATALINA -> {
        Optional<String> script =
            ask(
                "service.scriptPath",
                s.serviceScriptPath().map(Path::toString).orElse(""),
                List.of(),
                SettingsWizard::isFile,
                "the script must be an existing file");
        if (script.isEmpty()) {
          return Optional.empty();
        }
        s =
            s.withKey("service.scriptPath", UserPaths.expand(script.get(), Env.vars()))
                .withKey("service.name", "");
      }
      case MANUAL -> s = s.withKey("service.name", "").withKey("service.scriptPath", "");
    }
    Optional<String> timeout =
        ask(
            "service.stopTimeoutSeconds",
            Integer.toString(s.stopTimeoutSeconds()),
            List.of(),
            v -> number(v).filter(i -> i > 0).isPresent(),
            "please type a whole number of seconds greater than 0");
    if (timeout.isEmpty()) {
      return Optional.empty();
    }
    s = s.withKey("service.stopTimeoutSeconds", timeout.get());
    Optional<String> base =
        ask(
            "baseUrl",
            s.baseUrl().toString(),
            List.of(),
            SettingsWizard::isHttpUrl,
            "please type an http or https URL, e.g. http://localhost:8080/jasperserver-pro");
    if (base.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(s.withKey("baseUrl", base.get()));
  }

  // ---- helpers ----------------------------------------------------------------------------------

  /**
   * One value, prefilled with {@code initial} (Enter keeps it), asked again until {@code valid}
   * accepts it; empty at end of input.
   */
  private Optional<String> ask(
      String key,
      String initial,
      Collection<String> completions,
      Predicate<String> valid,
      String hint) {
    while (true) {
      Optional<String> answer = Prompter.edit(out, "  " + key + ": ", initial, completions);
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      String a = answer.get().strip();
      if (valid.test(a)) {
        return Optional.of(a);
      }
      out.println("    " + hint);
    }
  }

  private void printKeys(Settings s) {
    TextTable table = new TextTable();
    s.keys().forEach((k, v) -> table.row("  " + k, v.isEmpty() ? "-" : v));
    table.lines().forEach(out::println);
  }

  private static String kindName(ServiceConfig.Kind kind) {
    return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static Optional<Integer> number(String s) {
    if (!s.matches("\\d{1,9}")) {
      return Optional.empty();
    }
    return Optional.of(Integer.parseInt(s));
  }

  private static boolean isFile(String typed) {
    try {
      return !typed.isBlank() && Files.isRegularFile(Path.of(UserPaths.expand(typed, Env.vars())));
    } catch (InvalidPathException e) {
      return false;
    }
  }

  private static boolean isHttpUrl(String typed) {
    try {
      URI u = new URI(typed);
      return (Objects.equals(u.getScheme(), "http") || Objects.equals(u.getScheme(), "https"))
          && u.getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }
}

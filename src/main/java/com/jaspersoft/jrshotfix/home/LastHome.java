package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.platform.Diag;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The pointer to the home jrs-hotfix used last, so a run interrupted with the service down at a
 * path no scan can see is still found without {@code --home}. Invariants: the pointer is one line,
 * the home's absolute path, in the operator's configuration directory ({@code
 * %APPDATA%\jrs-hotfix\last-home} on Windows, {@code $XDG_CONFIG_HOME/jrs-hotfix/last-home} or
 * {@code ~/.config/jrs-hotfix/last-home} elsewhere); it is written atomically; reading it never
 * throws, and a pointer that cannot be read or names no directory is no pointer; failing to write
 * it is a warning, never an error.
 */
public final class LastHome {

  static final String DIR = "jrs-hotfix";
  static final String FILE = "last-home";

  private LastHome() {}

  /** Where the pointer lives for this operator; the environment wins over {@code user.home}. */
  public static Path file(Map<String, String> env, boolean windows) {
    Path config;
    if (windows) {
      config =
          nonBlank(env.get("APPDATA"))
              .map(Path::of)
              .orElseGet(() -> userHome(env).resolve("AppData").resolve("Roaming"));
    } else {
      config =
          nonBlank(env.get("XDG_CONFIG_HOME"))
              .map(Path::of)
              .orElseGet(() -> userHome(env).resolve(".config"));
    }
    return config.resolve(DIR).resolve(FILE);
  }

  /** The home the pointer names; empty when there is no pointer or it cannot be read. */
  public static Optional<Home> read(Path file) {
    try {
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      return nonBlank(Files.readString(file, StandardCharsets.UTF_8).strip())
          .map(Path::of)
          .map(Home::new);
    } catch (IOException | InvalidPathException e) {
      Diag.debug("cannot read {}: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  /** Points {@code file} at {@code home} unless it does already; a failure is only a warning. */
  public static void write(Path file, Home home) {
    if (read(file).filter(home::equals).isPresent()) {
      return;
    }
    try {
      Durability.writeAtomically(file, home.root() + System.lineSeparator());
    } catch (IOException | RuntimeException e) {
      Diag.warn("cannot remember the home in {}: {}", file, e.getMessage());
    }
  }

  private static Path userHome(Map<String, String> env) {
    return Path.of(
        nonBlank(env.get("HOME"))
            .or(() -> nonBlank(env.get("USERPROFILE")))
            .orElseGet(() -> System.getProperty("user.home", ".")));
  }

  private static Optional<String> nonBlank(String s) {
    return s == null || s.isBlank() ? Optional.empty() : Optional.of(s.strip());
  }
}

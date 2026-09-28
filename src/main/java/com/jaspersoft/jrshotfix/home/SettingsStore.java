package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** settings.json under the home. Invariants: absent file is "no settings"; a save is atomic. */
public final class SettingsStore {
  private SettingsStore() {}

  public static Optional<Settings> load(Home home) {
    Path file = home.settingsFile();
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Json.read(Files.readString(file, StandardCharsets.UTF_8), Settings.class));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  public static void save(Home home, Settings settings) {
    Path file = home.settingsFile();
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling("settings.json.tmp");
      Files.writeString(tmp, Json.writePretty(settings), StandardCharsets.UTF_8);
      Durability.sync(tmp);
      Durability.move(
          tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Durability.syncDirectory(file.toAbsolutePath().getParent());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }
}

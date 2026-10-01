package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.platform.DefaultHome;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The home: the flag, then JRS_HOTFIX_HOME, then installDir/jrs-hotfix, then ./jrs-hotfix.
 * Invariant: pure.
 */
public final class HomeResolver {
  public static final String ENV = "JRS_HOTFIX_HOME";

  private HomeResolver() {}

  public static Home resolve(
      Optional<Path> flag, Map<String, String> env, Optional<Path> installDir) {
    if (flag.isPresent()) {
      return new Home(flag.get());
    }
    String fromEnv = env.get(ENV);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return new Home(Path.of(fromEnv));
    }
    return new Home(
        installDir.map(d -> d.resolve(DefaultHome.DIR)).orElseGet(() -> Path.of(DefaultHome.DIR)));
  }
}

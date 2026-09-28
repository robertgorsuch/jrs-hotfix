package com.jaspersoft.jrshotfix.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HomeResolverTest {
  @Test
  void should_prefer_flag_then_env_then_install_dir_when_resolving() {
    Path flag = Path.of("a").toAbsolutePath(), install = Path.of("c").toAbsolutePath();
    Map<String, String> env = Map.of("JRS_HOTFIX_HOME", Path.of("b").toAbsolutePath().toString());
    assertThat(HomeResolver.resolve(Optional.of(flag), env, Optional.of(install)).root())
        .isEqualTo(flag.normalize());
    assertThat(HomeResolver.resolve(Optional.empty(), env, Optional.of(install)).root())
        .isEqualTo(Path.of("b").toAbsolutePath().normalize());
    assertThat(HomeResolver.resolve(Optional.empty(), Map.of(), Optional.of(install)).root())
        .isEqualTo(install.resolve("jrs-hotfix").normalize());
  }
}

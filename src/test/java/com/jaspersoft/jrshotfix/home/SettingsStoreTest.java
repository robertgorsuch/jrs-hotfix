package com.jaspersoft.jrshotfix.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {
  @TempDir Path tmp;

  static Settings sample(Path tmp) {
    return new Settings(
        tmp.resolve("jrs"),
        tmp.resolve("jrs/apache-tomcat"),
        "jasperserver-pro",
        ServiceConfig.Kind.MANUAL,
        Optional.empty(),
        Optional.empty(),
        180,
        Optional.empty(),
        URI.create("http://localhost:8080/jasperserver-pro"));
  }

  @Test
  void should_round_trip_when_saved_and_loaded() {
    Home home = new Home(tmp.resolve("home"));
    assertThat(SettingsStore.load(home)).isEmpty();
    SettingsStore.save(home, sample(tmp));
    assertThat(SettingsStore.load(home)).contains(sample(tmp));
  }

  @Test
  void should_apply_a_typed_key_when_set_from_the_command_line() {
    Settings s =
        sample(tmp)
            .withKey("service.kind", "systemd")
            .withKey("service.name", "jasperreportsTomcat")
            .withKey("service.stopTimeoutSeconds", "60");
    assertThat(s.toServiceConfig().kind()).isEqualTo(ServiceConfig.Kind.SYSTEMD);
    assertThat(s.toServiceConfig().name()).contains("jasperreportsTomcat");
    assertThat(s.toServiceConfig().stopTimeout().toSeconds()).isEqualTo(60);
    assertThatThrownBy(() -> s.withKey("nope", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("service.kind");
  }

  @Test
  void should_derive_the_webapp_dir_when_asked() {
    assertThat(sample(tmp).webappDir())
        .isEqualTo(
            tmp.resolve("jrs/apache-tomcat/webapps/jasperserver-pro").toAbsolutePath().normalize());
  }
}

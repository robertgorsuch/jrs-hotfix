package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.platform.FakeProcessRunner;
import com.jaspersoft.jrshotfix.platform.InstallScan;
import com.jaspersoft.jrshotfix.platform.OperatorPrompt;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.PlatformDetectionTest;
import com.jaspersoft.jrshotfix.platform.Platforms;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first-run wizard: pick a detected installation or type one, confirm each service value, save.
 */
class SettingsWizardTest {

  @TempDir Path tmp;

  private final StringWriter sw = new StringWriter();

  @AfterEach
  void restore() {
    Prompter.reset();
  }

  /** The {@code DetectionTest} layout plus a {@code ctlscript.sh}, so detection picks ctlscript. */
  private Path install() throws Exception {
    Path lib = tmp.resolve("jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/lib");
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("jasperserver-api-10.0.0.jar"), "x");
    Files.createDirectories(tmp.resolve("jrs/apache-tomcat/conf"));
    Files.writeString(
        tmp.resolve("jrs/apache-tomcat/conf/server.xml"),
        "<Server><Service><Connector port=\"8081\" protocol=\"HTTP/1.1\"/></Service></Server>");
    Files.createDirectories(tmp.resolve("jrs/apache-tomcat/bin"));
    Files.writeString(tmp.resolve("jrs/apache-tomcat/bin/catalina.sh"), "#!/bin/sh");
    Files.writeString(tmp.resolve("jrs/ctlscript.sh"), "#!/bin/sh");
    return tmp.resolve("jrs");
  }

  private Platform platform(List<Path> candidates, Optional<String> limit) {
    Platform base =
        Platforms.forTesting(
            Platform.OsFamily.LINUX,
            Platform.Arch.X86_64,
            new FakeProcessRunner(),
            PlatformDetectionTest.files(),
            OperatorPrompt.nonInteractive());
    return new DelegatingPlatform(base) {
      @Override
      public InstallScan scanInstallDirs() {
        return new InstallScan(candidates, Set.copyOf(candidates), limit);
      }
    };
  }

  private Optional<Settings> wizard(Platform platform, Home home, String answers) {
    Prompter.override(new StringReader(answers));
    return new SettingsWizard(new PrintWriter(sw, true), platform, home).run();
  }

  @Test
  void should_save_the_detected_defaults_when_every_default_is_accepted() throws Exception {
    Path install = install();
    Home home = new Home(tmp.resolve("home"));
    Optional<Settings> s =
        wizard(platform(List.of(install), Optional.empty()), home, "1\n\n\n\n\n");
    assertThat(s).isPresent();
    Settings saved = SettingsStore.load(home).orElseThrow();
    assertThat(saved).isEqualTo(s.get());
    assertThat(saved.webappName()).isEqualTo("jasperserver-pro");
    assertThat(saved.serviceKind()).isEqualTo(ServiceConfig.Kind.CTLSCRIPT);
    assertThat(saved.baseUrl().toString()).isEqualTo("http://localhost:8081/jasperserver-pro");
    assertThat(sw.toString()).contains("1) " + install.toAbsolutePath().normalize() + " (running)");
    for (String key : saved.keys().keySet()) {
      assertThat(sw.toString()).contains(key);
    }
  }

  @Test
  void should_save_the_typed_service_kind_when_the_operator_changes_it() throws Exception {
    Path install = install();
    Path catalina = install.resolve("apache-tomcat/bin/catalina.sh");
    Home home = new Home(tmp.resolve("home"));
    wizard(
        platform(List.of(install), Optional.empty()), home, "1\ncatalina\n" + catalina + "\n\n\n");
    Settings saved = SettingsStore.load(home).orElseThrow();
    assertThat(saved.serviceKind()).isEqualTo(ServiceConfig.Kind.CATALINA);
    assertThat(saved.serviceScriptPath()).contains(catalina.toAbsolutePath().normalize());
  }

  @Test
  void should_accept_a_typed_directory_and_ask_again_when_it_holds_no_tomcat() throws Exception {
    Path install = install();
    Path empty = Files.createDirectories(tmp.resolve("empty"));
    Home home = new Home(tmp.resolve("home"));
    wizard(
        platform(List.of(), Optional.of("1 Java process could not be read")),
        home,
        empty + "\n" + install + "\n\n\n\n\n");
    assertThat(sw.toString())
        .contains("1 Java process could not be read")
        .contains("no Tomcat webapp found under " + empty.toAbsolutePath().normalize());
    assertThat(SettingsStore.load(home).orElseThrow().installDir())
        .isEqualTo(install.toAbsolutePath().normalize());
  }

  @Test
  void should_ask_again_when_a_value_is_invalid() throws Exception {
    Path install = install();
    Home home = new Home(tmp.resolve("home"));
    wizard(
        platform(List.of(install), Optional.empty()),
        home,
        "1\nnonsense\nsystemd\njasperserver.service\nsoon\n90\nnot a url\n\n");
    Settings saved = SettingsStore.load(home).orElseThrow();
    assertThat(saved.serviceKind()).isEqualTo(ServiceConfig.Kind.SYSTEMD);
    assertThat(saved.serviceName()).contains("jasperserver.service");
    assertThat(saved.serviceScriptPath()).isEmpty();
    assertThat(saved.stopTimeoutSeconds()).isEqualTo(90);
    assertThat(sw.toString()).contains("please answer").contains("whole number");
  }

  @Test
  void should_save_nothing_when_input_ends() throws Exception {
    Path install = install();
    Home home = new Home(tmp.resolve("home"));
    assertThat(wizard(platform(List.of(install), Optional.empty()), home, "1\n\n")).isEmpty();
    assertThat(SettingsStore.load(home)).isEmpty();
  }

  @Test
  void should_keep_existing_settings_and_remember_their_home_when_the_operator_says_no()
      throws Exception {
    Path install = install();
    Home home = new Home(tmp.resolve("home"));
    wizard(platform(List.of(install), Optional.empty()), home, "1\n\n\n\n\n");
    Settings before =
        SettingsStore.load(home).orElseThrow().withKey("service.stopTimeoutSeconds", "77");
    SettingsStore.save(home, before);
    sw.getBuffer().setLength(0);
    List<Home> remembered = new ArrayList<>();
    Prompter.override(new StringReader("1\nn\n"));
    Optional<Settings> kept =
        new SettingsWizard(
                new PrintWriter(sw, true),
                platform(List.of(install), Optional.empty()),
                dir -> home,
                remembered::add)
            .run();
    assertThat(kept).contains(before);
    assertThat(SettingsStore.load(home)).contains(before);
    assertThat(remembered).containsExactly(home);
    assertThat(sw.toString())
        .contains(
            "Settings already exist for "
                + install.toAbsolutePath().normalize()
                + "; replace them? [y/N]")
        .contains("77");
  }
}

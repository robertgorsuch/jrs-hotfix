package com.jaspersoft.jrshotfix.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Which processes count as a Tomcat: the rule the force stop picks its victims by. */
class TomcatProcessesTest {

  private static final String INSTALL = "/home/robert/jasperreports-server-10.0.0";
  private static final String JVM =
      INSTALL
          + "/java/bin/java -Dcatalina.base="
          + INSTALL
          + "/apache-tomcat org.apache.catalina.startup.Bootstrap start";

  @Test
  void should_recognise_a_jvm_that_runs_catalina() {
    assertThat(TomcatProcesses.describe(42, JVM, INSTALL + "/java/bin/java"))
        .hasValueSatisfying(
            t ->
                assertThat(t.catalinaBase().map(Path::getFileName).map(Path::toString))
                    .contains("apache-tomcat"));
  }

  @Test
  void should_not_take_a_shell_for_a_tomcat_when_only_its_command_line_says_catalina() {
    // 2026-09-29, Linux laptop: a force stop ended the operator's own ssh shell, whose command
    // line named the install directory and .../work/Catalina/localhost
    String shell =
        "bash -c J=" + INSTALL + "; find $J/apache-tomcat/work/Catalina/localhost -type f | wc -l";
    assertThat(TomcatProcesses.describe(43, shell, "/usr/bin/bash")).isEmpty();
    assertThat(
            TomcatProcesses.describe(
                44, "vim " + INSTALL + "/apache-tomcat/conf/catalina.properties", "/usr/bin/vim"))
        .isEmpty();
  }

  @Test
  void should_recognise_the_daemon_and_the_windows_service_wrapper() {
    assertThat(
            TomcatProcesses.describe(
                45, "jsvc -Dcatalina.base=" + INSTALL + "/apache-tomcat", "/usr/bin/jsvc"))
        .isPresent();
    // the wrapper's home is derived from its path on Windows only (WindowsTomcatProcessesTest)
    assertThat(
            TomcatProcesses.describe(
                46,
                "",
                "C:\\Jaspersoft\\jasperreports-server-10.0.0\\apache-tomcat\\bin\\tomcat10.exe"))
        .isPresent();
  }

  @Test
  void should_recognise_a_launcher_that_carries_tomcats_own_property() {
    // the acceptance fixture's fake Tomcat, and a wrapper shell that starts the JVM
    assertThat(
            TomcatProcesses.describe(
                49, "sh -c sleep 900 # -Dcatalina.base=" + INSTALL + "/apache-tomcat", "/bin/sh"))
        .hasValueSatisfying(
            t ->
                assertThat(t.catalinaBase().map(Path::getFileName).map(Path::toString))
                    .contains("apache-tomcat"));
  }

  @Test
  void should_fall_back_on_the_command_line_when_the_executable_is_not_known() {
    // ProcessHandle gives no command for some processes; the first word of the line says java
    assertThat(TomcatProcesses.describe(47, JVM, "")).isPresent();
    assertThat(TomcatProcesses.describe(48, "bash -c echo catalina " + INSTALL, "")).isEmpty();
  }
}

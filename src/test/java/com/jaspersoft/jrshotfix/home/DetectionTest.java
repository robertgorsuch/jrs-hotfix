package com.jaspersoft.jrshotfix.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.platform.FakeProcessRunner;
import com.jaspersoft.jrshotfix.platform.OperatorPrompt;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.PlatformDetectionTest;
import com.jaspersoft.jrshotfix.platform.Platforms;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DetectionTest {
  @TempDir Path tmp;

  Path install() throws Exception {
    Path lib = tmp.resolve("jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/lib");
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("jasperserver-api-10.0.0.jar"), "x");
    Files.createDirectories(tmp.resolve("jrs/apache-tomcat/conf"));
    Files.writeString(
        tmp.resolve("jrs/apache-tomcat/conf/server.xml"),
        "<Server><Service><Connector port=\"8081\" protocol=\"HTTP/1.1\"/></Service></Server>");
    return tmp.resolve("jrs");
  }

  Platform platform(Platform.OsFamily os, FakeProcessRunner runner) {
    // PlatformDetectionTest (copied in Task 3) shows the FileOps construction that works here;
    // reuse it.
    return Platforms.forTesting(
        os,
        Platform.Arch.X86_64,
        runner,
        PlatformDetectionTest.files(),
        OperatorPrompt.nonInteractive());
  }

  @Test
  void should_pick_catalina_kind_and_the_server_xml_port_when_no_service_is_registered()
      throws Exception {
    Path install = install();
    Files.createDirectories(install.resolve("apache-tomcat/bin"));
    Files.writeString(install.resolve("apache-tomcat/bin/catalina.sh"), "#!/bin/sh");
    Settings s =
        Detection.defaults(platform(Platform.OsFamily.LINUX, new FakeProcessRunner()), install)
            .orElseThrow();
    assertThat(s.serviceKind()).isEqualTo(ServiceConfig.Kind.CATALINA);
    assertThat(s.serviceScriptPath())
        .contains(install.resolve("apache-tomcat/bin/catalina.sh").toAbsolutePath().normalize());
    assertThat(s.baseUrl().toString()).isEqualTo("http://localhost:8081/jasperserver-pro");
    assertThat(s.webappName()).isEqualTo("jasperserver-pro");
  }

  @Test
  void should_pick_the_windows_service_when_sc_query_lists_one() throws Exception {
    Path install = install();
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                Detection.SC_QUERY,
                FakeProcessRunner.ok(
                    "SERVICE_NAME: jasperreportsTomcat", "SERVICE_NAME: jasperreportsPostgreSQL"));
    Settings s =
        Detection.defaults(platform(Platform.OsFamily.WINDOWS, runner), install).orElseThrow();
    assertThat(s.serviceKind()).isEqualTo(ServiceConfig.Kind.WINDOWS_SERVICE);
    assertThat(s.serviceName()).contains("jasperreportsTomcat");
  }

  @Test
  void should_return_empty_when_the_directory_holds_no_tomcat() {
    assertThat(Detection.defaults(platform(Platform.OsFamily.LINUX, new FakeProcessRunner()), tmp))
        .isEmpty();
  }
}

package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagePathsTest {
  @TempDir Path tmp;

  @Test
  void should_resolve_webapps_under_tomcat_and_the_rest_under_the_install_when_resolved() {
    PackagePaths paths = new PackagePaths(tmp.resolve("jrs"), tmp.resolve("jrs/apache-tomcat"));
    assertThat(paths.resolve("webapps/jasperserver-pro/x"))
        .isEqualTo(paths.tomcatDir().resolve("webapps/jasperserver-pro/x"));
    assertThat(paths.resolve("buildomatic/x"))
        .isEqualTo(paths.installDir().resolve("buildomatic/x"));
  }

  @Test
  void should_require_a_stop_only_when_the_path_is_under_web_inf_lib_or_classes() {
    assertThat(PackagePaths.requiresServiceStop("webapps/jasperserver-pro/WEB-INF/lib/a.jar"))
        .isTrue();
    assertThat(PackagePaths.requiresServiceStop("webapps/jasperserver-pro/scripts/a.js")).isFalse();
  }

  @Test
  void should_report_a_problem_when_the_path_climbs_out() {
    assertThat(PackagePaths.pathProblems("../x")).isNotEmpty();
  }
}

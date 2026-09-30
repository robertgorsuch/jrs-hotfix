package com.jaspersoft.jrshotfix.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The build a webapp states about itself, which is the hotfix level of the files on disk. */
class InstalledBuildTest {

  /** The file as the vendor's build writes it: the two stamps indented, a ragged last line. */
  static final String VENDOR_FILE =
      "#\n"
          + "# js-pro internal labels/messages\n"
          + "\n"
          + "PRO_VERSION=10.0.0\n"
          + "PRO_URL=jasperserver-pro\n"
          + "\n"
          + "            \n"
          + "            BUILD_DATE_STAMP=20260730\n"
          + "            BUILD_TIME_STAMP=0457\n"
          + "        ";

  @TempDir Path tmp;

  private Path webappWith(String content) throws Exception {
    Path webapp = tmp.resolve("webapps/jasperserver-pro");
    Path file = webapp.resolve("WEB-INF/internal/jasperserver-pro.properties");
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.ISO_8859_1);
    return webapp;
  }

  @Test
  void should_read_release_and_build_when_the_stamps_are_indented() throws Exception {
    assertThat(InstalledBuild.ofWebapp(webappWith(VENDOR_FILE)))
        .hasValueSatisfying(
            b -> {
              assertThat(b.release()).isEqualTo("10.0.0");
              assertThat(b.build()).isEqualTo("20260730_0457");
            });
  }

  @Test
  void should_be_empty_when_the_file_is_absent() {
    assertThat(InstalledBuild.ofWebapp(tmp.resolve("webapps/jasperserver-pro"))).isEmpty();
  }

  @Test
  void should_be_empty_when_a_stamp_is_missing_or_is_not_a_date_and_time() throws Exception {
    assertThat(
            InstalledBuild.ofWebapp(webappWith("PRO_VERSION=10.0.0\nBUILD_DATE_STAMP=20260730\n")))
        .isEmpty();
    assertThat(
            InstalledBuild.ofWebapp(
                webappWith(
                    "PRO_VERSION=10.0.0\nBUILD_DATE_STAMP=@@DATE@@\nBUILD_TIME_STAMP=0457\n")))
        .isEmpty();
  }

  @Test
  void should_say_release_edition_and_build_when_describing_a_webapp() throws Exception {
    Path webapp = webappWith(VENDOR_FILE);
    Files.createDirectories(webapp.resolve("WEB-INF/lib"));
    Files.writeString(webapp.resolve("WEB-INF/lib/jasperserver-api-10.0.0.jar"), "api");
    assertThat(InstalledBuild.describe(webapp)).isEqualTo("10.0.0 PRO, build 20260730_0457");
  }

  @Test
  void should_say_release_and_edition_only_when_the_webapp_states_no_build() throws Exception {
    Path webapp = tmp.resolve("webapps/jasperserver");
    Files.createDirectories(webapp.resolve("WEB-INF/lib"));
    Files.writeString(webapp.resolve("WEB-INF/lib/jasperserver-api-10.0.0.jar"), "api");
    assertThat(InstalledBuild.describe(webapp)).isEqualTo("10.0.0 CE");
    assertThat(InstalledBuild.describe(tmp.resolve("webapps/absent-pro"))).isEqualTo("unknown PRO");
  }
}

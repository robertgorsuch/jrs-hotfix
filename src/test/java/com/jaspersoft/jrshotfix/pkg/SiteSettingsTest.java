package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.platform.Sums;
import com.jaspersoft.jrshotfix.text.PropertiesMerge;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SiteSettingsTest {

  private static final String H = PropertiesMerge.CARRIED_HEADING;

  private static Optional<String> merge(String mine, String theirs) {
    return SiteSettings.merge(
            mine.getBytes(StandardCharsets.ISO_8859_1),
            theirs.getBytes(StandardCharsets.ISO_8859_1))
        .map(SiteSettings.Merged::text);
  }

  @Test
  void writesThePackagesFinalLineEnd() {
    assertThat(merge("a=1\nb=2\n", "a=9\n")).contains("a=1\n\n" + H + "\nb=2\n");
  }

  @Test
  void writesNoFinalLineEndWhenThePackagesFileHasNone() {
    assertThat(merge("a=1\n", "a=9")).contains("a=1");
  }

  @Test
  void writesThePackagesCrlf() {
    assertThat(merge("a=1\nb=2\n", "a=9\r\nc=3\r\n"))
        .contains("a=1\r\nc=3\r\n\r\n" + H + "\r\nb=2\r\n");
  }

  @Test
  void readsTheServersCrlfAsLineEnds() {
    assertThat(merge("a=9\r\n", "a=9\n")).isEmpty();
  }

  @Test
  void anEmptyPackageFileTakesTheServersKeys() {
    assertThat(merge("a=1\n", "")).contains(H + "\na=1");
  }

  @Test
  void anEmptyServerFileKeepsNothing() {
    assertThat(merge("", "a=1\n")).isEmpty();
  }

  @Test
  void nothingToKeepLeavesThePackagesFile() {
    assertThat(merge("a=1\n", "a=1\n")).isEmpty();
  }

  @Test
  void aLoneCarriageReturnAtTheEndIsPartOfTheValue() {
    // only CR LF and LF end a line: a CR with no LF after it is a character of the value
    assertThat(merge("a=9\r", "a=9\n")).contains("a=9\r\n");
  }

  @Test
  void theWebappPredicatesAgreeWithThePackagePathOnes() {
    for (String path :
        List.of(
            "WEB-INF/js.jdbc.properties",
            "WEB-INF/classes/Hibernate.properties",
            "META-INF/context.xml",
            "META-INF/foo-jdbc.xml",
            "META-INF/sub/context.xml",
            "WEB-INF/web.xml")) {
      String packagePath = PackagePaths.WEBAPPS_PREFIX + "jasperserver-pro/" + path;
      assertThat(SiteSettings.holdsSiteValuesInWebapp(path))
          .as(path)
          .isEqualTo(SiteSettings.holdsSiteValues(packagePath));
      assertThat(SiteSettings.keptAsItIsInWebapp(path))
          .as(path)
          .isEqualTo(SiteSettings.keptAsItIs(packagePath));
    }
    assertThat(SiteSettings.holdsSiteValuesInWebapp("WEB-INF/classes/Hibernate.properties"))
        .isTrue();
    assertThat(SiteSettings.keptAsItIsInWebapp("META-INF/Context.xml")).isTrue();
    assertThat(SiteSettings.holdsSiteValues("buildomatic/js.jdbc.properties")).isFalse();
  }

  @Test
  void theHashIsOfTheWrittenBytes() {
    SiteSettings.Merged m =
        SiteSettings.merge(
                "a=1\n".getBytes(StandardCharsets.ISO_8859_1),
                "a=9\r\n".getBytes(StandardCharsets.ISO_8859_1))
            .orElseThrow();
    assertThat(m.sha256()).isEqualTo(Sums.of(m.bytes()).sha256());
    assertThat(m.kept()).containsExactly("a");
  }
}

package com.jaspersoft.jrshotfix.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.platform.Sums;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileClassTest {

  @Test
  void should_class_a_path_by_the_first_rule_that_matches() {
    assertThat(FileClass.of("WEB-INF/applicationContext-security-web.xml")).isEqualTo(FileClass.X);
    assertThat(FileClass.of("WEB-INF/web.xml")).isEqualTo(FileClass.X);
    assertThat(FileClass.of("WEB-INF/classes/mappings/thing.hbm.xml")).isEqualTo(FileClass.X);
    assertThat(FileClass.of("META-INF/context.xml")).isEqualTo(FileClass.X);
    assertThat(FileClass.of("WEB-INF/js.quartz.properties")).isEqualTo(FileClass.P);
    // X and P come before G: settings under a scripts directory are still settings
    assertThat(FileClass.of("scripts/config.properties")).isEqualTo(FileClass.P);
    assertThat(FileClass.of("scripts/_chunks/a1b2.js")).isEqualTo(FileClass.G);
    assertThat(FileClass.of("optimized-scripts/page.html")).isEqualTo(FileClass.G);
    assertThat(FileClass.of("themes/default/theme.css")).isEqualTo(FileClass.G);
    assertThat(FileClass.of("runtime/app.js.map")).isEqualTo(FileClass.G);
    assertThat(FileClass.of("WEB-INF/jsp/modules/login/login.jsp")).isEqualTo(FileClass.T);
    assertThat(FileClass.of("WEB-INF/tags/input.tag")).isEqualTo(FileClass.T);
    assertThat(FileClass.of("WEB-INF/lib/foo-1.2.3.jar")).isEqualTo(FileClass.B);
    assertThat(FileClass.of("images/logo.png")).isEqualTo(FileClass.B);
    // XML outside WEB-INF and META-INF is not reviewed configuration
    assertThat(FileClass.of("reports/sample.xml")).isEqualTo(FileClass.B);
    assertThat(FileClass.of("WEB-INF\\WEB.XML")).isEqualTo(FileClass.X);
  }

  @Test
  void should_merge_only_settings_and_pages_and_compare_every_text_class_without_line_ends() {
    assertThat(FileClass.X.mergeable()).isTrue();
    assertThat(FileClass.P.mergeable()).isTrue();
    assertThat(FileClass.T.mergeable()).isTrue();
    assertThat(FileClass.G.mergeable()).isFalse();
    assertThat(FileClass.B.mergeable()).isFalse();
    assertThat(FileClass.G.text()).isTrue();
    assertThat(FileClass.B.text()).isFalse();
  }

  private static Sums sums(String text) {
    return Sums.of(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void should_hash_text_alike_when_only_the_line_ends_differ() throws Exception {
    Sums unix = sums("a\nb\n\nc");
    Sums windows = sums("a\r\nb\r\n\r\nc");
    assertThat(windows.sha256()).isNotEqualTo(unix.sha256());
    assertThat(windows.textSha256()).isEqualTo(unix.textSha256());
    assertThat(unix.textSha256()).isEqualTo(unix.sha256());
    assertThat(windows.size()).isEqualTo(9);
    // a CR that stands alone is text: at the end, before another CR, in the middle of a line
    assertThat(sums("a\r").textSha256()).isEqualTo(sums("a\r").sha256());
    assertThat(sums("a\r\r\nb").textSha256()).isEqualTo(sums("a\r\nb").sha256());
    assertThat(sums("a\rb").textSha256()).isNotEqualTo(sums("ab").sha256());
    // the same whatever the chunks the bytes arrive in
    byte[] bytes = "one\r\ntwo\r\n".getBytes(StandardCharsets.ISO_8859_1);
    Sums.Sink sink = new Sums.Sink(java.io.OutputStream.nullOutputStream());
    for (byte b : bytes) {
      sink.write(b);
    }
    assertThat(sink.sums()).isEqualTo(Sums.of(new ByteArrayInputStream(bytes)));
  }
}

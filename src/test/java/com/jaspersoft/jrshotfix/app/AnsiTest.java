package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Review finding 3.5: colour and glyph choice are separate decisions, {@code --no-color} turns
 * colour off, the output encoding decides the glyphs, and {@code NO_COLOR} still wins over the
 * automatic choice.
 */
class AnsiTest {

  @Test
  void should_not_colour_when_no_color_is_given() {
    GlobalOptions g = new GlobalOptions();
    g.noColor = true;

    assertThat(Ansi.forStdout(g, Map.of()).enabled()).isFalse();
    assertThat(Ansi.forStdout(g, Map.of()).dim("x")).isEqualTo("x");
  }

  @Test
  void should_dim_only_when_colour_is_enabled() {
    assertThat(new Ansi(true, true).dim("x")).startsWith(Ansi.DIM).endsWith(Ansi.RESET);
    assertThat(new Ansi(false, true).dim("x")).isEqualTo("x");
  }

  @Test
  void should_not_colour_when_no_color_is_set_in_the_environment() {
    GlobalOptions g = new GlobalOptions();

    assertThat(Ansi.forStdout(g, Map.of("NO_COLOR", "1")).enabled()).isFalse();
  }

  @Test
  void should_refuse_glyphs_when_the_output_code_page_cannot_carry_them() {
    assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(Ansi.GLYPHS)).isTrue();
    assertThat(java.nio.charset.Charset.forName("IBM850").newEncoder().canEncode(Ansi.GLYPHS))
        .as("cp850 has no tick or arrows")
        .isFalse();
  }
}

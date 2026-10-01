package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code EmbeddedDoc} and the {@code --docs} option that prints it. */
class EmbeddedDocTest {

  @TempDir Path tmp;

  @Test
  void should_read_the_page_with_the_ten_sections_in_order() {
    String text = EmbeddedDoc.text();
    List<String> headings = text.lines().filter(l -> l.startsWith("#")).toList();
    assertThat(headings)
        .containsExactly(
            "# jrs-hotfix",
            "## Start",
            "## Commands",
            "## What apply does",
            "## Customized servers",
            "## Rollback",
            "## Manual steps",
            "## If something goes wrong",
            "## Files",
            "## Settings");
    assertThat(text).doesNotContain("jrsctl");
  }

  @Test
  void should_print_the_page_and_exit_0_when_docs_is_given() {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int code =
        Main.commandLine(new PrintWriter(out, true), new PrintWriter(err, true), Bootstrap.DEFAULT)
            .execute("--docs");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("# jrs-hotfix").contains("## Settings");
    assertThat(err.toString()).isEmpty();
  }

  @Test
  void should_not_open_the_home_when_docs_is_given() throws IOException {
    Path home = tmp.resolve("no-such-home");
    StringWriter out = new StringWriter();
    int code =
        Main.commandLine(
                new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true),
                Bootstrap.DEFAULT)
            .execute("--docs", "--home", home.toString());
    assertThat(code).isZero();
    assertThat(Files.exists(home)).isFalse();
  }
}

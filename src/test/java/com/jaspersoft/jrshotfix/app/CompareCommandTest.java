package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.Wars;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jrs-hotfix compare}: the report, the exit codes, {@code --out}, {@code --show}. */
class CompareCommandTest {

  @TempDir Path tmp;

  private final List<CommandsTest.Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void close() {
    fixtures.forEach(CommandsTest.Fixture::close);
  }

  private CommandsTest.Fixture fixture() throws Exception {
    CommandsTest.Fixture f = CommandsTest.Fixture.create(tmp);
    fixtures.add(f);
    return f;
  }

  private Path war(String name, Map<String, String> edits) throws Exception {
    Map<String, String> files = new LinkedHashMap<>(Wars.vendor());
    files.putAll(edits);
    return Wars.war(tmp.resolve("wars/" + name), files);
  }

  @Test
  void should_exit_0_when_the_same_and_7_when_different_without_any_home() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path a = war("a.war", Map.of());
    Path b = war("b.war", Map.of());
    Path c = war("c.war", Map.of(Wars.WEB_XML, "<web-app/>\n"));

    assertThat(f.runExactly(List.of("compare", a.toString(), b.toString()))).isZero();
    assertThat(f.out()).contains("webapp: the same");

    assertThat(f.runExactly(List.of("compare", a.toString(), c.toString()))).isEqualTo(7);
    assertThat(f.out()).containsPattern("differs +X +" + Wars.WEB_XML).contains("1 differ");
  }

  @Test
  void should_write_a_three_way_result_and_refuse_a_wrong_out() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path base = war("base.war", Map.of());
    Path mine =
        war("mine.war", Map.of(Wars.SECURITY, Wars.vendor().get(Wars.SECURITY) + "mine=1\n"));
    Path theirs = war("theirs.war", Map.of(Wars.LOGIN, "<h1>new</h1>\n"));
    Path out = tmp.resolve("result");

    assertThat(
            f.runExactly(
                List.of("compare", base.toString(), mine.toString(), "--out", out.toString())))
        .isEqualTo(1);
    Files.createDirectories(out);
    Files.writeString(out.resolve("x"), "x");
    assertThat(
            f.runExactly(
                List.of(
                    "compare",
                    base.toString(),
                    mine.toString(),
                    theirs.toString(),
                    "--out",
                    out.toString())))
        .isEqualTo(2);

    Path fresh = tmp.resolve("fresh");
    assertThat(
            f.runExactly(
                List.of(
                    "compare",
                    base.toString(),
                    mine.toString(),
                    theirs.toString(),
                    "--out",
                    fresh.toString())))
        .isEqualTo(7);
    assertThat(f.out()).contains("1 in mine only, 1 in theirs only").contains("0 conflict(s)");
    assertThat(Files.readString(fresh.resolve("webapp").resolve(Wars.SECURITY))).contains("mine=1");
    assertThat(fresh.resolve("webapp").resolve(Wars.LOGIN)).hasContent("<h1>new</h1>");
  }

  @Test
  void should_show_one_files_differences() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path a = war("a.war", Map.of());
    Path c = war("c.war", Map.of(Wars.WEB_XML, "<web-app/>\n"));

    assertThat(f.runExactly(List.of("compare", a.toString(), c.toString(), "--show", Wars.WEB_XML)))
        .isEqualTo(7);
    assertThat(f.out()).contains("+<web-app/>");
  }

  @Test
  void should_compare_the_server_with_the_vendors_war() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path vendor = war("vendor.war", Map.of());
    Files.writeString(
        f.hf.settings.webappDir().resolve("WEB-INF/site.txt"),
        "ours\n",
        StandardCharsets.ISO_8859_1);

    assertThat(f.run("compare", vendor.toString(), "server")).isEqualTo(7);
    assertThat(f.out()).contains("against server").contains("WEB-INF/site.txt");
  }
}

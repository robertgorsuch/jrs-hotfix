package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.hotfix.HotfixFixture;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.redact.Redactor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the operator reads before confirming a plan. */
class PlanPrinterTest {

  @TempDir Path tmp;

  private static String printed(HotfixFixture f, Plan plan) {
    StringWriter text = new StringWriter();
    PlanPrinter.print(new PrintWriter(text), f.home, plan, new Ansi(false, false), new Redactor());
    return text.toString();
  }

  private static Path packageWithNotes(Path file, int lines) throws Exception {
    StringBuilder readme = new StringBuilder("Additional Notes:\n");
    for (int i = 1; i <= lines; i++) {
      readme.append("step ").append(i).append(";\n");
    }
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        Packages.zipBytes(Map.of(Packages.LIB + "foo-1.2.3.jar", "patched"), readme.toString()));
    return Packages.zip(file, outer);
  }

  @Test
  void should_show_the_start_of_the_readme_text_and_say_where_the_rest_is_when_it_is_long()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan =
          f.plans.planApply(
              new HotfixPlans.ApplyArgs(packageWithNotes(tmp.resolve("dl/long.zip"), 70), true));
      String text = printed(f, plan);
      assertThat(text).contains("step 1;").doesNotContain("step 70;");
      assertThat(text).containsPattern("6[0-9] more lines").contains("notes.txt");
      // the whole text is still what the run saves and prints at its end
      List<String> notes = HotfixPlans.notesOf(plan);
      assertThat(notes).contains("step 1;", "step 70;");
    }
  }

  @Test
  void should_show_every_line_of_the_readme_text_when_it_is_short() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan =
          f.plans.planApply(
              new HotfixPlans.ApplyArgs(packageWithNotes(tmp.resolve("dl/short.zip"), 3), true));
      String text = printed(f, plan);
      assertThat(text).contains("step 1;", "step 2;", "step 3;").doesNotContain("more line");
    }
  }
}

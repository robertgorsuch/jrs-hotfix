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
import java.nio.file.Files;
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
  void should_count_the_files_by_area_and_action_when_printing_an_apply_plan() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      String text = printed(f, f.plan());
      assertThat(text).contains("5 files: 1 added, 2 replaced, 2 deleted");
      assertThat(text).containsPattern("webapp libraries +4: 1 added, 1 replaced, 2 deleted");
      assertThat(text).containsPattern("installation tree +1: 1 replaced");
      assertThat(text).contains("jrs-hotfix verify");
      // counted, not listed
      assertThat(text).doesNotContain(f.target(HotfixFixture.FOO).toString());
    }
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

  @Test
  void should_count_what_a_rollback_puts_back_when_printing_a_rollback_plan() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.run(f.plan(), "r1");
      Plan plan = f.plans.planRollback();
      String text = printed(f, plan);
      assertThat(text).contains("5 files: 2 restored, 1 removed, 2 put back");
    }
  }

  @Test
  void should_say_that_the_run_will_be_refused_when_the_hotfix_is_already_on_the_server()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Files.writeString(f.target(HotfixFixture.FOO), "patched foo");
      Files.writeString(f.target(HotfixFixture.NEW), "brand new");
      Files.writeString(f.target(HotfixFixture.TOOL), "patched tool");
      Files.delete(f.target(HotfixFixture.BAR));
      Files.delete(f.target(HotfixFixture.FOO_OLDER));
      String text = printed(f, f.plan());
      assertThat(text).contains("this plan will be refused").contains("is already on this server");
    }
  }

  @Test
  void should_not_speak_of_a_refusal_when_the_package_applies() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(printed(f, f.plan())).doesNotContain("will be refused");
    }
  }
}

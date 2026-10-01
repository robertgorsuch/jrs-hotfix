package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.pkg.Action;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.Packages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The field case of 2026-09-30: a hotfix brings a newer version of a library the hotfix before it
 * brought, and names the older one in no list. The older one is the vendor's, known to the undo,
 * and is deleted as superseded; a library the site added never is.
 */
class SupersededLibrariesTest {

  private static final String OLDER = "webapps/jasperserver-pro/WEB-INF/lib/widget-2.25.3.jar";
  private static final String NEWER = "webapps/jasperserver-pro/WEB-INF/lib/widget-2.25.4.jar";
  private static final String SITES = "webapps/jasperserver-pro/WEB-INF/lib/widget-2.20.0.jar";

  @TempDir Path tmp;

  /** A package of {@code build} that adds one jar under {@code WEB-INF/lib}. */
  private Path hotfix(String name, String build, String jar) throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put(
        "readme.txt",
        Packages.OUTER_README
            .replace("[20260730_0457]", "[" + build + "]")
            .getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        Packages.zipBytes(
            Map.of(Packages.LIB + jar, "widget " + jar),
            "Added files:\n" + Packages.LIB + jar + "\n"));
    return Packages.zip(tmp.resolve("dl/" + name), outer);
  }

  private static PackageContents contents(Plan plan) {
    return ((ApplySteps.ReadOnlyStep) HotfixFixture.step(plan, "preflight")).in.contents();
  }

  @Test
  void should_delete_the_older_version_the_undo_knows_and_leave_the_sites_when_no_list_names_them()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path first = hotfix("first.zip", "20260428_1437", "widget-2.25.3.jar");
      assertThat(
              f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(first, true)), "r-first")
                  .exitCode())
          .isZero();
      assertThat(f.target(OLDER)).exists();
      // a jar of the site's own, of the same artifact and an older version still
      Files.writeString(f.target(SITES), "the site's own");

      Path second = hotfix("second.zip", "20260730_0457", "widget-2.25.4.jar");
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(second, true));
      PackageContents c = contents(plan);
      assertThat(c.superseded()).containsExactly(OLDER);
      assertThat(c.deletes()).extracting(PackageContents.Entry::path).containsExactly(OLDER);
      assertThat(c.noteLines())
          .anySatisfy(
              n ->
                  assertThat(n)
                      .contains("deleted as superseded")
                      .contains("widget-2.25.3.jar (the package brings widget-2.25.4.jar)"))
          .anySatisfy(
              n -> assertThat(n).contains("widget-2.20.0.jar").contains("nothing is deleted"));
      assertThat(plan.summary().changes())
          .contains("Superseded libraries deleted (1)", "  widget-2.25.3.jar");

      assertThat(f.run(plan, "r-second").exitCode()).isZero();
      assertThat(f.target(OLDER)).doesNotExist();
      assertThat(f.target(NEWER)).exists();
      assertThat(f.target(SITES)).exists();
      assertThat(f.undo.read().orElseThrow().files())
          .anySatisfy(
              o -> {
                assertThat(o.path()).isEqualTo(f.target(OLDER));
                assertThat(o.action()).isEqualTo("delete");
              });

      // the rollback puts the superseded library back
      Plan rollback = f.plans.planRollback();
      assertThat(f.run(rollback, "r-back").exitCode()).isZero();
      assertThat(Files.readString(f.target(OLDER))).isEqualTo("widget widget-2.25.3.jar");
      assertThat(f.target(NEWER)).doesNotExist();
    }
  }

  @Test
  void should_leave_the_older_version_when_asked_to_keep_superseded_libraries() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path first = hotfix("first.zip", "20260428_1437", "widget-2.25.3.jar");
      assertThat(
              f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(first, true)), "r-first")
                  .exitCode())
          .isZero();
      Path second = hotfix("second.zip", "20260730_0457", "widget-2.25.4.jar");
      HotfixPlans.ApplyArgs args = new HotfixPlans.ApplyArgs(second, true).keepingSuperseded();
      Plan plan = f.plans.planApply(args);
      assertThat(contents(plan).superseded()).isEmpty();
      assertThat(contents(plan).deletes()).isEmpty();
      assertThat(contents(plan).noteLines())
          .anySatisfy(n -> assertThat(n).contains("left as --keep-superseded asks"));
      assertThat(HotfixPlans.applyArgs(HotfixPlans.applyArgsJson(args)).keepSuperseded()).isTrue();
      assertThat(
              HotfixPlans.applyArgs(
                      HotfixPlans.applyArgsJson(new HotfixPlans.ApplyArgs(second, true)))
                  .keepSuperseded())
          .isFalse();
      assertThat(f.run(plan, "r-second").exitCode()).isZero();
      assertThat(f.target(OLDER)).exists();
      assertThat(f.target(NEWER)).exists();
    }
  }

  /** A jar that is a web fragment, as log4j-jakarta-web is. */
  private static byte[] fragmentJar(String fragmentName) throws Exception {
    return Packages.zipBytes(
        Map.of(
            "META-INF/web-fragment.xml",
            "<web-fragment><name>" + fragmentName + "</name></web-fragment>"),
        null);
  }

  @Test
  void should_refuse_when_an_unknown_older_version_is_a_web_fragment_the_package_brings_again()
      throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      // the tester's case: log4j-jakarta-web 2.25.3 from a hotfix applied by hand, never recorded
      String older = "webapps/jasperserver-pro/WEB-INF/lib/log4j-jakarta-web-2.25.3.jar";
      Files.write(f.target(older), fragmentJar("log4j"));
      Map<String, byte[]> outer = new LinkedHashMap<>();
      outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
      Map<String, byte[]> payload = new LinkedHashMap<>();
      payload.put(Packages.LIB + "log4j-jakarta-web-2.25.4.jar", fragmentJar("log4j"));
      outer.put(
          "jasperserver-pro.zip",
          Packages.zip(tmp.resolve("inner.zip"), payload).toFile().exists()
              ? Files.readAllBytes(tmp.resolve("inner.zip"))
              : new byte[0]);
      Path pkg = Packages.zip(tmp.resolve("dl/log4j.zip"), outer);

      HotfixPlans.VerifyReport report = f.plans.verify(pkg);
      assertThat(report.applicable()).isFalse();
      assertThat(report.problems())
          .singleElement()
          .satisfies(
              p ->
                  assertThat(p)
                      .contains("log4j-jakarta-web-2.25.3.jar is a web fragment named log4j")
                      .contains("the package brings log4j-jakarta-web-2.25.4.jar")
                      .contains("Tomcat refuses to deploy")
                      .contains("`jrs-hotfix baseline add`"));
      Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(pkg, true));
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("will be refused").contains("web fragment"));
      assertThat(f.run(plan, "r-refused").exitCode()).isEqualTo(2);
      assertThat(f.target(older)).exists();

      // once the earlier hotfix is known (here: the undo of the apply that brought the jar), it
      // is deleted
      com.jaspersoft.jrshotfix.platform.Durability.writeAtomically(
          f.home.undo().resolve(com.jaspersoft.jrshotfix.state.UndoStore.RECORD),
          com.jaspersoft.jrshotfix.json.Json.writePretty(
              new com.jaspersoft.jrshotfix.state.UndoRecord(
                  "JRSHF-10.0.0-20260428-1437",
                  "10.0.0",
                  "PRO",
                  "20260428_1437",
                  "the earlier hotfix",
                  "r-earlier",
                  java.time.Instant.parse("2026-04-28T14:37:00Z"),
                  java.util.List.of(
                      new com.jaspersoft.jrshotfix.state.OwnedFile(
                          f.target(older),
                          "add",
                          java.util.Optional.empty(),
                          java.util.Optional.of("x"))),
                  java.util.List.of(),
                  java.util.Optional.empty(),
                  java.util.List.of())));
      assertThat(f.plans.verify(pkg).problems()).isEmpty();
      Plan again = f.plans.planApply(new HotfixPlans.ApplyArgs(pkg, true));
      assertThat(contents(again).superseded()).containsExactly(older);
      assertThat(f.run(again, "r-applied").exitCode()).isZero();
      assertThat(f.target(older)).doesNotExist();
    }
  }

  @Test
  void should_only_warn_when_nothing_knows_the_older_version_as_the_vendors() throws Exception {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      // the earlier hotfix was applied by hand and never recorded: the jar is nobody's
      Files.writeString(f.target(OLDER), "from a hotfix applied by hand");
      Path second = hotfix("second.zip", "20260730_0457", "widget-2.25.4.jar");
      PackageContents c = contents(f.plans.planApply(new HotfixPlans.ApplyArgs(second, true)));
      assertThat(c.superseded()).isEmpty();
      assertThat(c.deletes()).isEmpty();
      assertThat(c.noteLines())
          .anySatisfy(
              n ->
                  assertThat(n)
                      .contains("widget-2.25.3.jar (the package brings widget-2.25.4.jar)")
                      .contains("neither the latest apply nor a baseline knows it")
                      .contains("nothing is deleted"));
      assertThat(c.entries().stream().filter(e -> e.action() == Action.DELETE)).isEmpty();
    }
  }
}

package com.jaspersoft.jrshotfix.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.hotfix.SiteFixture;
import com.jaspersoft.jrshotfix.pkg.Packages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scenarios of the 0.2 design (10 to 18): a hotfix on a server whose site changed the vendor's
 * files, driven through the shaded jar. Invariants: as {@link ScenarioTest}; the vendor's WAR, the
 * packages and the site's edits are inputs the test builds, and everything the tool does to them
 * goes through {@link Fixture#cli}.
 */
class CustomizedServerTest {

  private static final String WEBAPP = "webapps/jasperserver-pro/";
  private static final Pattern MERGE_ID = Pattern.compile("m-\\d{8}-\\d{6}-[0-9a-f]{4}");

  @TempDir Path tmp;

  /** The vendor's webapp laid down as an installer leaves it; the baseline is not added yet. */
  private Path install(Fixture f) throws Exception {
    Path webapp = f.target(WEBAPP + "WEB-INF").getParent();
    Wars.installAsTheInstallerDoes(webapp);
    return webapp;
  }

  private void addBaseline(Fixture f) throws Exception {
    Path war = Wars.war(tmp.resolve("packages/jasperserver-pro.war"), Wars.vendor());
    f.cli.run("baseline", "add", war.toString()).assertExit(0);
  }

  private Path hotfix(String name, String build, Map<String, String> payload, String readme)
      throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put(
        "readme.txt",
        Packages.OUTER_README
            .replace("[20260730_0457]", "[" + build + "]")
            .getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(payload, readme));
    return Packages.zip(tmp.resolve("packages/" + name), outer);
  }

  private Path hotfix() throws Exception {
    return hotfix("hotfix-site.zip", "20260730_0457", SiteFixture.hotfixPayload(), null);
  }

  private static String vendor(String path) {
    return Wars.vendor().get(path);
  }

  private static String read(Path webapp, String path) throws Exception {
    return Files.readString(webapp.resolve(path), StandardCharsets.ISO_8859_1);
  }

  private static String mergeId(String output) {
    Matcher m = MERGE_ID.matcher(output);
    assertThat(m.find()).as("a merge id in:\n%s", output).isTrue();
    return m.group();
  }

  @Test
  void s10_s12_s13_should_say_what_the_site_changed_and_keep_it_when_the_hotfix_does_not_touch_it()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      f.cli.run("scan").assertExit(2);
      addBaseline(f);
      assertThat(f.cli.run("scan").assertExit(0).stdout())
          .contains("vanilla: no vendor file was changed");

      String siteContext = vendor(Wars.CONTEXT).replace("\"4\"", "\"16\"");
      Wars.write(webapp, Wars.CONTEXT, siteContext);
      Wars.write(webapp, "WEB-INF/jsp/site.jsp", "<p>ours</p>\n");
      assertThat(f.cli.run("scan").assertExit(0).stdout())
          .contains("customized: 1 changed, 1 added, 0 removed")
          .containsPattern("CHANGED +X +" + Wars.CONTEXT)
          .containsPattern("ADDED +T +WEB-INF/jsp/site.jsp");

      // the package ships the context as the vendor has it: the site's copy is kept, and the
      // apply needs no decision from anyone
      Cli.Result apply = f.cli.run("apply", hotfix().toString(), "--yes").assertExit(0);
      assertThat(apply.stdout()).contains("Kept as the site has it (2)").contains(Wars.CONTEXT);
      assertThat(read(webapp, Wars.CONTEXT)).isEqualTo(siteContext);
      assertThat(read(webapp, "WEB-INF/jsp/site.jsp")).isEqualTo("<p>ours</p>\n");
      assertThat(read(webapp, Wars.WEB_XML)).contains(">main2<");
      assertThat(read(webapp, Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
      assertThat(f.listRow(SiteFixture.HOTFIX_ID)).contains("INSTALLED");
      assertThat(f.tomcatRunning()).isTrue();
      // the hotfix is the vendor's level now: what the site changed is still what differs
      assertThat(f.cli.run("scan").assertExit(0).stdout())
          .contains("baseline: " + Wars.RELEASE_ID + " + " + SiteFixture.HOTFIX_ID)
          .contains("customized: 1 changed, 1 added, 0 removed");
    }
  }

  @Test
  void s11_should_notice_a_hotfix_applied_by_hand_when_the_webapp_states_a_newer_build()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      f.cli.run("apply", hotfix().toString(), "--yes").assertExit(0);
      // someone copies a later hotfix's files in by hand
      Wars.write(webapp, Wars.STAMPS, Wars.stamps("20260815", "0000"));
      Map<String, String> payload = new LinkedHashMap<>(SiteFixture.hotfixPayload());
      payload.put(Wars.STAMPS, Wars.stamps("20260830", "0100"));
      Path next = hotfix("next.zip", "20260830_0100", payload, null);

      Cli.Result plan = f.cli.run("apply", next.toString(), "--plan").assertExit(0);
      assertThat(plan.stdout())
          .contains("a hotfix with build 20260815_0000 was applied outside jrs-hotfix");
      assertThat(f.cli.run("list").assertExit(0).stdout()).contains("build 20260815_0000");

      // with a baseline the tool must know that hotfix's files before it compares anything
      addBaseline(f);
      Cli.Result refused = f.cli.run("apply", next.toString(), "--yes").assertExit(2);
      assertThat(refused.stderr())
          .contains("build 20260815_0000")
          .contains("jrs-hotfix baseline add <package.zip>");
      assertThat(read(webapp, Wars.STAMPS)).isEqualTo(Wars.stamps("20260815", "0000"));
    }
  }

  @Test
  void s14_should_merge_a_properties_file_by_key_and_report_the_keys_both_changed()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      addBaseline(f);
      // allow.list only the site changed; max.upload both changed
      Wars.write(
          webapp, Wars.SECURITY, "# security\nmax.upload=50\nallow.list=a,b,c\nstrict=true\n");
      Path zip = hotfix();

      Cli.Result refused = f.cli.run("apply", zip.toString(), "--yes").assertExit(2);
      assertThat(refused.stderr()).contains(Wars.SECURITY + " (CONFLICT)");
      String id = mergeId(refused.stderr());
      assertThat(f.cli.run("merge", "status", id).assertExit(2).stdout())
          .contains("changed by both, to resolve: max.upload")
          .contains("site values kept: allow.list");
      assertThat(read(webapp, Wars.SECURITY)).contains("max.upload=50");
      assertThat(f.tomcatRunning()).as("nothing was stopped").isTrue();

      // the site's value wins by rule, with the vendor's beside it
      Cli.Result prepared =
          f.cli.run("merge", "prepare", zip.toString(), "--on-conflict", "mine").assertExit(0);
      String mine = mergeId(prepared.stdout());
      f.cli.run("apply", zip.toString(), "--merge", mine, "--yes").assertExit(0);
      assertThat(read(webapp, Wars.SECURITY))
          .isEqualTo(
              "# security\n"
                  + "# jrs-hotfix: the hotfix's value, not used on this server:\n"
                  + "# max.upload=20\n"
                  + "max.upload=50\n"
                  + "allow.list=a,b,c\n"
                  + "strict=true\n"
                  + "fresh=1\n");
    }
  }

  @Test
  void s15_s16_should_wait_for_the_operator_on_web_xml_then_apply_and_roll_back_exactly()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      addBaseline(f);
      String siteWeb = vendor(Wars.WEB_XML).replace(">main<", ">site-main<");
      String siteLogin = vendor(Wars.LOGIN).replace("<p>Welcome</p>", "<p>Welcome to ACME</p>");
      Wars.write(webapp, Wars.WEB_XML, siteWeb);
      Wars.write(webapp, Wars.LOGIN, siteLogin);
      Path zip = hotfix();

      Cli.Result refused = f.cli.run("apply", zip.toString(), "--yes").assertExit(2);
      String id = mergeId(refused.stderr());
      assertThat(refused.stderr()).contains(Wars.WEB_XML + " (CONFLICT)");
      assertThat(read(webapp, Wars.WEB_XML)).isEqualTo(siteWeb);
      assertThat(f.cli.run("merge", "show", id, Wars.WEB_XML).assertExit(0).stdout())
          .contains("-    <servlet-name>main</servlet-name>")
          .contains("+    <servlet-name>site-main</servlet-name>")
          .contains("+    <servlet-name>main2</servlet-name>");

      // a file with a marker left in it is refused; the operator's own merged file is taken
      f.cli.run("merge", "resolve", id, Wars.WEB_XML, "--merged").assertExit(2);
      Path own = tmp.resolve("web.merged.xml");
      String resolved = vendor(Wars.WEB_XML).replace(">main<", ">site-main2<");
      Files.writeString(own, resolved, StandardCharsets.ISO_8859_1);
      f.cli.run("merge", "resolve", id, Wars.WEB_XML, "--merged", own.toString()).assertExit(0);
      f.cli.run("merge", "status", id).assertExit(0);

      f.cli.run("apply", zip.toString(), "--yes").assertExit(0);
      assertThat(read(webapp, Wars.WEB_XML)).isEqualTo(resolved);
      assertThat(read(webapp, Wars.LOGIN))
          .contains("<h1>Sign in</h1>")
          .contains("<p>Welcome to ACME</p>");
      assertThat(f.cli.run("verify", zip.toString()).assertExit(2).stdout())
          .contains("merged by jrs-hotfix with this site's file");

      f.cli.run("rollback", SiteFixture.HOTFIX_ID, "--yes").assertExit(0);
      assertThat(read(webapp, Wars.WEB_XML)).isEqualTo(siteWeb);
      assertThat(read(webapp, Wars.LOGIN)).isEqualTo(siteLogin);
      assertThat(read(webapp, Wars.SECURITY)).isEqualTo(vendor(Wars.SECURITY));
      assertThat(read(webapp, Wars.STAMPS)).isEqualTo(vendor(Wars.STAMPS));
      assertThat(f.tomcatRunning()).isTrue();
    }
  }

  @Test
  void s17_should_keep_the_installers_values_with_and_without_a_baseline() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      String container = read(webapp, Wars.CONTAINER);
      Path zip = hotfix();
      // without a baseline: as 0.1, with the installer's files merged or kept
      f.cli.run("apply", zip.toString(), "--yes").assertExit(0);
      assertThat(read(webapp, Wars.QUARTZ))
          .isEqualTo(
              "# scheduler\nreport.scheduler.web.deployment.uri=http://reports:8081/x\nnew.key=1\n");
      assertThat(read(webapp, Wars.CONTAINER)).isEqualTo(container);
      f.cli.run("rollback", SiteFixture.HOTFIX_ID, "--yes").assertExit(0);
      f.cli.run("runs", "prune", "--older-than", "0").assertExit(0);

      // with one: the same result, through the merge
      addBaseline(f);
      Cli.Result apply = f.cli.run("apply", zip.toString(), "--yes").assertExit(0);
      assertThat(apply.stdout()).contains(Wars.QUARTZ + "  (P)  merged automatically");
      assertThat(read(webapp, Wars.QUARTZ))
          .isEqualTo(
              "# scheduler\nreport.scheduler.web.deployment.uri=http://reports:8081/x\nnew.key=1\n");
      assertThat(read(webapp, Wars.CONTAINER)).isEqualTo(container);
    }
  }

  @Test
  void s18_should_never_delete_a_library_the_site_added_when_a_readme_pattern_matches_it()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      addBaseline(f);
      Wars.write(webapp, Packages.LIB + "foo-site-driver.jar", "the site's own");
      String readme =
          "Deleted files:\n"
              + Packages.LIB
              + "bar-0.9.jar\nIMPORTANT\n"
              + Packages.LIB
              + "foo-*.jar\n";
      Path zip = hotfix("globs.zip", "20260730_0457", SiteFixture.hotfixPayload(), readme);

      Cli.Result apply = f.cli.run("apply", zip.toString(), "--yes").assertExit(0);
      assertThat(apply.stdout()).contains("it is this site's own file");
      assertThat(read(webapp, Packages.LIB + "foo-site-driver.jar")).isEqualTo("the site's own");
      // the vendor's own leftovers go, as the readme says
      assertThat(webapp.resolve(Packages.LIB + "foo-1.0.0.jar")).doesNotExist();
      assertThat(webapp.resolve(Packages.LIB + "bar-0.9.jar")).doesNotExist();
      assertThat(read(webapp, Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
    }
  }

  @Test
  void should_refuse_with_exit_2_when_a_file_is_edited_between_the_merge_and_the_apply()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      addBaseline(f);
      Path zip = hotfix();
      String id = mergeId(f.cli.run("merge", "prepare", zip.toString()).assertExit(0).stdout());
      String edited = vendor(Wars.LOGIN).replace("Welcome", "Hello");
      Wars.write(webapp, Wars.LOGIN, edited);

      Cli.Result refused = f.cli.run("apply", zip.toString(), "--merge", id, "--yes").assertExit(2);
      assertThat(refused.stderr())
          .contains("changed since the merge was prepared: " + Wars.LOGIN)
          .contains("jrs-hotfix merge prepare");
      assertThat(read(webapp, Wars.LOGIN)).isEqualTo(edited);
      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.tomcatRunning()).isTrue();
    }
  }

  @Test
  void should_resume_with_the_merged_files_when_killed_after_staging() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Path webapp = install(f);
      addBaseline(f);
      String siteContext = vendor(Wars.CONTEXT).replace("\"4\"", "\"16\"");
      Wars.write(webapp, Wars.CONTEXT, siteContext);
      Wars.write(
          webapp,
          Wars.SECURITY,
          vendor(Wars.SECURITY).replace("allow.list=a,b", "allow.list=a,b,c"));
      Path zip = hotfix();

      Crash.kill(Crash.startAndPauseAt(f, "atomic-swap", "apply", zip.toString(), "--yes"));
      assertThat(read(webapp, Wars.SECURITY)).contains("max.upload=10");
      String runId = f.pendingRunId();

      f.cli.run("runs", "resume", runId, "--yes").assertExit(0);

      assertThat(read(webapp, Wars.SECURITY))
          .isEqualTo("# security\nmax.upload=20\nallow.list=a,b,c\nstrict=true\nfresh=1\n");
      assertThat(read(webapp, Wars.CONTEXT)).isEqualTo(siteContext);
      assertThat(read(webapp, Wars.WEB_XML)).contains(">main2<");
      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.listRow(SiteFixture.HOTFIX_ID)).contains("INSTALLED");
      assertThat(f.tomcatRunning()).as("started by the resume").isTrue();
    }
  }
}

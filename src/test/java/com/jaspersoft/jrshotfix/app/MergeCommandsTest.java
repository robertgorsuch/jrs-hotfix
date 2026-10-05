package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.hotfix.SiteFixture;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.pkg.Packages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code merge} and {@code apply} on a customized server, end to end through the commands. */
class MergeCommandsTest {

  @TempDir Path tmp;

  private final List<CommandsTest.Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void stopServers() {
    fixtures.forEach(CommandsTest.Fixture::close);
  }

  private CommandsTest.Fixture fixture() throws Exception {
    CommandsTest.Fixture f = CommandsTest.Fixture.create(tmp);
    fixtures.add(f);
    return f;
  }

  /** The id of the one merge in the home. */
  private static String mergeId(CommandsTest.Fixture f) {
    List<MergeDoc> merges = f.hf.runtime.merges().list();
    assertThat(merges).hasSize(1);
    return merges.get(0).id();
  }

  @Test
  void should_find_the_waiting_merge_of_exactly_this_package_for_the_menu() throws Exception {
    CommandsTest.Fixture f = fixture();
    SiteFixture s = SiteFixture.create(f.hf, tmp);
    s.customizeWithoutCollisions();
    Path zip = s.hotfix();
    assertThat(f.run("apply", zip.toString(), "--yes")).isEqualTo(2);
    GlobalOptions g = new GlobalOptions();
    g.home = f.home.root();
    RootCommand.Session session =
        new RootCommand.Session(Bootstrap.opener(prompt -> f.hf.platform, f.env()), g);

    assertThat(session.waitingMerge(zip))
        .contains(new Menu.WaitingMerge(mergeId(f), List.of(Wars.WEB_XML)));
    // a merge is matched by the package's bytes: any other file has none waiting
    Path other = Files.writeString(tmp.resolve("other.zip"), "not the package");
    assertThat(session.waitingMerge(other)).isEmpty();

    assertThat(f.run("merge", "resolve", mergeId(f), Wars.WEB_XML, "--merged")).isEqualTo(0);
    assertThat(session.waitingMerge(zip)).isEmpty();
  }

  @Test
  void should_apply_a_hotfix_to_a_customized_server_keeping_and_merging_the_sites_changes()
      throws Exception {
    CommandsTest.Fixture f = fixture();
    SiteFixture s = SiteFixture.create(f.hf, tmp);
    s.customizeWithoutCollisions();
    Path zip = s.hotfix();

    // web.xml merged cleanly, but an XML configuration file always waits for the operator
    assertThat(f.run("apply", zip.toString(), "--yes")).isEqualTo(2);
    assertThat(f.err())
        .contains("1 file(s) changed by both this site and " + SiteFixture.HOTFIX_ID)
        .contains(Wars.WEB_XML + " (REVIEW)")
        .contains("nothing was changed on the server")
        .contains("jrs-hotfix merge resolve");
    assertThat(s.read(Wars.WEB_XML)).contains(">60<").doesNotContain("main2");
    String id = mergeId(f);

    assertThat(f.run("merge", "list")).isEqualTo(0);
    assertThat(f.out()).contains(id).contains(SiteFixture.HOTFIX_ID);
    // the list is its own command since 0.6: status needs the id
    assertThat(f.run("merge", "status")).isEqualTo(1);
    assertThat(f.run("merge", "status", id)).isEqualTo(2);
    assertThat(f.out())
        .contains("merge " + id + " for " + SiteFixture.HOTFIX_ID)
        .contains("baseline  " + Wars.RELEASE_ID)
        .containsPattern("REVIEW +X  " + Wars.WEB_XML)
        .containsPattern("KEPT +X  " + Wars.CONTEXT)
        .containsPattern("AUTO +P  " + Wars.SECURITY)
        .containsPattern("OVERWRITTEN +G  " + Wars.SCRIPT)
        .contains("1 waiting for you");

    assertThat(f.run("merge", "show", id, Wars.WEB_XML)).isEqualTo(0);
    assertThat(f.out())
        .contains("What this site changed")
        .contains("-    <session-timeout>20</session-timeout>")
        .contains("+    <session-timeout>60</session-timeout>")
        .contains("What the hotfix changed")
        .contains("+    <servlet-name>main2</servlet-name>")
        .contains("The merged file: ");

    assertThat(f.run("merge", "resolve", id, Wars.WEB_XML, "--merged")).isEqualTo(0);
    assertThat(f.out()).contains(Wars.WEB_XML + ": resolved").contains("0 file(s) still wait");
    assertThat(f.run("merge", "status", id)).isEqualTo(0);

    // the same merge is met again, now with nothing left to decide
    assertThat(f.run("apply", zip.toString(), "--plan")).isEqualTo(0);
    assertThat(f.out())
        .contains("merge " + id + " against " + Wars.RELEASE_ID)
        .contains("Merged (4)")
        .contains("Kept as the site has it (2)")
        .contains("Replaced although the site changed it (1)");
    assertThat(f.run("apply", zip.toString(), "--yes")).isEqualTo(0);
    assertThat(mergeId(f)).isEqualTo(id);
    assertThat(s.read(Wars.WEB_XML)).contains(">60<").contains(">main2<");
    assertThat(s.read(Wars.CONTEXT)).contains("\"16\"");
    assertThat(s.read(Wars.SECURITY)).contains("allow.list=a,b,c").contains("max.upload=20");

    assertThat(f.run("verify", zip.toString())).isEqualTo(2);
    assertThat(f.out())
        .contains(s.webapp.resolve(Wars.SECURITY) + ": merged by jrs-hotfix with this site's file")
        .contains(s.webapp.resolve(Wars.CONTEXT) + ": kept as the site has it");
    // the merge is the record of how the hotfix was applied
    assertThat(f.run("merge", "discard", id)).isEqualTo(2);
    assertThat(f.err()).contains("is the record of how " + SiteFixture.HOTFIX_ID + " was applied");
    assertThat(f.run("runs", "prune", "--older-than", "0")).isEqualTo(0);
    assertThat(f.out()).contains("merges removed      none");

    // a rollback brings the site's files back
    assertThat(f.run("rollback", "--yes")).isEqualTo(0);
    assertThat(s.read(Wars.WEB_XML)).contains(">60<").doesNotContain("main2");
    assertThat(s.read(Wars.SECURITY)).contains("allow.list=a,b,c").contains("max.upload=10");
    assertThat(f.run("scan")).isEqualTo(0);
    assertThat(f.out()).contains("customized: 5 changed, 0 added, 0 removed");
  }

  @Test
  void should_exit_2_under_the_fail_rule_and_0_under_ask_when_a_prepared_merge_has_conflicts()
      throws Exception {
    CommandsTest.Fixture f = fixture();
    SiteFixture s = SiteFixture.create(f.hf, tmp);
    s.customizeWithCollisions();
    Path zip = s.hotfix();
    // --non-interactive, so the rule is fail
    assertThat(f.run("merge", "prepare", zip.toString())).isEqualTo(2);
    assertThat(f.out())
        .containsPattern("CONFLICT +P  " + Wars.SECURITY)
        .contains("changed by both, to resolve: max.upload")
        .contains("workspace: ");
    assertThat(f.run("merge", "prepare", zip.toString(), "--on-conflict", "ask")).isEqualTo(0);
    assertThat(f.run("merge", "prepare", zip.toString(), "--on-conflict", "mine")).isEqualTo(0);
    assertThat(f.out()).containsPattern("AUTO +P  " + Wars.SECURITY);
    assertThat(f.hf.runtime.merges().list()).hasSize(3);
    // the setting is the default when no rule is given
    SettingsStore.save(
        f.home, SettingsStore.load(f.home).orElseThrow().withKey("merge.onConflict", "theirs"));
    assertThat(f.run("merge", "prepare", zip.toString())).isEqualTo(0);
    assertThat(f.hf.runtime.merges().list().get(0).onConflict()).isEqualTo("theirs");
  }

  @Test
  void should_resolve_with_a_file_of_the_operators_and_refuse_one_that_fails_its_checks()
      throws Exception {
    CommandsTest.Fixture f = fixture();
    SiteFixture s = SiteFixture.create(f.hf, tmp);
    s.customizeWithCollisions();
    Path zip = s.hotfix();
    assertThat(f.run("merge", "prepare", zip.toString(), "--on-conflict", "ask")).isEqualTo(0);
    String id = mergeId(f);

    assertThat(f.run("merge", "resolve", id, Wars.SECURITY, "--merged")).isEqualTo(2);
    assertThat(f.err()).contains("a conflict marker is left in it");
    assertThat(f.run("merge", "resolve", id, Wars.SECURITY)).isEqualTo(1);
    assertThat(f.run("merge", "resolve", id, Wars.SECURITY, "--mine", "--theirs")).isEqualTo(1);
    assertThat(f.run("merge", "resolve", "m-none", Wars.SECURITY, "--mine")).isEqualTo(2);
    assertThat(f.err()).contains("unknown merge m-none");

    Path own = tmp.resolve("security.properties");
    Files.writeString(own, "# security\nmax.upload=50\nallow.list=a,b\nstrict=true\nfresh=1\n");
    assertThat(f.run("merge", "resolve", id, Wars.SECURITY, "--merged", own.toString()))
        .isEqualTo(0);
    assertThat(f.run("merge", "resolve", id, Wars.WEB_XML, "--theirs")).isEqualTo(0);
    assertThat(f.out()).contains("took theirs").contains("nothing waits for a decision");

    assertThat(f.run("apply", zip.toString(), "--merge", id, "--yes")).isEqualTo(0);
    assertThat(s.read(Wars.SECURITY)).isEqualTo(Files.readString(own));
    assertThat(s.read(Wars.WEB_XML)).contains(">main2<").doesNotContain("site-main");
  }

  @Test
  void should_refuse_a_stale_merge_and_name_the_file_that_changed() throws Exception {
    CommandsTest.Fixture f = fixture();
    SiteFixture s = SiteFixture.create(f.hf, tmp);
    Path zip = s.hotfix();
    assertThat(f.run("merge", "prepare", zip.toString())).isEqualTo(0);
    String id = mergeId(f);
    s.site(Wars.LOGIN, SiteFixture.vendor(Wars.LOGIN).replace("Welcome", "Hello"));
    assertThat(f.run("merge", "status", id)).isEqualTo(0);
    assertThat(f.out())
        .contains("changed on the server since this merge was prepared: " + Wars.LOGIN);
    assertThat(f.run("apply", zip.toString(), "--merge", id, "--yes")).isEqualTo(2);
    assertThat(f.err()).contains("changed since the merge was prepared: " + Wars.LOGIN);
    assertThat(s.read(Wars.LOGIN)).contains("Hello").doesNotContain("Sign in");
  }

  @Test
  void should_prune_unused_merges_and_hotfix_baselines_older_than_the_newest_two()
      throws Exception {
    CommandsTest.Fixture f = fixture();
    SiteFixture s = SiteFixture.create(f.hf, tmp);
    Path zip = s.hotfix();
    assertThat(f.run("merge", "prepare", zip.toString())).isEqualTo(0);
    String id = mergeId(f);
    for (String build : List.of("20260601_1200", "20260615_0900", "20260701_0100")) {
      Map<String, String> payload = new LinkedHashMap<>();
      payload.put(Packages.LIB + "foo-1.2.3.jar", "foo of " + build);
      Path p = s.packageOf("old-" + build + ".zip", "[" + build + "]", payload, null);
      assertThat(f.run("baseline", "add", p.toString())).isEqualTo(0);
    }
    assertThat(f.run("runs", "prune", "--older-than", "0")).isEqualTo(0);
    assertThat(f.out())
        .contains("baselines removed   JRSHF-10.0.0-20260601-1200")
        .contains("merges removed      " + id);
    assertThat(f.run("baseline", "list")).isEqualTo(0);
    assertThat(f.out())
        .contains(Wars.RELEASE_ID)
        .contains("JRSHF-10.0.0-20260615-0900")
        .contains("JRSHF-10.0.0-20260701-0100")
        .doesNotContain("JRSHF-10.0.0-20260601-1200");
    assertThat(f.run("merge", "list")).isEqualTo(0);
    assertThat(f.out()).contains("no merges");
  }

  @Test
  void should_set_and_refuse_the_merge_settings() throws Exception {
    CommandsTest.Fixture f = fixture();
    assertThat(f.run("settings", "set", "merge.onConflict", "Mine")).isEqualTo(0);
    assertThat(f.out()).contains("merge.onConflict = mine");
    assertThat(f.run("settings", "set", "merge.onConflict", "sometimes")).isEqualTo(1);
    assertThat(f.err()).contains("merge.onConflict must be one of ask, mine, theirs, fail");
    assertThat(f.run("settings", "show")).isEqualTo(0);
    assertThat(f.out()).contains("merge.onConflict").contains("mine").doesNotContain("merge.tool");
    // merge.tool (0.2 to 0.3) is gone; a settings.json that still holds it loads, the key is
    // ignored
    assertThat(f.run("settings", "set", "merge.tool", "meld")).isEqualTo(1);
    assertThat(f.err()).contains("unknown setting merge.tool");
    assertThat(f.run("settings", "set", "merge.onConflict", "")).isEqualTo(0);
    assertThat(SettingsStore.load(f.home).orElseThrow().mergeOnConflict()).isEmpty();
  }
}

package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeDoc.State;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace.Choice;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace.OnConflict;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.pkg.PropertiesMerge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preparing a merge on a customized server and resolving what it leaves to the operator. */
class MergeWorkspaceTest {

  @TempDir Path tmp;

  @Test
  void should_decide_every_file_of_the_package_when_the_site_changed_other_places()
      throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    MergeDoc doc = s.prepare(s.hotfix());

    assertThat(doc.hotfixId()).isEqualTo(SiteFixture.HOTFIX_ID);
    assertThat(doc.baselines()).containsExactly(Wars.RELEASE_ID);
    assertThat(doc.installedBuild()).isEqualTo(Wars.BUILD);
    assertThat(doc.files()).hasSize(SiteFixture.hotfixPayload().size());
    // only the vendor changed the library and the stamps: the package's copy lands
    assertThat(s.item(doc, Packages.LIB + "foo-1.2.3.jar").state()).isEqualTo(State.PLAIN);
    assertThat(s.item(doc, Wars.STAMPS).state()).isEqualTo(State.PLAIN);
    // only the site changed the context: it stays
    assertThat(s.item(doc, Wars.CONTEXT).state()).isEqualTo(State.KEPT);
    // both changed the settings file, in different keys: merged by key
    MergeDoc.Item security = s.item(doc, Wars.SECURITY);
    assertThat(security.state()).isEqualTo(State.AUTO);
    assertThat(security.note()).contains("site values kept: allow.list");
    assertThat(Files.readString(s.side(doc, Wars.SECURITY, MergeWorkspace.MERGED)))
        .isEqualTo("# security\nmax.upload=20\nallow.list=a,b,c\nstrict=true\nfresh=1\n");
    assertThat(security.merged()).isPresent();
    // both changed the page, in different lines: merged by line
    assertThat(s.item(doc, Wars.LOGIN).state()).isEqualTo(State.AUTO);
    assertThat(Files.readString(s.side(doc, Wars.LOGIN, MergeWorkspace.MERGED)))
        .contains("<h1>Sign in</h1>")
        .contains("<p>Welcome to ACME</p>");
    // both changed web.xml, in different lines: merged, and waiting for the operator all the same
    MergeDoc.Item web = s.item(doc, Wars.WEB_XML);
    assertThat(web.state()).isEqualTo(State.REVIEW);
    assertThat(web.merged()).isEmpty();
    assertThat(Files.readString(s.side(doc, Wars.WEB_XML, MergeWorkspace.MERGED)))
        .contains(">main2<")
        .contains(">60<");
    // a script is never merged
    assertThat(s.item(doc, Wars.SCRIPT).state()).isEqualTo(State.OVERWRITTEN);
    // the installer's properties keep this server's value and take the vendor's new key
    assertThat(s.item(doc, Wars.QUARTZ).state()).isEqualTo(State.AUTO);
    assertThat(Files.readString(s.side(doc, Wars.QUARTZ, MergeWorkspace.MERGED)))
        .isEqualTo(
            "# scheduler\nreport.scheduler.web.deployment.uri=http://reports:8081/x\nnew.key=1\n");
    // the installer's XML stays
    assertThat(s.item(doc, Wars.CONTAINER).state()).isEqualTo(State.KEPT);
    assertThat(doc.blocking()).extracting(MergeDoc.Item::path).containsExactly(Wars.WEB_XML);
    // the three sides of a merged file are kept, the sides of a plain one are not
    assertThat(s.side(doc, Wars.SECURITY, MergeWorkspace.BASE)).isRegularFile();
    assertThat(s.side(doc, Wars.SECURITY, MergeWorkspace.MINE)).isRegularFile();
    assertThat(s.side(doc, Wars.SECURITY, MergeWorkspace.THEIRS)).isRegularFile();
    assertThat(s.side(doc, Wars.STAMPS, MergeWorkspace.THEIRS)).doesNotExist();
    assertThat(s.f.runtime.merges().reportFile(doc.id())).isRegularFile();
    assertThat(s.f.runtime.merges().load(doc.id())).contains(doc);
  }

  @Test
  void should_change_nothing_on_the_server_when_a_merge_is_prepared_and_resolved()
      throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    Map<String, String> before = new LinkedHashMap<>();
    for (String path : SiteFixture.hotfixPayload().keySet()) {
      before.put(path, s.read(path));
    }
    MergeDoc doc = s.prepare(s.hotfix());
    s.resolve(doc, Wars.WEB_XML, Choice.MERGED);
    for (Map.Entry<String, String> e : before.entrySet()) {
      assertThat(s.read(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void should_wait_for_the_operator_when_both_changed_the_same_key_and_the_same_line()
      throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithCollisions();
    MergeDoc doc = s.prepare(s.hotfix());

    MergeDoc.Item security = s.item(doc, Wars.SECURITY);
    assertThat(security.state()).isEqualTo(State.CONFLICT);
    assertThat(security.note()).contains("changed by both, to resolve: max.upload");
    assertThat(Files.readAllLines(s.side(doc, Wars.SECURITY, MergeWorkspace.MERGED)))
        .containsSubsequence(
            PropertiesMerge.MARK_MINE,
            "max.upload=50",
            PropertiesMerge.MARK_BASE,
            "max.upload=10",
            PropertiesMerge.MARK_SEPARATOR,
            "max.upload=20",
            PropertiesMerge.MARK_THEIRS);
    MergeDoc.Item web = s.item(doc, Wars.WEB_XML);
    assertThat(web.state()).isEqualTo(State.CONFLICT);
    assertThat(web.note()).contains("1 place(s) changed by both");
    assertThat(doc.blocking())
        .extracting(MergeDoc.Item::path)
        .containsExactlyInAnyOrder(Wars.SECURITY, Wars.WEB_XML);
  }

  @Test
  void should_settle_a_key_conflict_by_the_rule_when_the_rule_is_mine_or_theirs() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithCollisions();
    Path zip = s.hotfix();

    MergeDoc mine = s.prepare(zip, OnConflict.MINE);
    assertThat(s.item(mine, Wars.SECURITY).state()).isEqualTo(State.AUTO);
    assertThat(s.item(mine, Wars.SECURITY).note())
        .contains("changed by both, settled by --on-conflict MINE: max.upload");
    assertThat(Files.readAllLines(s.side(mine, Wars.SECURITY, MergeWorkspace.MERGED)))
        .containsSubsequence("# max.upload=20", "max.upload=50")
        .contains("fresh=1");

    MergeDoc theirs = s.prepare(zip, OnConflict.THEIRS);
    assertThat(s.item(theirs, Wars.SECURITY).state()).isEqualTo(State.AUTO);
    assertThat(Files.readAllLines(s.side(theirs, Wars.SECURITY, MergeWorkspace.MERGED)))
        .containsSubsequence("# max.upload=50", "max.upload=20");
    // the rule is for keys: a line both changed in web.xml still waits
    assertThat(s.item(theirs, Wars.WEB_XML).state()).isEqualTo(State.CONFLICT);
    assertThat(s.f.runtime.merges().list()).extracting(MergeDoc::id).hasSize(2);
  }

  @Test
  void should_refuse_a_merged_file_that_still_holds_a_marker_and_take_it_once_it_is_clean()
      throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithCollisions();
    MergeDoc doc = s.prepare(s.hotfix());

    assertThatThrownBy(() -> s.resolve(doc, Wars.SECURITY, Choice.MERGED))
        .isInstanceOf(HotfixException.class)
        .hasMessageContaining("a conflict marker is left in it");
    MergeDoc after = s.f.runtime.merges().load(doc.id()).orElseThrow();
    assertThat(s.item(after, Wars.SECURITY).state()).isEqualTo(State.CONFLICT);
    assertThat(s.item(after, Wars.SECURITY).checks())
        .containsExactly("a conflict marker is left in it");

    Path own = tmp.resolve("security.merged");
    Files.writeString(own, "# security\nmax.upload=50\nallow.list=a,b\nstrict=true\nfresh=1\n");
    MergeDoc resolved =
        s.f
            .runtime
            .merges()
            .resolve(doc.id(), Wars.SECURITY, Choice.MERGED, Optional.of(own), "pat");
    MergeDoc.Item security = s.item(resolved, Wars.SECURITY);
    assertThat(security.state()).isEqualTo(State.RESOLVED);
    assertThat(security.resolvedBy()).contains("pat");
    assertThat(security.checks()).isEmpty();
    assertThat(security.merged()).contains(s.f.sha(own));
    assertThat(Files.readString(s.side(resolved, Wars.SECURITY, MergeWorkspace.MERGED)))
        .isEqualTo(Files.readString(own));
  }

  @Test
  void should_refuse_a_merged_xml_file_that_fails_its_checks() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithCollisions();
    MergeDoc doc = s.prepare(s.hotfix());
    Path broken = tmp.resolve("web.merged");
    Files.writeString(broken, "<web-app><servlet></web-app>");
    assertThatThrownBy(
            () ->
                s.f
                    .runtime
                    .merges()
                    .resolve(doc.id(), Wars.WEB_XML, Choice.MERGED, Optional.of(broken), "pat"))
        .hasMessageContaining("it is not well-formed XML");
    Files.writeString(
        broken,
        SiteFixture.vendor(Wars.WEB_XML)
            .replace(
                "</web-app>",
                "  <servlet>\n    <servlet-name>main</servlet-name>\n  </servlet>\n</web-app>"));
    assertThatThrownBy(
            () ->
                s.f
                    .runtime
                    .merges()
                    .resolve(doc.id(), Wars.WEB_XML, Choice.MERGED, Optional.of(broken), "pat"))
        .hasMessageContaining("the servlet main is defined twice");
  }

  @Test
  void should_let_the_operator_keep_the_sites_script_but_never_merge_it() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    MergeDoc doc = s.prepare(s.hotfix());
    // a script both changed is the hotfix's unless the operator keeps the site's; never merged
    assertThat(s.item(doc, Wars.SCRIPT).state()).isEqualTo(State.OVERWRITTEN);
    assertThat(s.item(doc, Wars.SCRIPT).note()).contains("unless resolved with --mine");
    assertThatThrownBy(() -> s.resolve(doc, Wars.SCRIPT, Choice.MERGED))
        .hasMessageContaining("is a G file, which is never merged");
    MergeDoc mine = s.resolve(doc, Wars.SCRIPT, Choice.MINE);
    assertThat(s.item(mine, Wars.SCRIPT).state()).isEqualTo(State.KEPT_MINE);
    assertThat(s.item(mine, Wars.SCRIPT).note())
        .contains("kept by the operator")
        .contains("the hotfix's change in this G file is not installed");
    assertThat(s.item(mine, Wars.SCRIPT).lands()).isEqualTo(s.item(mine, Wars.SCRIPT).mine());
    MergeDoc theirs = s.resolve(mine, Wars.SCRIPT, Choice.THEIRS);
    assertThat(s.item(theirs, Wars.SCRIPT).state()).isEqualTo(State.TOOK_THEIRS);
    // the script never waits for anyone: only web.xml does in this site
    assertThat(theirs.blocking()).extracting(MergeDoc.Item::path).containsExactly(Wars.WEB_XML);
  }

  @Test
  void should_record_the_operators_choice_of_a_side() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithCollisions();
    MergeDoc doc = s.prepare(s.hotfix());
    MergeDoc one = s.resolve(doc, Wars.SECURITY, Choice.MINE);
    assertThat(s.item(one, Wars.SECURITY).state()).isEqualTo(State.KEPT_MINE);
    assertThat(s.item(one, Wars.SECURITY).lands()).isEqualTo(s.item(one, Wars.SECURITY).mine());
    MergeDoc two = s.resolve(one, Wars.WEB_XML, Choice.THEIRS);
    assertThat(s.item(two, Wars.WEB_XML).state()).isEqualTo(State.TOOK_THEIRS);
    assertThat(s.item(two, Wars.WEB_XML).lands()).isEqualTo(s.item(two, Wars.WEB_XML).theirs());
    assertThat(two.blocking()).isEmpty();
    // a file that needs no decision takes none
    assertThatThrownBy(() -> s.resolve(two, Wars.STAMPS, Choice.MINE))
        .hasMessageContaining("needs no decision");
    assertThatThrownBy(() -> s.resolve(two, "WEB-INF/absent.xml", Choice.MINE))
        .hasMessageContaining("has no file");
  }

  @Test
  void should_wait_for_the_operator_when_the_site_removed_a_file_the_hotfix_changed()
      throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    Files.delete(s.webapp.resolve(Wars.LOGIN));
    Files.delete(s.webapp.resolve(Wars.CONTEXT));
    MergeDoc doc = s.prepare(s.hotfix());
    // removed here and changed by the vendor: a decision
    MergeDoc.Item login = s.item(doc, Wars.LOGIN);
    assertThat(login.state()).isEqualTo(State.CONFLICT);
    assertThat(login.mine()).isEmpty();
    assertThatThrownBy(() -> s.resolve(doc, Wars.LOGIN, Choice.MERGED))
        .hasMessageContaining("nothing to merge");
    assertThat(s.item(s.resolve(doc, Wars.LOGIN, Choice.MINE), Wars.LOGIN).lands()).isEmpty();
    // removed here and not changed by the vendor: it stays removed
    assertThat(s.item(doc, Wars.CONTEXT).state()).isEqualTo(State.KEPT);
  }

  @Test
  void should_keep_a_site_library_a_readme_pattern_would_delete() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    // seen on a real server: a JDBC driver of the site's matched by a glob of the readme
    s.site(Packages.LIB + "foo-site-driver.jar", "the site's own");
    Map<String, String> payload = new LinkedHashMap<>(SiteFixture.hotfixPayload());
    String readme =
        "Deleted files:\n"
            + Packages.LIB
            + "bar-0.9.jar\nIMPORTANT\n"
            + Packages.LIB
            + "foo-*.jar\n";
    Path zip = s.packageOf("globs.zip", "[20260730_0457]", payload, readme);
    MergeDoc doc = s.prepare(zip);

    MergeDoc.Item driver = s.item(doc, Packages.LIB + "foo-site-driver.jar");
    assertThat(driver.state()).isEqualTo(State.KEPT);
    assertThat(driver.theirs()).isEmpty();
    // the vendor's own leftover, known to the baseline, is still deleted: it has no record
    assertThat(doc.file(Packages.LIB + "foo-1.0.0.jar")).isEmpty();
    assertThat(doc.file(Packages.LIB + "bar-0.9.jar")).isEmpty();
  }

  @Test
  void should_list_newest_first_and_discard() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    Path zip = s.hotfix();
    MergeDoc first = s.prepare(zip);
    MergeDoc second = s.prepare(zip);
    MergeWorkspace merges = s.f.runtime.merges();
    List<String> ids = merges.list().stream().map(MergeDoc::id).toList();
    assertThat(ids).containsExactlyInAnyOrder(first.id(), second.id());
    assertThat(merges.discard(first.id())).isTrue();
    assertThat(merges.discard(first.id())).isFalse();
    assertThat(merges.discard("../" + second.id())).isFalse();
    assertThat(merges.list()).extracting(MergeDoc::id).containsExactly(second.id());
    assertThat(MergeWorkspace.report(second).get(0))
        .contains(second.id())
        .contains(SiteFixture.HOTFIX_ID);
  }

  @Test
  void should_refuse_to_prepare_when_no_baseline_fits() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.f.runtime.baselines().remove(Wars.RELEASE_ID);
    assertThatThrownBy(() -> s.prepare(s.hotfix()))
        .isInstanceOfSatisfying(
            HotfixException.class,
            e -> assertThat(e.remediation()).contains("jrs-hotfix baseline add"))
        .hasMessageContaining("no baseline for release 10.0.0");
  }
}

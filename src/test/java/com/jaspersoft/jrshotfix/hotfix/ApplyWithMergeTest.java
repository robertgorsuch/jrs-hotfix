package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace.Choice;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace.OnConflict;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Applying a hotfix to a customized server with a prepared merge, end to end on disk. */
class ApplyWithMergeTest {

  @TempDir Path tmp;

  private static HotfixPlans.ApplyArgs args(Path zip, MergeDoc doc) {
    return new HotfixPlans.ApplyArgs(zip, true, Optional.of(doc.id()));
  }

  /** A merge of the clean customizations with web.xml confirmed: nothing left to decide. */
  private static MergeDoc ready(SiteFixture s, Path zip) throws Exception {
    return s.resolve(s.prepare(zip), Wars.WEB_XML, Choice.MERGED);
  }

  private static String prefix() {
    return "webapps/jasperserver-pro/";
  }

  @Test
  void should_keep_merge_and_replace_as_the_merge_decided_when_the_plan_runs() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    String siteContext = s.read(Wars.CONTEXT);
    Path zip = s.hotfix();
    MergeDoc doc = ready(s, zip);
    Plan plan = s.f.plans.planApply(args(zip, doc));

    // a kept file is no target: nothing snapshots, stages or swaps it
    assertThat(plan.summary().filesTouched())
        .doesNotContain(s.webapp.resolve(Wars.CONTEXT), s.webapp.resolve(Wars.CONTAINER));
    assertThat(String.join("\n", plan.summary().changes()))
        .contains("merge " + doc.id() + " against " + Wars.RELEASE_ID)
        .contains("Merged (4)")
        .contains(Wars.SECURITY + "  (P)  merged automatically")
        .contains(Wars.WEB_XML + "  (X)  resolved by tester")
        .contains("Kept as the site has it (2)")
        .contains("Replaced although the site changed it (1)")
        .contains(Wars.SCRIPT);

    RunOutcome outcome = s.f.run(plan, "r-merge");
    assertThat(outcome.exitCode()).isZero();

    assertThat(s.read(Wars.CONTEXT)).isEqualTo(siteContext);
    assertThat(s.read(Wars.SECURITY))
        .isEqualTo("# security\nmax.upload=20\nallow.list=a,b,c\nstrict=true\nfresh=1\n");
    assertThat(s.read(Wars.WEB_XML)).contains(">main2<").contains(">60<");
    assertThat(s.read(Wars.LOGIN)).contains("<h1>Sign in</h1>").contains("<p>Welcome to ACME</p>");
    assertThat(s.read(Wars.SCRIPT)).isEqualTo("console.log('hotfix');\n");
    assertThat(s.read(Wars.QUARTZ)).contains("http://reports:8081/x").contains("new.key=1");
    assertThat(s.read(Wars.CONTAINER)).contains("jasperdb").doesNotContain("maxTotal");
    assertThat(s.read(Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");

    LedgerEntry entry = s.f.ledger.find(SiteFixture.HOTFIX_ID).orElseThrow();
    assertThat(entry.mergeId()).contains(doc.id());
    assertThat(entry.baselines()).containsExactly(Wars.RELEASE_ID);
    assertThat(entry.kept())
        .extracting(k -> s.webapp.relativize(k.path()).toString().replace('\\', '/'))
        .containsExactlyInAnyOrder(Wars.CONTEXT, Wars.CONTAINER);
    OwnedFile security =
        entry.files().stream()
            .filter(f -> f.path().equals(s.webapp.resolve(Wars.SECURITY)))
            .findFirst()
            .orElseThrow();
    assertThat(security.wasMerged()).isTrue();
    assertThat(security.afterSha256()).contains(s.f.sha(s.webapp.resolve(Wars.SECURITY)));
    assertThat(security.vendorSha256())
        .contains(s.f.sha(s.side(doc, Wars.SECURITY, MergeWorkspace.THEIRS)));
    OwnedFile jar =
        entry.files().stream()
            .filter(f -> f.path().endsWith("foo-1.2.3.jar"))
            .findFirst()
            .orElseThrow();
    assertThat(jar.wasMerged()).isFalse();

    // the package is the vendor's level from now on
    BaseView view = s.f.runtime.baseView().view().orElseThrow();
    assertThat(view.describe()).isEqualTo(Wars.RELEASE_ID + " + " + SiteFixture.HOTFIX_ID);
  }

  @Test
  void should_keep_the_sites_script_when_the_operator_resolved_it_with_mine() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    Path zip = s.hotfix();
    MergeDoc doc = s.resolve(ready(s, zip), Wars.SCRIPT, Choice.MINE);
    Plan plan = s.f.plans.planApply(args(zip, doc));

    assertThat(plan.summary().filesTouched()).doesNotContain(s.webapp.resolve(Wars.SCRIPT));
    assertThat(String.join("\n", plan.summary().changes()))
        .contains("Kept as the site has it (3)")
        .contains(Wars.SCRIPT + "  (G)")
        .doesNotContain("Replaced although the site changed it");

    assertThat(s.f.run(plan, "r-mine").exitCode()).isZero();
    assertThat(s.read(Wars.SCRIPT)).isEqualTo("console.log('site');\n");
    LedgerEntry entry = s.f.ledger.find(SiteFixture.HOTFIX_ID).orElseThrow();
    assertThat(entry.kept())
        .extracting(k -> s.webapp.relativize(k.path()).toString().replace('\\', '/'))
        .contains(Wars.SCRIPT);
    assertThat(entry.kept())
        .filteredOn(k -> k.path().equals(s.webapp.resolve(Wars.SCRIPT)))
        .singleElement()
        .satisfies(k -> assertThat(k.reason()).contains("kept by the operator"));
  }

  @Test
  void should_bring_the_sites_files_back_byte_for_byte_when_the_hotfix_is_rolled_back()
      throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    Map<String, String> before = new LinkedHashMap<>();
    for (String path : SiteFixture.hotfixPayload().keySet()) {
      before.put(path, s.read(path));
    }
    Path zip = s.hotfix();
    assertThat(s.f.run(s.f.plans.planApply(args(zip, ready(s, zip))), "r-apply").exitCode())
        .isZero();
    Plan rollback =
        s.f.plans.planRollback(new HotfixPlans.RollbackArgs(SiteFixture.HOTFIX_ID, false));
    assertThat(s.f.run(rollback, "r-rollback").exitCode()).isZero();
    for (Map.Entry<String, String> e : before.entrySet()) {
      assertThat(s.read(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void should_refuse_a_merge_with_a_file_still_waiting() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithCollisions();
    Path zip = s.hotfix();
    MergeDoc doc = s.prepare(zip);
    assertThatThrownBy(() -> s.f.plans.planApply(args(zip, doc)))
        .isInstanceOfSatisfying(
            HotfixException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(HotfixException.PRECHECK);
              assertThat(e.remediation())
                  .contains("jrs-hotfix merge status " + doc.id())
                  .contains("jrs-hotfix merge resolve " + doc.id())
                  .contains("--merge " + doc.id());
            })
        .hasMessageContaining("2 file(s) changed by both this site and " + SiteFixture.HOTFIX_ID)
        .hasMessageContaining(Wars.SECURITY + " (CONFLICT)")
        .hasMessageContaining("nothing was changed on the server");
    assertThatThrownBy(
            () -> s.f.plans.planApply(new HotfixPlans.ApplyArgs(zip, true, Optional.of("m-none"))))
        .hasMessageContaining("unknown merge m-none");
  }

  @Test
  void should_refuse_when_a_file_changed_after_the_merge_was_prepared() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    Path zip = s.hotfix();
    MergeDoc doc = ready(s, zip);
    // a file the merge meant to replace plainly, edited since
    s.site(Wars.STAMPS, Wars.stamps("20260121", "2317") + "# a note\n");
    assertThatThrownBy(() -> s.f.plans.planApply(args(zip, doc)))
        .isInstanceOf(HotfixException.class)
        .hasMessageContaining("changed since the merge was prepared: " + Wars.STAMPS);
    assertThat(s.f.plans.mergeChangedSince(doc)).containsExactly(Wars.STAMPS);
  }

  @Test
  void should_refuse_a_merge_prepared_for_another_package() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    Path zip = s.hotfix();
    MergeDoc doc = ready(s, zip);
    Map<String, String> other = new LinkedHashMap<>(SiteFixture.hotfixPayload());
    other.put(Packages.LIB + "foo-1.2.3.jar", "another build of foo");
    Path otherZip = s.packageOf("other.zip", "[20260730_0457]", other, null);
    assertThatThrownBy(() -> s.f.plans.planApply(args(otherZip, doc)))
        .hasMessageContaining("was prepared for another package");
  }

  @Test
  void should_build_the_same_plan_again_when_the_run_has_already_swapped() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithoutCollisions();
    Path zip = s.hotfix();
    MergeDoc doc = ready(s, zip);
    Plan plan = s.f.plans.planApply(args(zip, doc));
    assertThat(s.f.run(plan, "r-first").exitCode()).isZero();
    // recovery rebuilds the plan from the arguments: the files are then as the merge leaves them
    String argsJson = HotfixPlans.applyArgsJson(args(zip, doc));
    assertThat(HotfixPlans.applyArgs(argsJson).mergeId()).contains(doc.id());
    Plan rebuilt = s.f.plans.rebuild(HotfixPlans.APPLY, argsJson);
    // a deletion done is not planned again (the file is gone), as for the readme's deletions
    assertThat(plan.summary().filesTouched()).containsAll(rebuilt.summary().filesTouched());
    assertThat(rebuilt.summary().filesTouched())
        .containsAll(plan.summary().filesTouched().stream().filter(Files::isRegularFile).toList());
    assertThat(rebuilt.fingerprint().inputs())
        .containsEntry(HotfixPlans.MERGE_INPUT, doc.id())
        .containsEntry(
            HotfixPlans.MERGE_DOC_INPUT,
            plan.fingerprint().inputs().get(HotfixPlans.MERGE_DOC_INPUT));
    assertThat(rebuilt.fingerprint().inputs().get("merged:" + prefix() + Wars.SECURITY))
        .isEqualTo(plan.fingerprint().inputs().get("merged:" + prefix() + Wars.SECURITY));
  }

  @Test
  void should_read_arguments_stored_before_merges_existed() {
    HotfixPlans.ApplyArgs old =
        HotfixPlans.applyArgs("{\"packageFile\":\"p.zip\",\"checksumConfirmed\":true}");
    assertThat(old.mergeId()).isEmpty();
    assertThat(HotfixPlans.applyArgsJson(old)).doesNotContain("mergeId");
  }

  @Test
  void should_apply_without_a_merge_when_there_is_no_baseline() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.f.runtime.baselines().remove(Wars.RELEASE_ID);
    HotfixPlans.ApplyArgs resolved =
        s.f.plans.resolveApply(
            s.hotfix(), true, Optional.empty(), Optional.empty(), OnConflict.FAIL);
    assertThat(resolved.mergeId()).isEmpty();
    Plan plan = s.f.plans.planApply(resolved);
    // as 0.1 did: the installer's properties merged by the reader, its XML kept, the rest replaced
    assertThat(String.join("\n", plan.summary().warnings()))
        .contains("js.quartz.properties holds values written for this server")
        .contains("jrs-hotfix baseline add");
  }

  @Test
  void should_prepare_a_merge_by_itself_and_reuse_it_when_nothing_changed() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.site(Wars.CONTEXT, SiteFixture.vendor(Wars.CONTEXT).replace("\"4\"", "\"16\""));
    Path zip = s.hotfix();
    HotfixPlans.ApplyArgs first =
        s.f.plans.resolveApply(zip, true, Optional.empty(), Optional.empty(), OnConflict.FAIL);
    assertThat(first.mergeId()).isPresent();
    HotfixPlans.ApplyArgs second =
        s.f.plans.resolveApply(zip, true, Optional.empty(), Optional.empty(), OnConflict.FAIL);
    assertThat(second.mergeId()).isEqualTo(first.mergeId());
    // a change on the server makes the old merge stale: a new one is prepared
    s.site(Wars.LOGIN, SiteFixture.vendor(Wars.LOGIN).replace("Welcome", "Hello"));
    HotfixPlans.ApplyArgs third =
        s.f.plans.resolveApply(zip, true, Optional.empty(), Optional.empty(), OnConflict.FAIL);
    assertThat(third.mergeId()).isPresent().isNotEqualTo(first.mergeId());
    assertThat(s.f.run(s.f.plans.planApply(third), "r-auto").exitCode()).isZero();
    assertThat(s.read(Wars.CONTEXT)).contains("\"16\"");
    assertThat(s.read(Wars.LOGIN)).contains("Hello").contains("Sign in");
  }

  @Test
  void should_stop_the_apply_and_keep_what_was_resolved_when_files_wait() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    s.customizeWithCollisions();
    Path zip = s.hotfix();
    assertThatThrownBy(
            () ->
                s.f.plans.resolveApply(
                    zip, true, Optional.empty(), Optional.empty(), OnConflict.ASK))
        .hasMessageContaining("wait for your decision in merge m-");
    MergeDoc doc = s.f.runtime.merges().list().get(0);
    s.resolve(doc, Wars.SECURITY, Choice.MINE);
    // the second attempt meets the same merge, with one file less to decide
    assertThatThrownBy(
            () ->
                s.f.plans.resolveApply(
                    zip, true, Optional.empty(), Optional.empty(), OnConflict.ASK))
        .hasMessageContaining("1 file(s)")
        .hasMessageContaining("merge " + doc.id());
    s.resolve(doc, Wars.WEB_XML, Choice.THEIRS);
    HotfixPlans.ApplyArgs ready =
        s.f.plans.resolveApply(zip, true, Optional.empty(), Optional.empty(), OnConflict.ASK);
    assertThat(ready.mergeId()).contains(doc.id());
    assertThat(s.f.run(s.f.plans.planApply(ready), "r-resolved").exitCode()).isZero();
    assertThat(s.read(Wars.SECURITY)).contains("max.upload=50");
    assertThat(s.read(Wars.WEB_XML)).contains(">main2<");
  }

  @Test
  void should_refuse_rather_than_apply_blind_when_the_baselines_do_not_fit() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    // a hotfix applied by hand, whose package was never given as a baseline
    s.site(Wars.STAMPS, Wars.stamps("20260601", "1200"));
    Path zip = s.hotfix();
    assertThatThrownBy(
            () ->
                s.f.plans.resolveApply(
                    zip, true, Optional.empty(), Optional.empty(), OnConflict.FAIL))
        .isInstanceOfSatisfying(
            HotfixException.class,
            e ->
                assertThat(e.remediation())
                    .contains("jrs-hotfix baseline add <package.zip>")
                    .contains("jrs-hotfix baseline remove"))
        .hasMessageContaining("build 20260601_1200")
        .hasMessageContaining("cannot tell this site's changes from the vendor's");
  }

  @Test
  void should_take_the_first_hotfix_as_the_base_when_a_second_one_is_applied() throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    Path first = s.hotfix();
    assertThat(
            s.f
                .run(
                    s.f.plans.planApply(
                        s.f.plans.resolveApply(
                            first, true, Optional.empty(), Optional.empty(), OnConflict.FAIL)),
                    "r-one")
                .exitCode())
        .isZero();
    // after the first hotfix the site edits the settings file the hotfix brought
    s.site(Wars.SECURITY, SiteFixture.HOTFIX_SECURITY.replace("strict=true", "strict=false"));
    Map<String, String> payload = new LinkedHashMap<>(SiteFixture.hotfixPayload());
    // the second, cumulative, ships the same settings file again and a newer library
    payload.put(Packages.LIB + "foo-1.2.3.jar", "patched foo again");
    payload.put(Wars.STAMPS, Wars.stamps("20260830", "0100"));
    Path second = s.packageOf("second.zip", "[20260830_0100]", payload, null);
    HotfixPlans.ApplyArgs args =
        s.f.plans.resolveApply(second, true, Optional.empty(), Optional.empty(), OnConflict.FAIL);
    MergeDoc doc = s.f.plans.merge(args.mergeId().orElseThrow());
    assertThat(doc.baselines()).containsExactly(Wars.RELEASE_ID, SiteFixture.HOTFIX_ID);
    // the vendor did not change it since the first hotfix: only the site did, so it is kept
    assertThat(s.item(doc, Wars.SECURITY).state()).isEqualTo(MergeDoc.State.KEPT);
    assertThat(s.f.run(s.f.plans.planApply(args), "r-two").exitCode()).isZero();
    assertThat(s.read(Wars.SECURITY)).contains("strict=false").contains("fresh=1");
    assertThat(s.read(Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo again");
    assertThat(Files.isDirectory(s.f.home.baselines().resolve("JRSHF-10.0.0-20260830-0100")))
        .isTrue();
  }
}

package com.jaspersoft.jrshotfix.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.baseline.BaselineManifest;
import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeDoc.State;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace.Choice;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.scan.Scan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installation area (0.7 design, section 1): what a site changed under buildomatic is kept or
 * merged by the same rules as the webapp, given a baseline made from the vendor's distribution.
 */
class InstallationAreaTest {

  static final String DB = "buildomatic/conf_source/db/postgresql/db.template.properties";
  static final String INSTALL_XML = "buildomatic/install.xml";
  static final String SCRIPT = "buildomatic/js-ant.sh";
  static final String TOOL = "buildomatic/lib/tool-2.0.jar";
  static final String MASTER = "buildomatic/default_master.properties";
  static final String GENERATED = "buildomatic/build_conf/default/js.jdbc.properties";

  @TempDir Path tmp;

  /** The vendor's buildomatic, as the distribution ships it. */
  static Map<String, String> vendorInstallation() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(DB, "# db\ndb.port=5432\npool=10\n");
    files.put(
        INSTALL_XML,
        "<project>\n  <target name=\"a\"/>\n\n\n  <!-- deploy -->\n\n\n  <target name=\"b\"/>\n"
            + "</project>\n");
    files.put(SCRIPT, "#!/bin/sh\nant \"$@\"\n");
    files.put(TOOL, "old tool");
    return files;
  }

  /** The vendor's distribution: its WAR and its buildomatic. */
  private Path distribution() throws IOException {
    Path dist = tmp.resolve("dist");
    Wars.war(dist.resolve("jasperserver-pro.war"), Wars.vendor());
    write(dist, vendorInstallation());
    return dist;
  }

  /** A site over the release, with the distribution as its baseline. */
  private SiteFixture site() throws IOException {
    SiteFixture s = SiteFixture.create(tmp);
    write(installDir(s), vendorInstallation());
    s.f.runtime.baselines().addRelease(distribution());
    return s;
  }

  private static Path installDir(SiteFixture s) {
    return s.f.paths.installDir();
  }

  private static void write(Path root, Map<String, String> files) throws IOException {
    for (Map.Entry<String, String> e : files.entrySet()) {
      Path file = root.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue(), StandardCharsets.ISO_8859_1);
    }
  }

  private static String read(SiteFixture s, String path) throws IOException {
    return Files.readString(installDir(s).resolve(path), StandardCharsets.ISO_8859_1);
  }

  /** The site's buildomatic: a key, a target, the launcher, its own settings and their output. */
  private static void customize(SiteFixture s) throws IOException {
    Map<String, String> site = new LinkedHashMap<>();
    site.put(DB, "# db\ndb.port=5433\npool=10\n");
    site.put(INSTALL_XML, vendorInstallation().get(INSTALL_XML).replace("\"a\"", "\"a-site\""));
    site.put(SCRIPT, "#!/bin/sh\nexport ANT_OPTS=-Xmx2g\nant \"$@\"\n");
    site.put(MASTER, "appServerType=tomcat\n");
    site.put(GENERATED, "generated\n");
    write(installDir(s), site);
  }

  /** The hotfix, which ships buildomatic files too. */
  private Path hotfix() throws IOException {
    Path file = tmp.resolve("dl/hotfix-install.zip");
    if (Files.isRegularFile(file)) {
      return file;
    }
    Map<String, String> install = new LinkedHashMap<>();
    install.put(DB, "# db\ndb.port=5432\npool=20\nnew.key=1\n");
    install.put(
        INSTALL_XML, vendorInstallation().get(INSTALL_XML).replace("\"b\"", "\"b-hotfix\""));
    install.put(SCRIPT, vendorInstallation().get(SCRIPT));
    install.put(TOOL, "patched tool");
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(SiteFixture.hotfixPayload(), null));
    outer.put("js-install.zip", Packages.zipBytes(install, null));
    return Packages.zip(file, outer);
  }

  @Test
  void should_record_the_installation_when_the_baseline_is_the_distribution() throws Exception {
    SiteFixture s = site();
    BaselineManifest release = s.f.runtime.baselines().find(Wars.RELEASE_ID).orElseThrow();

    assertThat(release.files(Area.INSTALLATION))
        .extracting(BaselineManifest.BaseFile::path)
        .containsExactlyInAnyOrder(DB, INSTALL_XML, SCRIPT, TOOL);
    assertThat(release.files(Area.INSTALLATION))
        .filteredOn(f -> f.path().equals(TOOL))
        .singleElement()
        .satisfies(f -> assertThat(f.payload()).isFalse());
    assertThat(s.f.runtime.baselines().payload(Wars.RELEASE_ID, Area.INSTALLATION, DB))
        .hasContent("# db\ndb.port=5432\npool=10\n");
  }

  @Test
  void should_scan_what_the_site_changed_under_buildomatic() throws Exception {
    SiteFixture s = site();
    customize(s);
    BaseView installation = s.f.runtime.baseView().view().orElseThrow().installation();

    Scan.Report report = Scan.of(installation, installDir(s), s.f.runtime.files());

    assertThat(report.verdict()).isEqualTo("customized: 3 changed, 0 added, 0 removed");
    assertThat(report.of(Scan.State.CHANGED))
        .extracting(Scan.Item::path)
        .containsExactlyInAnyOrder(DB, INSTALL_XML, SCRIPT);
    // the site's own settings are listed, never a customization; buildomatic's output is counted
    assertThat(report.of(Scan.State.INSTALLER)).extracting(Scan.Item::path).containsExactly(MASTER);
    assertThat(report.generated()).isEqualTo(1);
    // Tomcat and the home under the installation are not the installation area
    assertThat(report.items()).noneMatch(i -> i.path().startsWith("apache-tomcat/"));
  }

  @Test
  void should_merge_keep_and_replace_buildomatic_as_the_webapp_and_undo_it() throws Exception {
    SiteFixture s = site();
    customize(s);
    Path zip = hotfix();
    Map<String, String> before = new LinkedHashMap<>();
    for (String path : vendorInstallation().keySet()) {
      before.put(path, read(s, path));
    }

    MergeDoc doc = s.prepare(zip);
    assertThat(s.item(doc, DB).where()).isEqualTo(Area.INSTALLATION);
    assertThat(s.item(doc, DB).state()).isEqualTo(State.AUTO);
    assertThat(s.item(doc, INSTALL_XML).state()).isEqualTo(State.REVIEW);
    assertThat(s.item(doc, SCRIPT).state()).isEqualTo(State.KEPT);
    assertThat(s.item(doc, TOOL).state()).isEqualTo(State.PLAIN);
    doc = s.resolve(doc, INSTALL_XML, Choice.MERGED);
    assertThat(doc.blocking()).isEmpty();

    Plan plan = s.f.plans.planApply(new HotfixPlans.ApplyArgs(zip, true, Optional.of(doc.id())));
    assertThat(s.f.run(plan, "r-apply")).isInstanceOf(RunOutcome.Succeeded.class);

    // merged by key: the site's port and the hotfix's pool and new key
    assertThat(read(s, DB)).contains("db.port=5433").contains("pool=20").contains("new.key=1");
    // merged by line: both targets renamed
    assertThat(read(s, INSTALL_XML)).contains("\"a-site\"").contains("\"b-hotfix\"");
    // only the site changed the launcher: it stays
    assertThat(read(s, SCRIPT)).contains("ANT_OPTS");
    assertThat(read(s, TOOL)).isEqualTo("patched tool");
    assertThat(read(s, MASTER)).isEqualTo("appServerType=tomcat\n");

    assertThat(s.f.run(s.f.plans.planRollback(), "r-rollback"))
        .isInstanceOf(RunOutcome.Succeeded.class);
    for (Map.Entry<String, String> e : before.entrySet()) {
      assertThat(read(s, e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void should_say_buildomatic_is_replaced_when_the_baseline_knows_the_webapp_only()
      throws Exception {
    SiteFixture s = SiteFixture.create(tmp);
    write(installDir(s), vendorInstallation());
    customize(s);
    Path zip = hotfix();

    MergeDoc doc = s.prepare(zip);
    assertThat(doc.files()).noneMatch(i -> i.where() == Area.INSTALLATION);
    Plan plan = s.f.plans.planApply(new HotfixPlans.ApplyArgs(zip, true, Optional.of(doc.id())));

    assertThat(plan.summary().warnings())
        .anySatisfy(
            w ->
                assertThat(w)
                    .contains("under the installation (buildomatic, samples) are replaced")
                    .contains("baseline add"));
  }
}

package com.jaspersoft.jrshotfix.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.baseline.BaselineStore;
import com.jaspersoft.jrshotfix.baseline.FileClass;
import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.PlatformDetectionTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scan of a small installation against a release baseline, and against a package. */
class ScanTest {

  @TempDir Path tmp;

  final FileOps files = PlatformDetectionTest.files();
  BaselineStore store;
  PackagePaths paths;
  Path webapp;

  @BeforeEach
  void install() throws Exception {
    store = new BaselineStore(new Home(tmp.resolve("home")), Clock.systemUTC());
    paths = new PackagePaths(tmp.resolve("jrs"), tmp.resolve("jrs/apache-tomcat"));
    webapp = paths.tomcatDir().resolve("webapps/jasperserver-pro");
    Wars.installAsTheInstallerDoes(webapp);
    store.addRelease(Wars.war(tmp.resolve("dl/jasperserver-pro.war"), Wars.vendor()));
  }

  private BaseView view() {
    BaseView.Resolution r = BaseView.resolve(store, webapp, files);
    assertThat(r.problem()).isEmpty();
    return r.view().orElseThrow();
  }

  private Scan.Report scan() {
    return Scan.of(view(), webapp, files);
  }

  @Test
  void should_say_vanilla_and_list_the_installer_files_when_nothing_was_changed() {
    Scan.Report report = scan();
    assertThat(report.verdict()).isEqualTo("vanilla: no vendor file was changed");
    assertThat(report.vanilla()).isTrue();
    assertThat(report.items())
        .extracting(Scan.Item::path, Scan.Item::state)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(Wars.QUARTZ, Scan.State.INSTALLER),
            org.assertj.core.groups.Tuple.tuple(Wars.CONTAINER, Scan.State.INSTALLER));
    assertThat(report.baseline()).isEqualTo(Wars.RELEASE_ID);
    assertThat(report.unchanged()).isEqualTo(Wars.vendor().size() - 2);
  }

  @Test
  void should_list_what_the_site_changed_added_and_removed() throws Exception {
    Wars.write(webapp, Wars.WEB_XML, Wars.vendor().get(Wars.WEB_XML).replace("20", "60"));
    Wars.write(webapp, Wars.SCRIPT, "console.log('patched by the site');\n");
    Wars.write(webapp, Packages.LIB + "iijdbc.jar", "a driver");
    Wars.write(webapp, "WEB-INF/jsp/site.jsp", "<p>ours</p>\n");
    Files.delete(webapp.resolve(Wars.LOGO));

    Scan.Report report = scan();
    assertThat(report.verdict()).isEqualTo("customized: 2 changed, 2 added, 1 removed");
    assertThat(report.of(Scan.State.CHANGED))
        .containsExactly(
            new Scan.Item(Wars.WEB_XML, FileClass.X, Scan.State.CHANGED),
            new Scan.Item(Wars.SCRIPT, FileClass.G, Scan.State.CHANGED));
    assertThat(report.of(Scan.State.ADDED))
        .extracting(Scan.Item::path)
        .containsExactly("WEB-INF/jsp/site.jsp", Packages.LIB + "iijdbc.jar");
    assertThat(report.of(Scan.State.REMOVED))
        .extracting(Scan.Item::path)
        .containsExactly(Wars.LOGO);
  }

  @Test
  void should_not_call_a_file_changed_when_only_its_line_ends_differ() throws Exception {
    Wars.write(webapp, Wars.WEB_XML, Wars.vendor().get(Wars.WEB_XML).replace("\n", "\r\n"));
    Wars.write(webapp, Wars.SCRIPT, Wars.vendor().get(Wars.SCRIPT).replace("\n", "\r\n"));
    assertThat(scan().vanilla()).isTrue();
    assertThat(scan().of(Scan.State.CHANGED)).isEmpty();
    // a binary file is compared byte for byte
    Wars.write(webapp, Wars.LOGO, "PNG\r\n");
    assertThat(scan().of(Scan.State.CHANGED))
        .extracting(Scan.Item::path)
        .containsExactly(Wars.LOGO);
  }

  @Test
  void should_count_and_not_list_what_the_server_writes_and_what_is_built() throws Exception {
    // seen on a real 10.0.0: the installer writes this file whole, the WAR has no copy of it
    Wars.write(webapp, "WEB-INF/classes/keystore.init.properties", "ks=/home/jrs\n");
    Wars.write(webapp, "WEB-INF/logs/jasperserver.log", "a line\n");
    Wars.write(webapp, "optimized-scripts/bundle.js", "built\n");
    Wars.write(webapp, "themes/site.css", "built\n");
    Scan.Report report = scan();
    assertThat(report.vanilla()).isTrue();
    assertThat(report.generated()).isEqualTo(3);
    assertThat(report.of(Scan.State.ADDED)).isEmpty();
    assertThat(report.of(Scan.State.INSTALLER))
        .extracting(Scan.Item::path)
        .contains("WEB-INF/classes/keystore.init.properties");
  }

  @Test
  void should_list_a_deployed_external_authentication_context_under_its_own_heading()
      throws Exception {
    Wars.write(webapp, "WEB-INF/applicationContext-externalAuth-LDAP.xml", "<beans/>\n");
    Scan.Report report = scan();
    assertThat(report.of(Scan.State.EXTERNAL_AUTH))
        .extracting(Scan.Item::path)
        .containsExactly("WEB-INF/applicationContext-externalAuth-LDAP.xml");
    assertThat(report.of(Scan.State.ADDED)).isEmpty();
    assertThat(report.vanilla()).isTrue();
  }

  /** A package whose webapp archive holds {@code payload}. */
  private PackageContents read(Map<String, String> payload) throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    Map<String, String> webappFiles = new LinkedHashMap<>();
    Map<String, String> installFiles = new LinkedHashMap<>();
    payload.forEach(
        (path, text) -> (path.startsWith("samples/") ? installFiles : webappFiles).put(path, text));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(webappFiles, null));
    if (!installFiles.isEmpty()) {
      outer.put("js-install.zip", Packages.zipBytes(installFiles, null));
    }
    Path zip = Packages.zip(tmp.resolve("dl/p" + payload.hashCode() + ".zip"), outer);
    return OfficialPackage.read(zip, paths, "jasperserver-pro", files);
  }

  private Scan.Verdict verdict(PackageContents c, String path) {
    List<Scan.PackageItem> items = Scan.against(view(), c, "jasperserver-pro", webapp, files);
    return items.stream().filter(i -> i.path().equals(path)).findFirst().orElseThrow().verdict();
  }

  @Test
  void should_judge_every_row_of_the_collision_table() throws Exception {
    Map<String, String> vendor = Wars.vendor();
    String hotfixWebXml = vendor.get(Wars.WEB_XML).replace("main", "main2");
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(Wars.WEB_XML, hotfixWebXml);
    payload.put(Wars.CONTEXT, vendor.get(Wars.CONTEXT));
    payload.put(Wars.LOGIN, vendor.get(Wars.LOGIN));
    payload.put(Wars.SECURITY, vendor.get(Wars.SECURITY) + "fresh=1\n");
    payload.put(Wars.SCRIPT, "console.log('hotfix');\n");
    payload.put(Wars.LOGO, vendor.get(Wars.LOGO));
    payload.put("WEB-INF/jsp/new.jsp", "<p>new</p>\n");
    payload.put(Wars.QUARTZ, vendor.get(Wars.QUARTZ));

    // mine same as base
    PackageContents c = read(payload);
    assertThat(verdict(c, Wars.CONTEXT)).isEqualTo(Scan.Verdict.UNTOUCHED);
    assertThat(verdict(c, Wars.WEB_XML)).isEqualTo(Scan.Verdict.VENDOR_ONLY);
    assertThat(verdict(c, "WEB-INF/jsp/new.jsp")).isEqualTo(Scan.Verdict.NEW);
    assertThat(verdict(c, Wars.QUARTZ)).isEqualTo(Scan.Verdict.INSTALLER);

    // mine different from base
    Wars.write(webapp, Wars.CONTEXT, vendor.get(Wars.CONTEXT).replace("4", "16"));
    Wars.write(webapp, Wars.SECURITY, vendor.get(Wars.SECURITY).replace("10", "50"));
    Wars.write(webapp, Wars.SCRIPT, "console.log('site');\n");
    Wars.write(webapp, Wars.WEB_XML, hotfixWebXml.replace("\n", "\r\n"));
    Files.delete(webapp.resolve(Wars.LOGIN));
    c = read(payload);
    assertThat(verdict(c, Wars.CONTEXT)).isEqualTo(Scan.Verdict.SITE_ONLY);
    assertThat(verdict(c, Wars.SECURITY)).isEqualTo(Scan.Verdict.COLLISION);
    assertThat(verdict(c, Wars.SCRIPT)).isEqualTo(Scan.Verdict.COLLISION);
    assertThat(verdict(c, Wars.WEB_XML)).isEqualTo(Scan.Verdict.ALREADY_APPLIED);
    assertThat(verdict(c, Wars.LOGIN)).isEqualTo(Scan.Verdict.SITE_REMOVED);

    List<Scan.PackageItem> items = Scan.against(view(), c, "jasperserver-pro", webapp, files);
    assertThat(items.stream().filter(Scan.PackageItem::needsMerge).map(Scan.PackageItem::path))
        .containsExactly(Wars.SECURITY);
    assertThat(items.stream().filter(Scan.PackageItem::overwritten).map(Scan.PackageItem::path))
        .containsExactly(Wars.SCRIPT);

    // removed by the site, changed by the vendor
    payload.put(Wars.LOGIN, vendor.get(Wars.LOGIN).replace("Welcome", "Hello"));
    c = read(payload);
    assertThat(verdict(c, Wars.LOGIN)).isEqualTo(Scan.Verdict.REMOVED_COLLISION);
  }

  @Test
  void should_warn_when_the_package_changes_the_samples_a_deployed_context_was_made_from()
      throws Exception {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(Packages.LIB + "foo-1.2.3.jar", "patched");
    payload.put(
        "samples/externalAuth-sample-config/sample-applicationContext-externalAuth-LDAP.xml",
        "<beans/>");
    PackageContents c = read(payload);
    assertThat(Scan.externalAuthWarning(c, webapp)).isEmpty();

    Wars.write(webapp, "WEB-INF/applicationContext-externalAuth-LDAP.xml", "<beans/>\n");
    assertThat(Scan.externalAuthWarning(c, webapp))
        .hasValueSatisfying(
            w ->
                assertThat(w)
                    .contains("WEB-INF/applicationContext-externalAuth-LDAP.xml")
                    .contains("sample-applicationContext-externalAuth-LDAP.xml")
                    .contains("by hand"));

    payload.remove(
        "samples/externalAuth-sample-config/sample-applicationContext-externalAuth-LDAP.xml");
    assertThat(Scan.externalAuthWarning(read(payload), webapp)).isEmpty();
  }
}

package com.jaspersoft.jrshotfix.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.PlatformDetectionTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaseViewTest {

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
  }

  private void addRelease() throws Exception {
    store.addRelease(Wars.war(tmp.resolve("dl/jasperserver-pro.war"), Wars.vendor()));
  }

  /** Applies the hotfix of {@link BaselineStoreTest#hotfix} by hand and adds its baseline. */
  private void applyHotfixByHand(boolean withBaseline) throws Exception {
    Path zip = BaselineStoreTest.hotfix(tmp.resolve("dl/hotfix.zip"));
    PackageContents contents = OfficialPackage.read(zip, paths, "jasperserver-pro", files);
    if (withBaseline) {
      store.addHotfix(zip, contents, "jasperserver-pro");
    }
    Wars.write(webapp, Packages.LIB + "foo-1.2.3.jar", "patched foo");
    Wars.write(webapp, Wars.WEB_XML, "<web-app>\n  <!-- hotfix -->\n</web-app>\n");
    Wars.write(webapp, Wars.SCRIPT, "console.log('hotfix');\n");
    Wars.write(webapp, Wars.STAMPS, Wars.stamps("20260730", "0457"));
    Files.delete(webapp.resolve(Packages.LIB + "bar-0.9.jar"));
    Files.delete(webapp.resolve(Packages.LIB + "foo-1.0.0.jar"));
  }

  @Test
  void should_have_no_view_and_name_baseline_add_when_there_is_no_release_baseline() {
    BaseView.Resolution r = BaseView.resolve(store, webapp, files);
    assertThat(r.view()).isEmpty();
    assertThat(r.present()).isFalse();
    assertThat(r.problem()).contains("no baseline for release 10.0.0");
    assertThat(r.remediation()).contains("jrs-hotfix baseline add");
  }

  @Test
  void should_be_the_release_when_the_webapp_states_the_releases_build() throws Exception {
    addRelease();
    BaseView view = BaseView.resolve(store, webapp, files).view().orElseThrow();
    assertThat(view.describe()).isEqualTo(Wars.RELEASE_ID);
    assertThat(view.paths()).containsExactlyInAnyOrderElementsOf(Wars.vendor().keySet());
    assertThat(view.payload(Wars.WEB_XML)).isPresent();
    assertThat(view.payload(Wars.SCRIPT)).isEmpty();
    assertThat(view.installer(Wars.QUARTZ)).isTrue();
    assertThat(view.installer(Wars.WEB_XML)).isFalse();
  }

  @Test
  void should_lay_the_hotfix_over_the_release_when_the_webapp_states_the_hotfixs_build()
      throws Exception {
    addRelease();
    applyHotfixByHand(true);
    BaseView view = BaseView.resolve(store, webapp, files).view().orElseThrow();
    assertThat(view.describe()).isEqualTo(Wars.RELEASE_ID + " + JRSHF-10.0.0-20260730-0457");
    // the hotfix's copy where it ships one, and its payload
    assertThat(Files.readString(view.payload(Wars.WEB_XML).orElseThrow())).contains("hotfix");
    // the release's copy where it does not
    assertThat(Files.readString(view.payload(Wars.CONTEXT).orElseThrow())).contains("<beans>");
    // what the hotfix's readme deletes is not the vendor's any more
    assertThat(view.file(Packages.LIB + "bar-0.9.jar")).isEmpty();
    assertThat(view.file(Packages.LIB + "foo-1.0.0.jar")).isEmpty();
    assertThat(view.file(Packages.LIB + "foo-1.2.3.jar")).isPresent();
    assertThat(view.paths()).doesNotContain(Packages.LIB + "bar-0.9.jar");
  }

  @Test
  void should_have_no_view_when_the_stated_build_has_no_baseline() throws Exception {
    addRelease();
    applyHotfixByHand(false);
    BaseView.Resolution r = BaseView.resolve(store, webapp, files);
    assertThat(r.view()).isEmpty();
    assertThat(r.present()).isTrue();
    assertThat(r.problem()).contains("build 20260730_0457").contains("no baseline for that build");
    assertThat(r.remediation()).contains("jrs-hotfix baseline add <package.zip>");
  }

  @Test
  void should_have_no_view_when_the_baseline_is_of_another_webapp() throws Exception {
    Map<String, String> other = new LinkedHashMap<>(Wars.vendor());
    for (int i = 0; i < 20; i++) {
      other.put(Packages.LIB + "other-" + i + ".jar", "not on this server " + i);
    }
    store.addRelease(Wars.war(tmp.resolve("dl/other.war"), other));
    BaseView.Resolution r = BaseView.resolve(store, webapp, files);
    assertThat(r.view()).isEmpty();
    assertThat(r.present()).isTrue();
    assertThat(r.problem())
        .contains("does not fit this installation")
        .contains("17 in 100")
        .contains("other-0.jar absent")
        .contains("other-12.jar absent")
        .doesNotContain("other-13.jar")
        .contains("and 15 more");
    assertThat(r.remediation()).contains("removed or replaced counts against the fit");
  }

  @Test
  void should_have_no_view_when_the_webapp_states_no_build_but_baselines_exist() throws Exception {
    addRelease();
    Files.delete(webapp.resolve(Wars.STAMPS));
    BaseView.Resolution r = BaseView.resolve(store, webapp, files);
    assertThat(r.view()).isEmpty();
    assertThat(r.present()).isTrue();
    assertThat(r.problem()).contains("states no build");
  }
}

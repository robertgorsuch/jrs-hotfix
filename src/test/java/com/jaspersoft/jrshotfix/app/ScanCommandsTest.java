package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.pkg.Packages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code baseline} and {@code scan} end to end, in process, over a small vendor webapp. */
class ScanCommandsTest {

  @TempDir Path tmp;

  private final List<CommandsTest.Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void stopServers() {
    fixtures.forEach(CommandsTest.Fixture::close);
  }

  /** The fixture installation with the vendor's files as an installer leaves them. */
  private CommandsTest.Fixture fixture() throws Exception {
    CommandsTest.Fixture f = CommandsTest.Fixture.create(tmp);
    fixtures.add(f);
    Wars.installAsTheInstallerDoes(f.hf.settings.webappDir());
    return f;
  }

  private Path war() throws Exception {
    return Wars.war(tmp.resolve("dl/jasperserver-pro.war"), Wars.vendor());
  }

  /** A hotfix that changes {@code web.xml}, a properties file and a script. */
  private Path hotfix() throws Exception {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(Wars.WEB_XML, Wars.vendor().get(Wars.WEB_XML).replace("main", "main2"));
    payload.put(Wars.SECURITY, Wars.vendor().get(Wars.SECURITY) + "fresh=1\n");
    payload.put(Wars.SCRIPT, "console.log('hotfix');\n");
    payload.put(Wars.CONTEXT, Wars.vendor().get(Wars.CONTEXT));
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(payload, null));
    return Packages.zip(tmp.resolve("dl/hotfix-settings.zip"), outer);
  }

  @Test
  void should_exit_2_and_name_baseline_add_when_scanning_without_a_baseline() throws Exception {
    CommandsTest.Fixture f = fixture();
    assertThat(f.run("scan")).isEqualTo(2);
    assertThat(f.err()).contains("no baseline for release 10.0.0").contains("baseline add");
    assertThat(f.run("baseline", "list")).isEqualTo(0);
    assertThat(f.out()).contains("no baselines");
  }

  @Test
  void should_add_list_and_remove_a_release_baseline() throws Exception {
    CommandsTest.Fixture f = fixture();
    assertThat(f.run("baseline", "add", war().toString())).isEqualTo(0);
    assertThat(f.out())
        .contains("added " + Wars.RELEASE_ID)
        .contains("nothing was changed on the server")
        .contains("written by the installer")
        .contains(Wars.QUARTZ);
    assertThat(f.run("baseline", "list")).isEqualTo(0);
    assertThat(f.out()).contains(Wars.RELEASE_ID).contains("release").contains(Wars.BUILD);
    assertThat(f.run("baseline", "remove", "no-such")).isEqualTo(2);
    assertThat(f.run("baseline", "remove", Wars.RELEASE_ID)).isEqualTo(0);
    assertThat(f.run("scan")).isEqualTo(2);
  }

  @Test
  void should_refuse_a_source_that_is_not_a_webapp_or_does_not_exist() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path notAWar = Packages.zip(tmp.resolve("dl/x.war"), Map.of("a.txt", new byte[] {1}));
    assertThat(f.run("baseline", "add", notAWar.toString())).isEqualTo(6);
    assertThat(f.err()).contains("is not a JasperReports Server 10.x Pro webapp");
    assertThat(f.run("baseline", "add", tmp.resolve("dl/absent.war").toString())).isEqualTo(2);
    assertThat(f.err()).contains("does not exist");
  }

  @Test
  void should_say_vanilla_then_customized_and_exit_0_either_way() throws Exception {
    CommandsTest.Fixture f = fixture();
    assertThat(f.run("baseline", "add", war().toString())).isEqualTo(0);
    assertThat(f.run("scan")).isEqualTo(0);
    assertThat(f.out())
        .contains("baseline: " + Wars.RELEASE_ID)
        .contains("vanilla: no vendor file was changed")
        .containsPattern("INSTALLER +P +" + Wars.QUARTZ);

    Path webapp = f.hf.settings.webappDir();
    Wars.write(webapp, Wars.CONTEXT, Wars.vendor().get(Wars.CONTEXT).replace("4", "16"));
    Wars.write(webapp, Packages.LIB + "iijdbc.jar", "a driver");
    Wars.write(webapp, "WEB-INF/applicationContext-externalAuth-LDAP.xml", "<beans/>\n");
    Wars.write(webapp, "WEB-INF/logs/jasperserver.log", "a line\n");
    assertThat(f.run("scan")).isEqualTo(0);
    assertThat(f.out())
        .contains("customized: 1 changed, 1 added, 0 removed")
        .containsPattern("CHANGED +X +" + Wars.CONTEXT)
        .containsPattern("ADDED +B +" + Packages.LIB + "iijdbc.jar")
        .contains("External authentication")
        .contains("  WEB-INF/applicationContext-externalAuth-LDAP.xml")
        .contains("GENERATED  1 files");
  }

  @Test
  void should_verify_where_the_site_and_a_package_meet_when_a_baseline_fits() throws Exception {
    CommandsTest.Fixture f = fixture();
    assertThat(f.run("baseline", "add", war().toString())).isEqualTo(0);
    Path webapp = f.hf.settings.webappDir();
    Wars.write(webapp, Wars.CONTEXT, Wars.vendor().get(Wars.CONTEXT).replace("4", "16"));
    Wars.write(webapp, Wars.SECURITY, Wars.vendor().get(Wars.SECURITY).replace("10", "50"));
    Wars.write(webapp, Wars.SCRIPT, "console.log('site');\n");
    String before = Files.readString(webapp.resolve(Wars.SECURITY));

    // what `scan --package` said before 0.6, `verify` says whenever a baseline fits
    assertThat(f.run("verify", hotfix().toString())).isEqualTo(0);
    assertThat(f.out())
        .contains("against this site (4 files under the webapp)")
        .containsPattern(Wars.CONTEXT + " +X +site change only +keep")
        .containsPattern(Wars.SECURITY + " +P +collision +merge")
        .containsPattern(Wars.SCRIPT + " +G +collision +replace \\(the site's change is lost\\)")
        .contains("1 replaced (vendor change only)")
        .contains("1 file(s) changed by both the site and the hotfix need a merge")
        .contains("1 script, stylesheet or binary file(s) the site changed are replaced");
    // read-only
    assertThat(Files.readString(webapp.resolve(Wars.SECURITY))).isEqualTo(before);
  }

  @Test
  void should_add_the_baseline_of_a_hotfix_package() throws Exception {
    CommandsTest.Fixture f = fixture();
    assertThat(f.run("baseline", "add", hotfix().toString())).isEqualTo(0);
    assertThat(f.out()).contains("added JRSHF-10.0.0-20260730-0457: 4 files, 3 kept for merging");
    assertThat(f.run("baseline", "list")).isEqualTo(0);
    assertThat(f.out()).contains("hotfix");
  }
}

package com.jaspersoft.jrshotfix.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.hotfix.SiteFixture;
import com.jaspersoft.jrshotfix.pkg.Packages;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scenario 19 of the 0.2 design: a hotfixed WAR made from a WAR, with the same merge, without a
 * server, driven through the shaded jar. Invariants: as {@link ScenarioTest}; the stand-in Tomcat
 * of the fixture is never stopped, since no server is the target.
 */
class WarTargetTest {

  @TempDir Path tmp;

  private static Map<String, String> entries(Path war) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    try (InputStream in = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (!e.isDirectory()) {
          out.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
      }
    }
    return out;
  }

  private static String sha(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  @Test
  void s19_should_make_a_hotfixed_war_from_a_customized_war_without_touching_a_server()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Map<String, String> site = new LinkedHashMap<>(Wars.vendor());
      site.put(
          Wars.QUARTZ, "# scheduler\nreport.scheduler.web.deployment.uri=http://reports:8081/x\n");
      String siteContext = Wars.vendor().get(Wars.CONTEXT).replace("\"4\"", "\"16\"");
      site.put(Wars.CONTEXT, siteContext);
      site.put(
          Wars.SECURITY,
          Wars.vendor().get(Wars.SECURITY).replace("allow.list=a,b", "allow.list=a,b,c"));
      Path in = Wars.war(tmp.resolve("wars/jasperserver-pro.war"), site);
      Path vendor = Wars.war(tmp.resolve("wars/vendor.war"), Wars.vendor());
      Path out = tmp.resolve("wars/jasperserver-pro-hotfixed.war");
      Map<String, byte[]> outer = new LinkedHashMap<>();
      outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
      outer.put("jasperserver-pro.zip", Packages.zipBytes(SiteFixture.hotfixPayload(), null));
      Path pkg = Packages.zip(tmp.resolve("packages/hotfix-war.zip"), outer);
      String inSha = sha(in);

      f.cli.run("baseline", "add", vendor.toString()).assertExit(0);
      Cli.Result scan = f.cli.run("scan", "--war", in.toString()).assertExit(0);
      assertThat(scan.stdout()).contains("customized: 2 changed, 0 added, 0 removed");

      Cli.Result apply =
          f.cli
              .run(
                  "apply", pkg.toString(), "--war", in.toString(), "--out", out.toString(), "--yes")
              .assertExit(0);
      assertThat(apply.stdout()).contains("record-war").contains("Kept as the site has it");

      Map<String, String> result = entries(out);
      assertThat(result.get(Wars.CONTEXT)).isEqualTo(siteContext);
      assertThat(result.get(Wars.SECURITY)).contains("allow.list=a,b,c").contains("fresh=1");
      assertThat(result.get(Wars.WEB_XML)).contains(">main2<");
      assertThat(result.get(Wars.QUARTZ)).contains("reports:8081").contains("new.key=1");
      assertThat(result.get(Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
      assertThat(sha(in)).as("the input WAR is never modified").isEqualTo(inSha);
      assertThat(out.resolveSibling(out.getFileName() + ".jrs-hotfix.json")).exists();
      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.tomcatRunning()).as("no server was touched").isTrue();
      // the server's ledger is not the WAR's inventory
      assertThat(f.cli.run("list").assertExit(0).stdout()).contains("no hotfixes recorded");
    }
  }
}

package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.hotfix.SiteFixture;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.war.WarFileTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A WAR as the target: {@code apply --war --out}, {@code scan --war}, {@code merge prepare --war}.
 */
class WarCommandsTest {

  private static final Pattern MERGE_ID = Pattern.compile("m-\\d{8}-\\d{6}-[0-9a-f]{4}");

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

  /** A site's WAR: the vendor's files as an installer leaves them, plus {@code edits}. */
  private Path siteWar(String name, Map<String, String> edits) throws Exception {
    Map<String, String> files = new LinkedHashMap<>(Wars.vendor());
    files.put(
        Wars.QUARTZ, "# scheduler\nreport.scheduler.web.deployment.uri=http://reports:8081/x\n");
    files.put(Wars.CONTAINER, "<Context><Resource username=\"jasperdb\"/></Context>\n");
    files.putAll(edits);
    return Wars.war(tmp.resolve("wars/" + name), files);
  }

  /** The hotfix of {@link SiteFixture}, with an installation-tree file beside the webapp's. */
  private Path hotfix() throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        Packages.zipBytes(
            SiteFixture.hotfixPayload(), "Deleted files:\n" + Packages.LIB + "bar-0.9.jar\n"));
    outer.put(
        "js-install.zip", Packages.zipBytes(Map.of("buildomatic/lib/tool-2.0.jar", "tool"), null));
    return Packages.zip(tmp.resolve("dl/hotfix-war.zip"), outer);
  }

  private static String mergeId(String text) {
    Matcher m = MERGE_ID.matcher(text);
    assertThat(m.find()).as("a merge id in:\n%s", text).isTrue();
    return m.group();
  }

  @Test
  void should_write_a_hotfixed_war_beside_its_record_and_leave_the_input_alone() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path in = siteWar("jasperserver-pro.war", Map.of());
    String inSha = f.hf.sha(in);
    Path out = tmp.resolve("out/jasperserver-pro-fixed.war");
    Files.createDirectories(out.getParent());

    assertThat(
            f.run(
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out.toString(),
                "--plan"))
        .isEqualTo(0);
    assertThat(f.out())
        .contains("preflight-war")
        .contains("assemble-war")
        .contains("check-war")
        .contains("record-war")
        .doesNotContain("stop-service")
        .doesNotContain("snapshot the files")
        .contains("1 file(s) of the package belong to the installation tree")
        .contains("no server is touched");
    assertThat(out).doesNotExist();

    assertThat(
            f.run(
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out.toString(),
                "--yes"))
        .isEqualTo(0);
    Map<String, String> result = WarFileTest.entries(out);
    assertThat(result.get(Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
    assertThat(result.get(Wars.WEB_XML)).contains(">main2<");
    assertThat(result.get(Wars.STAMPS)).contains("20260730");
    assertThat(result).doesNotContainKey(Packages.LIB + "bar-0.9.jar");
    assertThat(result).doesNotContainKey("buildomatic/lib/tool-2.0.jar");
    // without a baseline, as 0.1: the installer's properties merged, its XML kept
    assertThat(result.get(Wars.QUARTZ)).contains("reports:8081").contains("new.key=1");
    assertThat(result.get(Wars.CONTAINER)).contains("jasperdb").doesNotContain("maxTotal");
    assertThat(f.hf.sha(in)).isEqualTo(inSha);
    assertThat(out.resolveSibling(out.getFileName() + ".jrs-hotfix.tmp")).doesNotExist();

    Path sidecar = out.resolveSibling(out.getFileName() + ".jrs-hotfix.json");
    JsonNode doc = Json.mapper().readTree(Files.readString(sidecar));
    assertThat(doc.get("hotfixId").asText()).isEqualTo(SiteFixture.HOTFIX_ID);
    assertThat(doc.get("inputSha256").asText()).isEqualTo(inSha);
    assertThat(doc.get("outputSha256").asText()).isEqualTo(f.hf.sha(out));
    assertThat(doc.get("files"))
        .anySatisfy(n -> assertThat(n.get("path").asText()).isEqualTo(Wars.WEB_XML));
    assertThat(doc.get("kept"))
        .anySatisfy(n -> assertThat(n.get("path").asText()).isEqualTo(Wars.CONTAINER));
    // a WAR is not the server's inventory: nothing in the ledger
    assertThat(f.run("list")).isEqualTo(0);
    assertThat(f.out()).contains("no hotfixes recorded");
    // the output is never overwritten
    assertThat(
            f.run(
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out.toString(),
                "--yes"))
        .isEqualTo(2);
    assertThat(f.out() + f.err()).contains("exists already");
  }

  @Test
  void should_keep_and_merge_the_sites_changes_when_the_war_has_a_baseline() throws Exception {
    CommandsTest.Fixture f = fixture();
    String siteContext = Wars.vendor().get(Wars.CONTEXT).replace("\"4\"", "\"16\"");
    Map<String, String> edits = new LinkedHashMap<>();
    edits.put(Wars.CONTEXT, siteContext);
    edits.put(
        Wars.SECURITY,
        Wars.vendor().get(Wars.SECURITY).replace("allow.list=a,b", "allow.list=a,b,c"));
    edits.put(Wars.WEB_XML, Wars.vendor().get(Wars.WEB_XML).replace(">main<", ">site-main<"));
    Path in = siteWar("jasperserver-pro.war", edits);
    Path out = tmp.resolve("wars/fixed.war");
    Path vendorWar = Wars.war(tmp.resolve("dl/vendor.war"), Wars.vendor());

    assertThat(f.run("scan", "--war", in.toString())).isEqualTo(2);
    assertThat(f.run("baseline", "add", vendorWar.toString())).isEqualTo(0);
    assertThat(f.run("scan", "--war", in.toString())).isEqualTo(0);
    assertThat(f.out())
        .contains("customized: 3 changed, 0 added, 0 removed")
        .containsPattern("CHANGED +X +" + Wars.CONTEXT);

    // web.xml collides: prepare, resolve with the hotfix's copy, apply with the merge
    assertThat(
            f.run(
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out.toString(),
                "--yes"))
        .isEqualTo(2);
    assertThat(f.err()).contains(Wars.WEB_XML + " (CONFLICT)");
    String id = mergeId(f.err());
    assertThat(f.run("merge", "resolve", id, Wars.WEB_XML, "--theirs")).isEqualTo(0);
    assertThat(
            f.run(
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out.toString(),
                "--merge",
                id,
                "--yes"))
        // failed once on a CI runner with exit 2 and no output kept; the output is kept now
        .as("apply --war --merge; stdout: %s; stderr: %s", f.out(), f.err())
        .isEqualTo(0);
    assertThat(f.out()).contains("Merged (2)").contains("Kept as the site has it (2)");
    Map<String, String> result = WarFileTest.entries(out);
    assertThat(result.get(Wars.CONTEXT)).isEqualTo(siteContext);
    assertThat(result.get(Wars.SECURITY)).contains("allow.list=a,b,c").contains("fresh=1");
    assertThat(result.get(Wars.WEB_XML)).contains(">main2<").doesNotContain("site-main");
    assertThat(result.get(Wars.QUARTZ)).contains("reports:8081");
    // the hotfix is the vendor's level for the next hotfix on that WAR
    assertThat(f.run("baseline", "list")).isEqualTo(0);
    assertThat(f.out()).contains(SiteFixture.HOTFIX_ID);
    assertThat(f.run("scan", "--war", out.toString())).isEqualTo(0);
    assertThat(f.out()).contains("baseline: " + Wars.RELEASE_ID + " + " + SiteFixture.HOTFIX_ID);
  }

  @Test
  void should_prepare_a_merge_for_a_war_with_merge_prepare() throws Exception {
    CommandsTest.Fixture f = fixture();
    Map<String, String> edits =
        Map.of(Wars.LOGIN, Wars.vendor().get(Wars.LOGIN).replace("Welcome", "Hello"));
    Path in = siteWar("jasperserver-pro.war", edits);
    assertThat(
            f.run(
                "baseline",
                "add",
                Wars.war(tmp.resolve("dl/vendor.war"), Wars.vendor()).toString()))
        .isEqualTo(0);
    assertThat(f.run("merge", "prepare", hotfix().toString(), "--war", in.toString())).isEqualTo(0);
    assertThat(f.out()).containsPattern("AUTO +T +" + Wars.LOGIN);
  }

  @Test
  void should_use_a_home_beside_the_war_when_none_is_given() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path in = siteWar("jasperserver-pro.war", Map.of());
    Path out = tmp.resolve("wars/fixed.war");
    int code =
        f.runExactly(
            List.of(
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out.toString(),
                "--yes"));
    assertThat(code).isEqualTo(0);
    Path home = in.resolveSibling("jrs-hotfix");
    assertThat(home.resolve("settings.json")).exists();
    assertThat(home.resolve("wars/webapps/jasperserver-pro/WEB-INF/web.xml")).exists();
    assertThat(home.resolve("runs")).isDirectory();
    assertThat(out).exists();
  }

  @Test
  void should_refuse_war_without_out_and_a_file_that_is_not_a_webapp() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path in = siteWar("jasperserver-pro.war", Map.of());
    assertThat(f.run("apply", hotfix().toString(), "--war", in.toString())).isEqualTo(1);
    assertThat(f.err()).contains("--war and --out go together");
    Path notAWar = Packages.zip(tmp.resolve("wars/x.war"), Map.of("a.txt", new byte[] {1}));
    assertThat(f.run("scan", "--war", notAWar.toString())).isEqualTo(2);
    assertThat(
            f.run(
                "apply",
                hotfix().toString(),
                "--war",
                tmp.resolve("wars/absent.war").toString(),
                "--out",
                tmp.resolve("wars/o.war").toString()))
        .isEqualTo(2);
    assertThat(f.err()).contains("does not exist");
  }
}

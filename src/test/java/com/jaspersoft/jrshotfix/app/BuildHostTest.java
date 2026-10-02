package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.hotfix.SiteFixture;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.war.WarFile;
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
 * A build host as the target (0.7 design, section 2.1): the distribution's WAR replaced in place
 * and its buildomatic patched, with an undo; and {@code apply --war --install-out}.
 */
class BuildHostTest {

  private static final String TOOL = "buildomatic/lib/tool-2.0.jar";
  private static final String ANT = "buildomatic/js-ant.sh";

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

  /** An unpacked distribution: buildomatic with a file of its own, and the vendor's WAR. */
  private Path distribution() throws Exception {
    Path dist = tmp.resolve("dist");
    Files.createDirectories(dist.resolve("buildomatic/lib"));
    Files.writeString(dist.resolve(ANT), "ant\n", StandardCharsets.UTF_8);
    Wars.war(dist.resolve("jasperserver-pro.war"), Wars.vendor());
    return dist;
  }

  /** The hotfix of {@link SiteFixture}, with an installation-tree file beside the webapp's. */
  private Path hotfix() throws Exception {
    Path file = tmp.resolve("dl/hotfix-war.zip");
    if (Files.isRegularFile(file)) {
      return file;
    }
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        Packages.zipBytes(
            SiteFixture.hotfixPayload(), "Deleted files:\n" + Packages.LIB + "bar-0.9.jar\n"));
    outer.put("js-install.zip", Packages.zipBytes(Map.of(TOOL, "tool"), null));
    return Packages.zip(file, outer);
  }

  /** Runs {@code args} with {@code home} as the home, as no terminal. */
  private static int run(CommandsTest.Fixture f, Path home, String... args) {
    List<String> all = new ArrayList<>(List.of(args));
    all.addAll(List.of("--home", home.toString(), "--non-interactive"));
    return f.runExactly(all);
  }

  @Test
  void should_hotfix_a_build_host_in_place_and_undo_it() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path dist = distribution();
    Path home = dist.resolve("jrs-hotfix");
    Path war = dist.resolve("jasperserver-pro.war");
    String vendorSha = f.hf.sha(war);

    assertThat(run(f, home, "settings", "detect")).isZero();
    assertThat(f.out()).contains("a build host").containsPattern("service.kind +none");

    assertThat(run(f, home, "apply", hotfix().toString(), "--plan"))
        .as("%s%s", f.out(), f.err())
        .isZero();
    assertThat(f.out())
        .contains("check the distribution")
        .contains("assemble-war")
        .contains("swap-war")
        .contains("promote-undo")
        .doesNotContain("stop-service")
        .contains("deploy the hotfixed WAR with buildomatic");
    assertThat(f.hf.sha(war)).isEqualTo(vendorSha);

    assertThat(run(f, home, "apply", hotfix().toString(), "--yes"))
        .as("%s%s", f.out(), f.err())
        .isZero();
    Map<String, String> result = WarFileTest.entries(war);
    assertThat(result.get(Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
    assertThat(result.get(Wars.WEB_XML)).contains(">main2<");
    assertThat(result).doesNotContainKey(Packages.LIB + "bar-0.9.jar").doesNotContainKey(TOOL);
    assertThat(Files.readString(dist.resolve(TOOL))).isEqualTo("tool");
    assertThat(Files.readString(dist.resolve(ANT))).isEqualTo("ant\n");
    assertThat(WarFile.temporary(war)).doesNotExist();
    // moved, not copied: the earlier WAR is the undo's
    assertThat(f.hf.sha(home.resolve("undo/war/jasperserver-pro.war"))).isEqualTo(vendorSha);

    // the webapp read is the hotfixed WAR's, unpacked again
    assertThat(run(f, home, "list")).isZero();
    assertThat(f.out()).contains("can be undone:  " + SiteFixture.HOTFIX_ID);
    assertThat(run(f, home, "apply", hotfix().toString(), "--yes")).isEqualTo(2);
    assertThat(f.out() + f.err()).contains("already");

    assertThat(run(f, home, "rollback", "--plan")).isZero();
    assertThat(f.out()).contains("check-undo").contains("restore-war").doesNotContain("stop");
    assertThat(run(f, home, "rollback", "--yes")).isZero();
    assertThat(f.hf.sha(war)).isEqualTo(vendorSha);
    assertThat(dist.resolve(TOOL)).doesNotExist();
    assertThat(Files.readString(dist.resolve(ANT))).isEqualTo("ant\n");
    // neither WAR is left in the home once the rollback has ended
    try (var walk = Files.walk(home)) {
      assertThat(
              walk.map(p -> p.getFileName().toString())
                  .filter(n -> n.endsWith(".war") || n.endsWith(".hotfixed"))
                  .toList())
          .isEmpty();
    }
    assertThat(run(f, home, "list")).isZero();
    assertThat(f.out()).contains("can be undone:  nothing");
  }

  @Test
  void should_keep_and_merge_the_sites_changes_in_buildomatic_and_the_war() throws Exception {
    CommandsTest.Fixture f = fixture();
    String build = "<project>\n<target name=\"a\"/>\n\n\n\n<target name=\"b\"/>\n</project>\n";
    Path vendor = tmp.resolve("vendor-dist");
    Files.createDirectories(vendor.resolve("buildomatic"));
    Files.writeString(vendor.resolve("buildomatic/build.xml"), build, StandardCharsets.UTF_8);
    Wars.war(vendor.resolve("jasperserver-pro.war"), Wars.vendor());

    Path dist = tmp.resolve("dist");
    Files.createDirectories(dist.resolve("buildomatic"));
    Files.writeString(
        dist.resolve("buildomatic/build.xml"),
        build.replace("name=\"a\"", "name=\"a\" site=\"1\""),
        StandardCharsets.UTF_8);
    Map<String, String> site = new LinkedHashMap<>(Wars.vendor());
    site.put(Wars.CONTEXT, Wars.vendor().get(Wars.CONTEXT).replace("\"4\"", "\"16\""));
    Wars.war(dist.resolve("jasperserver-pro.war"), site);
    Path home = dist.resolve("jrs-hotfix");

    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(SiteFixture.hotfixPayload(), null));
    outer.put(
        "js-install.zip",
        Packages.zipBytes(
            Map.of("buildomatic/build.xml", build.replace("name=\"b\"", "name=\"b\" hotfix=\"1\"")),
            null));
    Path hotfix = Packages.zip(tmp.resolve("dl/hotfix-build.zip"), outer);

    assertThat(run(f, home, "settings", "detect")).isZero();
    assertThat(run(f, home, "baseline", "add", vendor.toString()))
        .as("%s%s", f.out(), f.err())
        .isZero();
    assertThat(run(f, home, "scan")).isZero();
    assertThat(f.out()).contains(Wars.CONTEXT).contains("buildomatic/build.xml");

    // merged cleanly; an XML file waits for a look, as in the webapp
    assertThat(run(f, home, "apply", hotfix.toString(), "--yes")).isEqualTo(2);
    assertThat(f.err()).contains("buildomatic/build.xml (REVIEW)");
    Matcher id = Pattern.compile("m-\\d{8}-\\d{6}-[0-9a-f]{4}").matcher(f.err());
    assertThat(id.find()).isTrue();
    assertThat(run(f, home, "merge", "resolve", id.group(), "buildomatic/build.xml", "--merged"))
        .as("%s%s", f.out(), f.err())
        .isZero();
    assertThat(run(f, home, "apply", hotfix.toString(), "--merge", id.group(), "--yes"))
        .as("%s%s", f.out(), f.err())
        .isZero();
    assertThat(Files.readString(dist.resolve("buildomatic/build.xml")))
        .contains("site=\"1\"")
        .contains("hotfix=\"1\"");
    Map<String, String> result = WarFileTest.entries(dist.resolve("jasperserver-pro.war"));
    assertThat(result.get(Wars.CONTEXT)).contains("\"16\"");
    assertThat(result.get(Packages.LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
  }

  @Test
  void should_put_the_war_back_when_the_apply_fails_after_replacing_it() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path dist = distribution();
    Path home = dist.resolve("jrs-hotfix");
    Path war = dist.resolve("jasperserver-pro.war");
    String vendorSha = f.hf.sha(war);
    assertThat(run(f, home, "settings", "detect")).isZero();
    // the last step cannot keep the undo: both of its places are taken
    Files.createDirectories(home.resolve("undo"));
    Files.createDirectories(home.resolve("undo.old"));

    assertThat(run(f, home, "apply", hotfix().toString(), "--yes"))
        .as("%s%s", f.out(), f.err())
        .isEqualTo(3);
    assertThat(f.hf.sha(war)).isEqualTo(vendorSha);
    assertThat(dist.resolve(TOOL)).doesNotExist();
    assertThat(WarFile.temporary(war)).doesNotExist();

    Files.delete(home.resolve("undo.old"));
    Files.delete(home.resolve("undo"));
    assertThat(run(f, home, "apply", hotfix().toString(), "--yes")).isZero();
    assertThat(f.hf.sha(war)).isNotEqualTo(vendorSha);
  }

  @Test
  void should_refuse_to_undo_when_the_war_changed_since_the_apply() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path dist = distribution();
    Path home = dist.resolve("jrs-hotfix");
    Path war = dist.resolve("jasperserver-pro.war");
    assertThat(run(f, home, "settings", "detect")).isZero();
    assertThat(run(f, home, "apply", hotfix().toString(), "--yes"))
        .as("%s%s", f.out(), f.err())
        .isZero();
    String hotfixed = f.hf.sha(war);

    // someone built another WAR into the distribution since
    Map<String, String> other = new LinkedHashMap<>(WarFileTest.entries(war));
    other.put("WEB-INF/other.txt", "other\n");
    Files.delete(war);
    Wars.war(war, other);

    assertThat(run(f, home, "rollback", "--yes")).isEqualTo(2);
    assertThat(f.out() + f.err()).contains("changed since").contains("jasperserver-pro.war");
    assertThat(f.hf.sha(war)).isNotEqualTo(hotfixed);
    assertThat(dist.resolve(TOOL)).exists();
  }

  @Test
  void should_patch_an_installation_tree_beside_a_war_and_undo_it() throws Exception {
    CommandsTest.Fixture f = fixture();
    Path in = Wars.war(tmp.resolve("in/jasperserver-pro.war"), Wars.vendor());
    Path out = tmp.resolve("in/fixed.war");
    Path tree = tmp.resolve("elsewhere");
    Path home = tmp.resolve("war-home");

    assertThat(
            f.runExactly(
                List.of("apply", hotfix().toString(), "--install-out", tree.toString(), "--yes")))
        .isEqualTo(1);

    assertThat(
            run(
                f,
                home,
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out.toString(),
                "--install-out",
                tree.toString(),
                "--yes"))
        .isZero();
    assertThat(f.out()).contains("applied in " + tree);
    assertThat(Files.readString(tree.resolve(TOOL))).isEqualTo("tool");
    assertThat(WarFileTest.entries(out)).doesNotContainKey(TOOL);

    assertThat(run(f, home, "rollback", "--yes")).isZero();
    assertThat(tree.resolve(TOOL)).doesNotExist();
    // the output WAR is the operator's
    assertThat(out).exists();

    // a server's home keeps its own undo: refused before anything is written
    Path out2 = tmp.resolve("in/fixed2.war");
    assertThat(
            f.run(
                "apply",
                hotfix().toString(),
                "--war",
                in.toString(),
                "--out",
                out2.toString(),
                "--install-out",
                tree.toString(),
                "--yes"))
        .isEqualTo(2);
    assertThat(f.out() + f.err()).contains("not one for WARs");
    assertThat(out2).doesNotExist();
  }
}

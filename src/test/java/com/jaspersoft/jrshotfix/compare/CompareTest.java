package com.jaspersoft.jrshotfix.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.compare.Compare.Item;
import com.jaspersoft.jrshotfix.compare.Compare.Kind;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.text.Conflict;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareTest {

  static final String PROPS = "WEB-INF/classes/app.properties";
  static final String PAGE = "WEB-INF/jsp/page.jsp";
  static final String CONFIG = "WEB-INF/applicationContext-site.xml";
  static final String JAR = "WEB-INF/lib/lib-1.0.jar";
  static final String LOG = "WEB-INF/logs/app.log";

  @TempDir Path tmp;

  private Path webapp(String name, Map<String, String> files) throws Exception {
    Path dir = tmp.resolve(name);
    Files.createDirectories(dir.resolve("WEB-INF"));
    for (Map.Entry<String, String> e : files.entrySet()) {
      Path f = dir.resolve(e.getKey());
      Files.createDirectories(f.getParent());
      Files.writeString(f, e.getValue(), StandardCharsets.ISO_8859_1);
    }
    return dir;
  }

  private Input open(Path path) {
    return Input.open(path.toString(), Optional.empty(), tmp.resolve("temp"));
  }

  private static Item item(Compare.Report r, String path) {
    return r.items().stream()
        .filter(i -> i.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + path + " in " + r.items()));
  }

  @Test
  void should_list_what_differs_between_two_webapps() throws Exception {
    Map<String, String> a = new LinkedHashMap<>();
    a.put(PROPS, "x=1\n");
    a.put(PAGE, "<p>a</p>\n");
    a.put(JAR, "jar");
    a.put(LOG, "a log");
    Map<String, String> b = new LinkedHashMap<>();
    b.put(PROPS, "x=2\n");
    b.put(PAGE, "<p>a</p>\r\n");
    b.put(CONFIG, "<beans/>\n");
    b.put(LOG, "another log");
    try (Input x = open(webapp("a", a));
        Input y = open(webapp("b", b))) {
      Compare.Report r = Compare.twoWay(x, y);

      assertThat(item(r, PROPS).kind()).isEqualTo(Kind.DIFFERS);
      assertThat(item(r, JAR).kind()).isEqualTo(Kind.ONLY_FIRST);
      assertThat(item(r, CONFIG).kind()).isEqualTo(Kind.ONLY_SECOND);
      // line ends only: the same; a log: written at run time, not compared
      assertThat(r.items()).extracting(Item::path).doesNotContain(PAGE, LOG);
      assertThat(r.same()).isEqualTo(1);
      assertThat(r.generated()).isEqualTo(1);
      assertThat(r.differ()).isTrue();
      assertThat(r.compared()).containsExactly(Area.WEBAPP);
    }
  }

  @Test
  void should_find_a_war_and_its_unpacked_copy_the_same() throws Exception {
    Path war = Wars.war(tmp.resolve("in.war"), Wars.vendor());
    Path dir = tmp.resolve("unpacked");
    Wars.install(dir, Wars.vendor());
    try (Input x = open(war);
        Input y = open(dir)) {
      Compare.Report r = Compare.twoWay(x, y);
      assertThat(r.differ()).isFalse();
      assertThat(r.same()).isEqualTo(Wars.vendor().size());
    }
    // the WAR's temporary copy is gone with the input
    try (var left = Files.list(tmp.resolve("temp"))) {
      assertThat(left).isEmpty();
    }
  }

  @Test
  void should_decide_every_file_of_three_as_a_merge_does_and_write_the_result() throws Exception {
    Map<String, String> base = new LinkedHashMap<>();
    base.put(PROPS, "a=1\nb=1\nc=1\n");
    base.put(PAGE, "<p>one</p>\n\n\n\n<p>two</p>\n");
    base.put(CONFIG, "<beans>\n<bean id=\"x\"/>\n</beans>\n");
    base.put(JAR, "jar 1");
    base.put("WEB-INF/mine-only.txt", "v1\n");
    base.put("WEB-INF/theirs-only.txt", "v1\n");
    base.put("WEB-INF/alike.txt", "v1\n");
    Map<String, String> mine = new LinkedHashMap<>(base);
    mine.put(PROPS, "a=2\nb=1\nc=9\n");
    mine.put(PAGE, "<p>ONE</p>\n\n\n\n<p>two</p>\n");
    mine.put(CONFIG, "<beans>\n<bean id=\"mine\"/>\n</beans>\n");
    mine.put(JAR, "jar mine");
    mine.put("WEB-INF/mine-only.txt", "v2\n");
    mine.put("WEB-INF/alike.txt", "v2\n");
    Map<String, String> theirs = new LinkedHashMap<>(base);
    theirs.put(PROPS, "a=1\nb=3\nc=7\n");
    theirs.put(PAGE, "<p>one</p>\n\n\n\n<p>TWO</p>\n");
    theirs.put(CONFIG, "<beans>\n<bean id=\"theirs\"/>\n</beans>\n");
    theirs.put(JAR, "jar theirs");
    theirs.put("WEB-INF/theirs-only.txt", "v3\n");
    theirs.put("WEB-INF/alike.txt", "v2\n");
    try (Input b = open(webapp("base", base));
        Input m = open(webapp("mine", mine));
        Input t = open(webapp("theirs", theirs))) {
      Compare.Report r = Compare.threeWay(b, m, t);

      assertThat(item(r, "WEB-INF/mine-only.txt").kind()).isEqualTo(Kind.ONLY_MINE);
      assertThat(item(r, "WEB-INF/theirs-only.txt").kind()).isEqualTo(Kind.ONLY_THEIRS);
      assertThat(item(r, "WEB-INF/alike.txt").kind()).isEqualTo(Kind.BOTH_ALIKE);
      // a by mine, b by theirs, c by both: a conflict on c
      assertThat(item(r, PROPS).kind()).isEqualTo(Kind.CONFLICT);
      assertThat(item(r, PROPS).note()).contains("c");
      assertThat(item(r, PAGE).kind()).isEqualTo(Kind.MERGED);
      assertThat(item(r, CONFIG).kind()).isEqualTo(Kind.CONFLICT);
      assertThat(item(r, JAR).kind()).isEqualTo(Kind.CONFLICT);
      assertThat(item(r, JAR).note()).contains("not merged");

      Path out = tmp.resolve("out");
      Compare.write(r, b, m, t, out);
      Path w = out.resolve("webapp");
      assertThat(w.resolve(PAGE)).hasContent("<p>ONE</p>\n\n\n\n<p>TWO</p>");
      assertThat(Files.readString(w.resolve(CONFIG))).contains(Conflict.MARK_MINE);
      assertThat(Files.readString(w.resolve(PROPS))).contains("a=2").contains("b=3");
      assertThat(w.resolve("WEB-INF/mine-only.txt")).hasContent("v2");
      assertThat(w.resolve("WEB-INF/theirs-only.txt")).hasContent("v3");
      assertThat(w.resolve(JAR)).hasContent("jar mine");
    }
  }

  @Test
  void should_merge_properties_both_changed_on_other_keys() throws Exception {
    try (Input b = open(webapp("base", Map.of(PROPS, "a=1\nb=1\n")));
        Input m = open(webapp("mine", Map.of(PROPS, "a=2\nb=1\n")));
        Input t = open(webapp("theirs", Map.of(PROPS, "a=1\nb=3\n")))) {
      Compare.Report r = Compare.threeWay(b, m, t);
      assertThat(item(r, PROPS).kind()).isEqualTo(Kind.MERGED);
      assertThat(r.count(Kind.CONFLICT)).isZero();
    }
  }

  @Test
  void should_read_both_areas_of_a_distribution_and_compare_only_the_shared_one() throws Exception {
    Path dist = tmp.resolve("dist");
    Wars.war(dist.resolve("jasperserver-pro.war"), Wars.vendor());
    Files.createDirectories(dist.resolve("buildomatic"));
    Files.writeString(dist.resolve("buildomatic/js-ant.sh"), "ant\n");
    Path war = Wars.war(tmp.resolve("only.war"), Wars.vendor());
    try (Input d = open(dist);
        Input w = open(war)) {
      assertThat(d.areas()).containsExactlyInAnyOrder(Area.WEBAPP, Area.INSTALLATION);
      assertThat(d.files(Area.INSTALLATION)).containsExactly("buildomatic/js-ant.sh");
      Compare.Report r = Compare.twoWay(d, w);
      assertThat(r.compared()).containsExactly(Area.WEBAPP);
      assertThat(r.skipped()).containsExactly(Area.INSTALLATION);
      assertThat(r.differ()).isFalse();
    }
  }

  @Test
  void should_read_a_hotfix_package_as_its_two_areas() throws Exception {
    try (Input p = open(Packages.standard(tmp.resolve("dl/hotfix.zip")))) {
      assertThat(p.areas()).containsExactlyInAnyOrder(Area.WEBAPP, Area.INSTALLATION);
      assertThat(p.files(Area.INSTALLATION)).contains("buildomatic/lib/tool-2.0.jar");
    }
  }

  @Test
  void should_refuse_what_is_absent_or_not_jasperreports_server() throws Exception {
    assertThatThrownBy(() -> open(tmp.resolve("absent")))
        .isInstanceOf(HotfixException.class)
        .hasFieldOrPropertyWithValue("kind", HotfixException.PRECHECK);
    Path other = Files.createDirectories(tmp.resolve("other"));
    assertThatThrownBy(() -> open(other))
        .isInstanceOf(HotfixException.class)
        .hasFieldOrPropertyWithValue("kind", HotfixException.UNSUPPORTED);
    Path zip = Packages.zip(tmp.resolve("x.zip"), Map.of("a.txt", new byte[] {1}));
    assertThatThrownBy(() -> open(zip))
        .isInstanceOf(HotfixException.class)
        .hasMessageContaining("cannot be compared");
  }

  /** Every file under {@code dir} named {@code name}. */
  private static List<Path> named(Path dir, String name) throws Exception {
    try (var walk = Files.walk(dir)) {
      return walk.filter(p -> p.getFileName().toString().equals(name)).toList();
    }
  }

  @Test
  void should_refuse_an_archive_whose_names_would_escape_and_write_nothing_outside()
      throws Exception {
    // a webapp whose entry climbs out of the directory it is unpacked into
    Map<String, byte[]> webapp = new LinkedHashMap<>();
    webapp.put(PAGE, "<p>a</p>\n".getBytes(StandardCharsets.UTF_8));
    webapp.put("../escaped.txt", new byte[] {1});
    Path climbing = Packages.zip(tmp.resolve("dl/climbing.war"), webapp);
    assertThatThrownBy(() -> open(climbing))
        .isInstanceOf(HotfixException.class)
        .hasFieldOrPropertyWithValue("kind", HotfixException.UNSUPPORTED)
        .hasMessageContaining("unusable entry name");

    // a distribution whose WAR sits in a folder above the archive's root
    Map<String, byte[]> distribution = new LinkedHashMap<>();
    distribution.put(
        "../outside/jasperserver-pro.war",
        Files.readAllBytes(Wars.war(tmp.resolve("src/vendor.war"), Wars.vendor())));
    Path escaping = Packages.zip(tmp.resolve("dl/escaping.zip"), distribution);
    assertThatThrownBy(() -> open(escaping))
        .isInstanceOf(HotfixException.class)
        .hasFieldOrPropertyWithValue("kind", HotfixException.UNSUPPORTED)
        .hasMessageContaining("unusable entry name");

    assertThat(named(tmp, "escaped.txt")).isEmpty();
    assertThat(named(tmp, "outside")).isEmpty();
  }

  @Test
  void should_show_a_files_differences_as_a_unified_diff() throws Exception {
    try (Input x = open(webapp("a", Map.of(PAGE, "<p>a</p>\n")));
        Input y = open(webapp("b", Map.of(PAGE, "<p>b</p>\n")))) {
      List<String> diff = Compare.show(PAGE, List.of(x, y));
      assertThat(diff).contains("-<p>a</p>", "+<p>b</p>");
    }
  }
}

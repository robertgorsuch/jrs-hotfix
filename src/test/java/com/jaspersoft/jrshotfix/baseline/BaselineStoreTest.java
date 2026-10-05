package com.jaspersoft.jrshotfix.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.baseline.BaselineManifest.BaseFile;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.Sums;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineStoreTest {

  @TempDir Path tmp;

  private BaselineStore store() {
    return new BaselineStore(new Home(tmp.resolve("home")), Clock.systemUTC());
  }

  private static BaseFile file(BaselineManifest m, String path) {
    return m.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
  }

  @Test
  void should_hash_every_file_and_keep_only_the_mergeable_ones_when_a_war_is_added()
      throws Exception {
    BaselineStore store = store();
    Map<String, String> vendor = Wars.vendor();
    BaselineManifest m = store.addRelease(Wars.war(tmp.resolve("dl/jasperserver-pro.war"), vendor));

    assertThat(m.id()).isEqualTo(Wars.RELEASE_ID);
    assertThat(m.kind()).isEqualTo(BaselineManifest.Kind.RELEASE);
    assertThat(m.release()).isEqualTo("10.0.0");
    assertThat(m.build()).isEqualTo(Wars.BUILD);
    assertThat(m.files()).extracting(BaseFile::path).containsExactlyElementsOf(vendor.keySet());
    for (BaseFile f : m.files()) {
      assertThat(f.sha256())
          .as(f.path())
          .isEqualTo(Sums.of(vendor.get(f.path()).getBytes(StandardCharsets.ISO_8859_1)).sha256());
      boolean mergeable = FileClass.of(f.path()).mergeable();
      assertThat(f.payload()).as(f.path()).isEqualTo(mergeable);
      assertThat(Files.isRegularFile(store.payload(m.id(), f.path())))
          .as(f.path())
          .isEqualTo(mergeable);
    }
    assertThat(Files.readString(store.payload(m.id(), Wars.WEB_XML)))
        .isEqualTo(vendor.get(Wars.WEB_XML));
    // the text hash is kept for scripts too, which have no payload
    assertThat(file(m, Wars.SCRIPT).textSha256()).isPresent();
    assertThat(file(m, Wars.LOGO).textSha256()).isEmpty();
    assertThat(store.find(Wars.RELEASE_ID)).contains(m);
    assertThat(store.list()).containsExactly(m);
  }

  @Test
  void should_mark_the_files_that_hold_an_installer_placeholder() throws Exception {
    BaselineManifest m =
        store().addRelease(Wars.war(tmp.resolve("dl/jasperserver-pro.war"), Wars.vendor()));
    assertThat(m.files().stream().filter(BaseFile::installer).map(BaseFile::path))
        .containsExactlyInAnyOrder(Wars.QUARTZ, Wars.CONTAINER);
  }

  @Test
  void should_read_an_unpacked_webapp_and_a_directory_that_holds_the_war() throws Exception {
    Path unpacked = tmp.resolve("unpacked");
    Wars.install(unpacked, Wars.vendor());
    BaselineManifest fromTree = store().addRelease(unpacked);
    Wars.war(tmp.resolve("dist/jasperserver-pro.war"), Wars.vendor());
    BaselineManifest fromDir = store().addRelease(tmp.resolve("dist"));

    assertThat(fromTree.id()).isEqualTo(Wars.RELEASE_ID);
    assertThat(fromDir.id()).isEqualTo(Wars.RELEASE_ID);
    assertThat(fromTree.files())
        .extracting(BaseFile::path, BaseFile::sha256)
        .containsExactlyInAnyOrderElementsOf(
            fromDir.files().stream()
                .map(f -> org.assertj.core.groups.Tuple.tuple(f.path(), f.sha256()))
                .toList());
    // added twice: one baseline, and nothing left of the directories it was built in
    assertThat(store().list()).hasSize(1);
    try (Stream<Path> dirs = Files.list(tmp.resolve("home/baselines"))) {
      assertThat(dirs.map(p -> p.getFileName().toString())).containsExactly(Wars.RELEASE_ID);
    }
  }

  @Test
  void should_refuse_as_unsupported_when_the_source_is_not_a_jrs_10_pro_webapp() throws Exception {
    Map<String, String> noStamps = new LinkedHashMap<>(Wars.vendor());
    noStamps.remove(Wars.STAMPS);
    Path war = Wars.war(tmp.resolve("dl/other.war"), noStamps);
    assertThatThrownBy(() -> store().addRelease(war))
        .isInstanceOfSatisfying(
            HotfixException.class, e -> assertThat(e.kind()).isEqualTo(HotfixException.UNSUPPORTED))
        .hasMessageContaining("states no release and build");

    Map<String, String> nine = new LinkedHashMap<>(Wars.vendor());
    nine.put(Wars.STAMPS, Wars.stamps("20250101", "0000").replace("10.0.0", "9.0.0"));
    Path old = Wars.war(tmp.resolve("dl/nine.war"), nine);
    assertThatThrownBy(() -> store().addRelease(old)).hasMessageContaining("release 9.0.0");

    Files.createDirectories(tmp.resolve("empty"));
    assertThatThrownBy(() -> store().addRelease(tmp.resolve("empty")))
        .hasMessageContaining("neither WEB-INF nor jasperserver-pro.war");
    assertThat(store().list()).isEmpty();
  }

  /** A package that ships a jar, {@code web.xml} and a script, and deletes by name and by glob. */
  static Path hotfix(Path file) throws Exception {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(Packages.LIB + "foo-1.2.3.jar", "patched foo");
    payload.put(Wars.WEB_XML, "<web-app>\n  <!-- hotfix -->\n</web-app>\n");
    payload.put(Wars.SCRIPT, "console.log('hotfix');\n");
    payload.put(Wars.STAMPS, Wars.stamps("20260730", "0457"));
    String readme =
        "Deleted files:\n"
            + Packages.LIB
            + "bar-0.9.jar\nIMPORTANT\n"
            + Packages.LIB
            + "foo-1.*.jar\n";
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(payload, readme));
    outer.put(
        "js-install.zip", Packages.zipBytes(Map.of("buildomatic/lib/tool-2.0.jar", "tool"), null));
    return Packages.zip(file, outer);
  }

  @Test
  void should_fill_a_hotfix_baseline_from_the_files_a_package_ships_under_the_webapp()
      throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Path zip = hotfix(tmp.resolve("dl/hotfix.zip"));
    PackageContents contents = OfficialPackage.read(zip, paths, "jasperserver-pro");
    BaselineStore store = store();
    BaselineManifest m = store.addHotfix(zip, contents, "jasperserver-pro");

    assertThat(m.id()).isEqualTo("JRSHF-10.0.0-20260730-0457");
    assertThat(m.kind()).isEqualTo(BaselineManifest.Kind.HOTFIX);
    assertThat(m.build()).isEqualTo("20260730_0457");
    // the webapp's files, and the installation's apart from them (0.7)
    assertThat(m.files(Area.INSTALLATION))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.path()).isEqualTo("buildomatic/lib/tool-2.0.jar");
              assertThat(f.payload()).isFalse();
            });
    assertThat(m.files())
        .extracting(BaseFile::path)
        .containsExactlyInAnyOrder(
            Packages.LIB + "foo-1.2.3.jar", Wars.WEB_XML, Wars.SCRIPT, Wars.STAMPS);
    assertThat(Files.readString(store.payload(m.id(), Wars.WEB_XML))).contains("<!-- hotfix -->");
    assertThat(Files.exists(store.payload(m.id(), Wars.SCRIPT))).isFalse();
    assertThat(m.deletes(Packages.LIB + "bar-0.9.jar")).isTrue();
    assertThat(m.deletes(Packages.LIB + "foo-1.0.0.jar")).isTrue();
    assertThat(m.deletes(Packages.LIB + "food-1.0.jar")).isFalse();
    assertThat(m.deletes("other/" + Packages.LIB + "foo-1.0.0.jar")).isFalse();
    // from the same package again: the baseline that is there stays
    assertThat(store.addHotfix(zip, contents, "jasperserver-pro").createdAt())
        .isEqualTo(m.createdAt());
  }

  @Test
  void should_read_both_areas_of_a_distribution_zip_with_the_war_inside() throws Exception {
    Path war = Wars.war(tmp.resolve("build/jasperserver-pro.war"), Wars.vendor());
    String top = "jasperreports-server-pro-10.0.0-bin/";
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put(top + "docs/readme.html", "<p>docs</p>".getBytes(StandardCharsets.UTF_8));
    entries.put(top + "jasperserver-pro.war", Files.readAllBytes(war));
    entries.put(
        top + "buildomatic/default_master.properties.sample",
        "appServerType=tomcat\n".getBytes(StandardCharsets.UTF_8));
    entries.put(
        top + "buildomatic/conf_source/db/postgresql/db.template.properties",
        "db.port=5432\n".getBytes(StandardCharsets.UTF_8));
    entries.put(top + "samples/readme.txt", "samples\n".getBytes(StandardCharsets.UTF_8));
    Path zip = Packages.zip(tmp.resolve("dl/jasperreports-server-pro-10.0.0-bin.zip"), entries);

    BaselineStore store = store();
    BaselineManifest m = store.addRelease(zip);

    assertThat(m.id()).isEqualTo(Wars.RELEASE_ID);
    assertThat(m.files()).hasSize(Wars.vendor().size());
    assertThat(m.files(Area.INSTALLATION))
        .extracting(BaseFile::path)
        .containsExactlyInAnyOrder(
            "buildomatic/default_master.properties.sample",
            "buildomatic/conf_source/db/postgresql/db.template.properties",
            "samples/readme.txt");
    assertThat(
            store.payload(
                m.id(),
                Area.INSTALLATION,
                "buildomatic/conf_source/db/postgresql/db.template.properties"))
        .hasContent("db.port=5432\n");
    // the webapp's payload is where it always was
    assertThat(store.payload(m.id(), Wars.WEB_XML)).exists();
  }

  @Test
  void should_import_another_homes_baselines_once_and_leave_both_homes_readable() throws Exception {
    BaselineStore server = store();
    BaselineManifest release =
        server.addRelease(Wars.war(tmp.resolve("dl/jasperserver-pro.war"), Wars.vendor()));
    BaselineStore war = new BaselineStore(new Home(tmp.resolve("war-home")), Clock.systemUTC());

    assertThat(war.importFrom(server)).containsExactly(release.id());
    assertThat(war.list()).extracting(BaselineManifest::id).containsExactly(release.id());
    assertThat(war.payload(release.id(), Wars.WEB_XML))
        .hasSameTextualContentAs(server.payload(release.id(), Wars.WEB_XML));
    // a second import copies nothing, and the server's home is as it was
    assertThat(war.importFrom(server)).isEmpty();
    assertThat(server.list()).extracting(BaselineManifest::id).containsExactly(release.id());
    // importing a home into itself copies nothing
    assertThat(server.importFrom(store())).isEmpty();
    try (Stream<Path> left = Files.list(tmp.resolve("war-home/baselines"))) {
      assertThat(left.map(p -> p.getFileName().toString())).containsExactly(release.id());
    }
  }

  @Test
  void should_refuse_a_name_that_would_escape_the_baseline_and_keep_nothing() throws Exception {
    // a WAR with an entry that climbs out of the baseline being built
    Map<String, byte[]> war = new LinkedHashMap<>();
    Wars.vendor().forEach((k, v) -> war.put(k, v.getBytes(StandardCharsets.ISO_8859_1)));
    war.put("../escaped.xml", "<x/>".getBytes(StandardCharsets.UTF_8));
    Path climbing = Packages.zip(tmp.resolve("dl/jasperserver-pro.war"), war);
    assertThatThrownBy(() -> store().addRelease(climbing))
        .isInstanceOfSatisfying(
            HotfixException.class, e -> assertThat(e.kind()).isEqualTo(HotfixException.UNSUPPORTED))
        .hasMessageContaining("unusable path");

    // a distribution whose buildomatic entry climbs out of the installation's payload
    String top = "jasperreports-server-pro-10.0.0-bin/";
    Map<String, byte[]> dist = new LinkedHashMap<>();
    dist.put(
        top + "jasperserver-pro.war",
        Files.readAllBytes(Wars.war(tmp.resolve("build/jasperserver-pro.war"), Wars.vendor())));
    dist.put(
        top + "buildomatic/../../escaped.properties", "a=1\n".getBytes(StandardCharsets.UTF_8));
    Path escaping = Packages.zip(tmp.resolve("dl/escaping-bin.zip"), dist);
    assertThatThrownBy(() -> store().addRelease(escaping))
        .isInstanceOfSatisfying(
            HotfixException.class, e -> assertThat(e.kind()).isEqualTo(HotfixException.UNSUPPORTED))
        .hasMessageContaining("unusable path");

    assertThat(store().list()).isEmpty();
    try (var walk = Files.walk(tmp)) {
      assertThat(walk.map(p -> p.getFileName().toString()))
          .doesNotContain("escaped.xml", "escaped.properties");
    }
  }

  @Test
  void should_read_a_baseline_written_before_07_as_one_without_an_installation() throws Exception {
    BaselineStore store = store();
    BaselineManifest m =
        store.addRelease(Wars.war(tmp.resolve("dl/jasperserver-pro.war"), Wars.vendor()));
    Path manifest = tmp.resolve("home/baselines").resolve(m.id()).resolve("manifest.json");
    String old =
        Files.readString(manifest, StandardCharsets.UTF_8)
            .replace(",\"installFiles\":[]", "")
            .replace(",\"installDeleted\":[]", "");
    assertThat(old).doesNotContain("installFiles").doesNotContain("installDeleted");
    Files.writeString(manifest, old, StandardCharsets.UTF_8);

    BaselineManifest read = store.find(m.id()).orElseThrow();

    assertThat(read.files(Area.INSTALLATION)).isEmpty();
    assertThat(read.files()).hasSize(m.files().size());
  }

  @Test
  void should_remove_a_baseline_and_say_when_there_is_none() throws Exception {
    BaselineStore store = store();
    store.addRelease(Wars.war(tmp.resolve("dl/jasperserver-pro.war"), Wars.vendor()));
    assertThat(store.remove("no-such")).isFalse();
    assertThat(store.remove("../" + Wars.RELEASE_ID)).isFalse();
    assertThat(store.remove(Wars.RELEASE_ID)).isTrue();
    assertThat(store.list()).isEmpty();
  }
}

package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.platform.DefaultFileOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageStagerTest {

  @TempDir Path tmp;

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void should_refuse_a_destination_outside_the_staging_root() throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", bytes(Packages.OUTER_README));
    outer.put("jasperserver-pro/WEB-INF/lib/x.jar", bytes("payload"));
    Path zip = Packages.zip(tmp.resolve("escape.zip"), outer);
    PackageContents contents =
        new PackageContents(
            "id",
            "10.0.0",
            "PRO",
            "20260730_0457",
            "title",
            "sha",
            List.of(
                new PackageContents.Entry(
                    "../escape.jar",
                    Action.ADD,
                    Optional.of("x"),
                    Optional.empty(),
                    "jasperserver-pro/WEB-INF/lib/x.jar")),
            List.of());
    Path staging = tmp.resolve("staging");

    assertThatThrownBy(
            () ->
                PackageStager.stage(
                    zip,
                    contents,
                    Set.of("../escape.jar"),
                    staging,
                    staging::resolve,
                    new CancellationToken()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("refusing to stage outside");
    assertThat(tmp.resolve("escape.jar")).doesNotExist();
  }

  @Test
  void should_stage_only_the_wanted_files_when_the_webapp_ships_unpacked() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", bytes(Packages.OUTER_README));
    outer.put("jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar", bytes("patched foo"));
    outer.put("jasperserver-pro/WEB-INF/lib/new-1.0.jar", bytes("brand new"));
    Path zip = Packages.zip(tmp.resolve("tree.zip"), outer);
    PackageContents contents =
        OfficialPackage.read(zip, paths, "jasperserver-pro", new DefaultFileOps());
    Path staging = tmp.resolve("staging");

    PackageStager.stage(
        zip,
        contents,
        Set.of("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar"),
        staging,
        staging::resolve,
        new CancellationToken());

    assertThat(staging.resolve("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar"))
        .hasContent("brand new");
    assertThat(staging.resolve("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))
        .doesNotExist();
  }

  @Test
  void should_find_an_inner_archive_whose_outer_name_uses_backslashes() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("hotfix\\readme.txt", bytes(Packages.OUTER_README));
    outer.put(
        "hotfix\\jasperserver-pro.zip",
        Packages.zipBytes(Map.of(Packages.LIB + "foo-1.2.3.jar", "patched foo"), null));
    Path zip = Packages.zip(tmp.resolve("windows.zip"), outer);
    PackageContents contents =
        OfficialPackage.read(zip, paths, "jasperserver-pro", new DefaultFileOps());
    Path staging = tmp.resolve("staging");

    PackageStager.stage(
        zip,
        contents,
        Set.of("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"),
        staging,
        staging::resolve,
        new CancellationToken());

    assertThat(staging.resolve("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))
        .hasContent("patched foo");
  }
}

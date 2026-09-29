package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialPackageTest {
  @TempDir Path tmp;
  // use the same FileOps construction PlatformDetectionTest uses
  final com.jaspersoft.jrshotfix.platform.FileOps files =
      com.jaspersoft.jrshotfix.platform.PlatformDetectionTest.files();

  @Test
  void should_derive_id_add_replace_and_delete_when_read_against_this_installation()
      throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    PackageContents c =
        OfficialPackage.read(
            Packages.standard(tmp.resolve("dl/hotfix.zip")), paths, "jasperserver-pro", files);
    assertThat(c.id()).isEqualTo("JRSHF-10.0.0-20260730-0457");
    assertThat(c.release()).isEqualTo("10.0.0");
    assertThat(c.edition()).isEqualTo("PRO");
    assertThat(c.replaces())
        .extracting(PackageContents.Entry::path)
        .containsExactlyInAnyOrder(
            "webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar", "buildomatic/lib/tool-2.0.jar");
    assertThat(c.adds())
        .extracting(PackageContents.Entry::path)
        .containsExactly("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar");
    assertThat(c.deletes())
        .extracting(PackageContents.Entry::path)
        .containsExactlyInAnyOrder(
            "webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar",
            "webapps/jasperserver-pro/WEB-INF/lib/foo-1.0.0.jar");
    assertThat(c.replaces().get(0).sha256()).isPresent();
    assertThat(c.sha256()).hasSize(64);
    assertThat(c.sha256()).isEqualTo(files.sha256(tmp.resolve("dl/hotfix.zip")));
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("Additional Notes"));
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("left by an earlier hotfix"));
    // the readme's own lines, verbatim, after the summary sentence of their section
    assertThat(c.notes())
        .contains(
            "For PostgreSQL run the SQL in js-install/sql/postgresql.sql",
            "If an earlier hotfix is installed delete");
    assertThat(c.notes().indexOf("For PostgreSQL run the SQL in js-install/sql/postgresql.sql"))
        .isGreaterThan(indexContaining(c.notes(), "Additional Notes"));
  }

  private static int indexContaining(java.util.List<String> notes, String text) {
    for (int i = 0; i < notes.size(); i++) {
      if (notes.get(i).contains(text)) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void should_skip_a_listed_deletion_when_the_file_is_not_installed() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    PackageContents c =
        OfficialPackage.read(
            Packages.standard(tmp.resolve("dl/hotfix.zip")), paths, "jasperserver-pro", files);
    assertThat(c.deletes())
        .extracting(PackageContents.Entry::path)
        .doesNotContain("webapps/jasperserver-pro/WEB-INF/lib/never-installed-1.0.jar");
  }

  @Test
  void should_recognise_names_with_a_version_suffix_and_upper_case_readme_when_shaped()
      throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("hotfix/README.TXT", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put(
        "hotfix/jasperserver-pro-10.0.0-hotfix.zip",
        Packages.zipBytes(Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), null));
    Path zip = Packages.zip(tmp.resolve("dl/odd.zip"), outer);
    assertThat(OfficialPackage.looksOfficial(zip)).isTrue();
    assertThat(OfficialPackage.describe(zip).id()).isEqualTo("JRSHF-10.0.0-20260730-0457");
  }

  @Test
  void should_refuse_a_zip_that_is_not_a_package_when_read() throws Exception {
    Path zip = Packages.zip(tmp.resolve("dl/other.zip"), Map.of("a.txt", new byte[] {1}));
    assertThat(OfficialPackage.looksOfficial(zip)).isFalse();
    assertThatThrownBy(() -> OfficialPackage.describe(zip))
        .isInstanceOf(HotfixException.class)
        .hasMessageContaining("readme.txt");
  }

  @Test
  void should_refuse_a_readme_without_a_build_when_read() throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", "Release version: 10.0.0\n".getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        Packages.zipBytes(Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), null));
    assertThatThrownBy(
            () -> OfficialPackage.describe(Packages.zip(tmp.resolve("dl/nobuild.zip"), outer)))
        .isInstanceOf(HotfixException.class)
        .hasMessageContaining("build");
  }

  /** A package whose webapp archive holds {@code payload} and {@code innerReadme}. */
  private Path packageWith(String name, Map<String, String> payload, String innerReadme)
      throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(payload, innerReadme));
    return Packages.zip(tmp.resolve("dl/" + name), outer);
  }

  @Test
  void should_keep_at_most_60_readme_note_lines_when_the_section_is_longer() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    StringBuilder readme = new StringBuilder("Additional Notes:\n");
    for (int i = 1; i <= 70; i++) {
      readme.append("step ").append(i).append('\n');
    }
    PackageContents c =
        OfficialPackage.read(
            packageWith("long.zip", Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), readme.toString()),
            paths,
            "jasperserver-pro",
            files);
    assertThat(c.notes()).contains("step 1", "step 60").doesNotContain("step 61");
    assertThat(c.notes()).contains("… (see readme.txt for the rest)");
  }

  @Test
  void should_skip_a_readme_deletion_that_climbs_out_with_a_note() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    PackageContents c =
        OfficialPackage.read(
            packageWith(
                "climb.zip",
                Map.of(Packages.LIB + "foo-1.2.3.jar", "x"),
                "Deleted files:\n../x\nIMPORTANT\n../*.jar\n"),
            paths,
            "jasperserver-pro",
            files);
    assertThat(c.deletes()).isEmpty();
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("../x").contains("skipped"));
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("../*.jar").contains("skipped"));
  }

  @Test
  void should_refuse_as_unsupported_when_an_entry_name_holds_a_control_character()
      throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Path zip =
        packageWith("ctrl.zip", Map.of(Packages.LIB + "foo\n-1.2.3.jar", "x"), "Added files:\n");
    assertThatThrownBy(() -> OfficialPackage.read(zip, paths, "jasperserver-pro", files))
        .isInstanceOfSatisfying(
            HotfixException.class, e -> assertThat(e.kind()).isEqualTo(HotfixException.UNSUPPORTED))
        .hasMessageContaining("unusable path");
  }
}

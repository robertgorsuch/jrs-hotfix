package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
    assertThat(c.noteLines()).anySatisfy(n -> assertThat(n).contains("Additional Notes"));
    assertThat(c.noteLines()).anySatisfy(n -> assertThat(n).contains("left by an earlier hotfix"));
    // the readme's own lines, verbatim, after the summary sentence of their section
    assertThat(c.noteLines())
        .contains(
            "For PostgreSQL run the SQL in js-install/sql/postgresql.sql",
            "If an earlier hotfix is installed delete");
    assertThat(c.noteLines().indexOf("For PostgreSQL run the SQL in js-install/sql/postgresql.sql"))
        .isGreaterThan(indexContaining(c.noteLines(), "Additional Notes"));
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
  void should_keep_every_line_of_a_note_section_when_it_is_long() throws Exception {
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
    assertThat(c.noteLines()).contains("step 1", "step 60", "step 61", "step 70");
    assertThat(c.noteLines())
        .noneSatisfy(n -> assertThat(n).contains("see readme.txt for the rest"));
  }

  @Test
  void should_keep_repeated_lines_when_the_sql_of_a_note_section_repeats_them() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    String readme =
        """
        Additional Notes:
        Details for fix JS-72244:
        ALTER TABLE JIReportJob
        ADD last_error_new nvarchar(2000);
        ALTER TABLE JIReportJob
        DROP COLUMN last_error;
        """;
    PackageContents c =
        OfficialPackage.read(
            packageWith("sql.zip", Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), readme),
            paths,
            "jasperserver-pro",
            files);
    assertThat(c.noteLines())
        .containsSubsequence(
            "Details for fix JS-72244:",
            "ALTER TABLE JIReportJob",
            "ADD last_error_new nvarchar(2000);",
            "ALTER TABLE JIReportJob",
            "DROP COLUMN last_error;");
  }

  @Test
  void should_keep_indentation_and_inner_blank_lines_when_a_note_section_has_them()
      throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    String readme =
        "Additional Notes:\n\nDetails for fix A:\n   UPDATE T\n      SET a = 1;\n\nDetails for"
            + " fix B:\n\n==========\n";
    PackageContents c =
        OfficialPackage.read(
            packageWith("indent.zip", Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), readme),
            paths,
            "jasperserver-pro",
            files);
    assertThat(c.noteLines())
        .containsSubsequence(
            "Details for fix A:", "   UPDATE T", "      SET a = 1;", "", "Details for fix B:");
    // the blank lines after the section's last line are not part of it
    List<String> quoted =
        c.notes().stream()
            .filter(PackageContents.Note::quoted)
            .map(PackageContents.Note::text)
            .toList();
    assertThat(quoted.get(quoted.size() - 1)).isEqualTo("Details for fix B:");
  }

  @Test
  void should_carry_a_note_section_once_when_both_inner_readmes_hold_it() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    String notes = "Additional Notes:\nDetails for fix A:\nUPDATE T\nSET a = 1;\nUPDATE T\n";
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        Packages.zipBytes(Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), notes));
    // the same section, with a blank line the other readme does not have
    outer.put(
        "js-install.zip",
        Packages.zipBytes(
            Map.of("buildomatic/lib/tool-2.0.jar", "y"),
            notes.replace("Details for fix A:\n", "Details for fix A:\n\n")));
    PackageContents c =
        OfficialPackage.read(
            Packages.zip(tmp.resolve("dl/twice.zip"), outer), paths, "jasperserver-pro", files);
    assertThat(c.noteLines()).filteredOn("Details for fix A:"::equals).hasSize(1);
    assertThat(c.noteLines()).filteredOn("UPDATE T"::equals).hasSize(2);
    assertThat(c.noteLines()).filteredOn(n -> n.contains("Additional Notes")).hasSize(1);
  }

  @Test
  void should_name_the_webapp_files_and_count_the_templates_when_settings_files_are_replaced()
      throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Path webapp = paths.tomcatDir().resolve("webapps/jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF/classes"));
    Files.writeString(webapp.resolve("WEB-INF/web.xml"), "<web-app/>");
    Files.writeString(webapp.resolve("WEB-INF/classes/jasperreports.properties"), "a=1");
    Map<String, String> templates = new LinkedHashMap<>();
    for (int i = 0; i < 9; i++) {
      String path = "buildomatic/conf_source/iePro/applicationContext-" + i + ".xml";
      Files.createDirectories(paths.installDir().resolve(path).getParent());
      Files.writeString(paths.installDir().resolve(path), "<beans/>");
      templates.put(path, "<beans id='new'/>");
    }
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put(
        "jasperserver-pro.zip",
        Packages.zipBytes(
            Map.of(
                "WEB-INF/web.xml", "<web-app id='new'/>",
                "WEB-INF/classes/jasperreports.properties", "a=2"),
            null));
    outer.put("js-install.zip", Packages.zipBytes(templates, null));
    PackageContents c =
        OfficialPackage.read(
            Packages.zip(tmp.resolve("dl/settings.zip"), outer), paths, "jasperserver-pro", files);
    assertThat(c.noteLines())
        .filteredOn(n -> n.contains("are overwritten"))
        .singleElement()
        .satisfies(
            n ->
                assertThat(n)
                    .contains("webapps/jasperserver-pro/WEB-INF/web.xml")
                    .contains("webapps/jasperserver-pro/WEB-INF/classes/jasperreports.properties")
                    .contains("9 configuration templates under buildomatic")
                    .doesNotContain("applicationContext-0.xml"));
  }

  static final String QUARTZ = "webapps/jasperserver-pro/WEB-INF/js.quartz.properties";

  /** A package whose webapp archive ships {@code WEB-INF/js.quartz.properties} and a jar. */
  private PackageContents readWithQuartz(String mine, String theirs) throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Files.writeString(paths.resolve(QUARTZ), mine, StandardCharsets.ISO_8859_1);
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(Packages.LIB + "foo-1.2.3.jar", "x");
    payload.put("WEB-INF/js.quartz.properties", theirs);
    return OfficialPackage.read(
        packageWith("quartz.zip", payload, null), paths, "jasperserver-pro", files);
  }

  private static String sha256(String text) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.ISO_8859_1)));
  }

  @Test
  void should_plan_the_merged_file_when_an_installer_written_file_holds_this_servers_values()
      throws Exception {
    String theirs = "# scheduler\r\na=1\r\nuri=http://localhost:8080/x\r\nfresh=true\r\n";
    PackageContents c =
        readWithQuartz("a=1\nuri=http://reports:8081/x\nmail.host=smtp.example.org\n", theirs);
    PackageContents.Entry e =
        c.replaces().stream().filter(r -> r.path().equals(QUARTZ)).findFirst().orElseThrow();
    // the package's layout and line ends, this server's values
    assertThat(e.sha256())
        .contains(
            sha256(
                "# scheduler\r\na=1\r\nuri=http://reports:8081/x\r\nfresh=true\r\n\r\n"
                    + PropertiesMerge.CARRIED_HEADING
                    + "\r\nmail.host=smtp.example.org\r\n"));
    assertThat(e.packageSha256()).contains(sha256(theirs));
    assertThat(c.noteLines())
        .filteredOn(n -> n.contains("js.quartz.properties"))
        .singleElement()
        .satisfies(
            n ->
                assertThat(n)
                    .contains("merged")
                    .contains("uri")
                    .contains("mail.host")
                    // key names only: a value may be a password
                    .doesNotContain("reports:8081")
                    .doesNotContain("smtp.example.org"));
    assertThat(c.noteLines()).noneSatisfy(n -> assertThat(n).contains("are overwritten"));
  }

  @Test
  void should_replace_plainly_when_the_installer_written_file_has_the_packages_values()
      throws Exception {
    String theirs = "# scheduler\na=1\nuri=http://localhost:8080/x\n";
    PackageContents c = readWithQuartz("uri=http://localhost:8080/x\na=1\n", theirs);
    PackageContents.Entry e =
        c.replaces().stream().filter(r -> r.path().equals(QUARTZ)).findFirst().orElseThrow();
    assertThat(e.sha256()).contains(sha256(theirs));
    assertThat(e.packageSha256()).isEmpty();
    assertThat(c.noteLines()).noneSatisfy(n -> assertThat(n).contains("merged"));
  }

  @Test
  void should_add_plainly_when_the_installer_written_file_is_not_on_this_server() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("WEB-INF/js.quartz.properties", "a=1\n");
    PackageContents c =
        OfficialPackage.read(
            packageWith("fresh.zip", payload, null), paths, "jasperserver-pro", files);
    assertThat(c.adds()).singleElement().satisfies(e -> assertThat(e.packageSha256()).isEmpty());
  }

  private static final String CONTEXT = "webapps/jasperserver-pro/META-INF/context.xml";

  private PackageContents readWithContext(String mine, String theirs) throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Path context = paths.resolve(CONTEXT);
    Files.createDirectories(context.getParent());
    Files.writeString(context, mine, StandardCharsets.ISO_8859_1);
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("META-INF/context.xml", theirs);
    payload.put(Packages.LIB + "foo-1.2.3.jar", "x");
    return OfficialPackage.read(
        packageWith("context.zip", payload, "Deleted files:\nMETA-INF/context.xml\n"),
        paths,
        "jasperserver-pro",
        files);
  }

  @Test
  void should_keep_the_servers_context_and_show_the_packages_copy_when_the_package_ships_one()
      throws Exception {
    String theirs = "<Context>\n  <Resource username=\"@@BITROCK_DB_USER@@\"/>\n</Context>\n";
    PackageContents c =
        readWithContext("<Context><Resource username=\"jasperdb\"/></Context>\n", theirs);
    // no entry: nothing snapshots, stages, swaps or deletes it
    assertThat(c.entries()).extracting(PackageContents.Entry::path).doesNotContain(CONTEXT);
    assertThat(c.kept())
        .singleElement()
        .satisfies(
            k -> {
              assertThat(k.path()).isEqualTo(CONTEXT);
              assertThat(k.vendorSha256()).isEqualTo(sha256(theirs));
            });
    assertThat(c.noteLines())
        .anySatisfy(n -> assertThat(n).contains(CONTEXT).contains("is not replaced"))
        .containsSubsequence(
            "<Context>", "  <Resource username=\"@@BITROCK_DB_USER@@\"/>", "</Context>");
    assertThat(c.noteLines()).noneSatisfy(n -> assertThat(n).contains("are overwritten"));
  }

  @Test
  void should_say_nothing_about_the_context_when_the_package_ships_the_servers_copy()
      throws Exception {
    String same = "<Context/>\n";
    PackageContents c = readWithContext(same, same);
    assertThat(c.kept()).extracting(PackageContents.Kept::path).containsExactly(CONTEXT);
    assertThat(c.noteLines()).noneSatisfy(n -> assertThat(n).contains("context.xml"));
  }

  @Test
  void should_add_the_context_when_the_server_has_none() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    PackageContents c =
        OfficialPackage.read(
            packageWith("fresh-context.zip", Map.of("META-INF/context.xml", "<Context/>"), null),
            paths,
            "jasperserver-pro",
            files);
    assertThat(c.adds()).extracting(PackageContents.Entry::path).containsExactly(CONTEXT);
    assertThat(c.kept()).isEmpty();
  }

  @Test
  void should_report_and_not_delete_an_older_version_of_a_library_the_package_brings()
      throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Path lib = paths.resolve("webapps/jasperserver-pro/" + Packages.LIB + "x").getParent();
    Files.writeString(lib.resolve("widget-2.0.1.jar"), "old widget");
    Files.writeString(lib.resolve("widget-extras-2.0.1.jar"), "another artifact");
    Files.writeString(lib.resolve("widget-3.0.0.jar"), "a newer one than the package's");
    PackageContents c =
        OfficialPackage.read(
            packageWith("widget.zip", Map.of(Packages.LIB + "widget-2.1.0.jar", "new"), null),
            paths,
            "jasperserver-pro",
            files);
    assertThat(c.deletes()).isEmpty();
    assertThat(c.noteLines())
        .filteredOn(n -> n.contains("older version"))
        .singleElement()
        .satisfies(
            n ->
                assertThat(n)
                    .contains("widget-2.0.1.jar (the package brings widget-2.1.0.jar)")
                    .contains("nothing is deleted")
                    .doesNotContain("widget-extras")
                    .doesNotContain("widget-3.0.0"));
  }

  @Test
  void should_not_report_a_library_the_readme_already_deletes() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    // the standard package replaces foo-1.2.3.jar and its readme's glob deletes foo-1.0.0.jar
    PackageContents c =
        OfficialPackage.read(
            Packages.standard(tmp.resolve("dl/hotfix.zip")), paths, "jasperserver-pro", files);
    assertThat(c.noteLines()).noneSatisfy(n -> assertThat(n).contains("older version"));
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
    assertThat(c.noteLines()).anySatisfy(n -> assertThat(n).contains("../x").contains("skipped"));
    assertThat(c.noteLines())
        .anySatisfy(n -> assertThat(n).contains("../*.jar").contains("skipped"));
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

  /**
   * The readme deletes {@code FOO-1.2.3.jar} and the payload lays down {@code foo-1.2.3.jar}; both
   * exist on disk as far as the file system can tell.
   */
  private PackageContents caseClash() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    Path upper =
        paths.tomcatDir().resolve("webapps/jasperserver-pro/" + Packages.LIB + "FOO-1.2.3.jar");
    if (!Files.exists(upper)) {
      Files.writeString(upper, "upper foo");
    }
    return OfficialPackage.read(
        packageWith(
            "case.zip",
            Map.of(Packages.LIB + "foo-1.2.3.jar", "new foo"),
            "Deleted files:\nWEB-INF/lib/FOO-1.2.3.jar\n"),
        paths,
        "jasperserver-pro",
        files);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_not_delete_what_the_package_lays_down_when_only_the_case_differs_on_windows()
      throws Exception {
    PackageContents c = caseClash();
    assertThat(c.replaces())
        .extracting(PackageContents.Entry::path)
        .containsExactly("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar");
    assertThat(c.deletes()).isEmpty();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void should_keep_both_entries_when_only_the_case_differs_on_linux() throws Exception {
    PackageContents c = caseClash();
    assertThat(c.replaces())
        .extracting(PackageContents.Entry::path)
        .containsExactly("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar");
    assertThat(c.deletes())
        .extracting(PackageContents.Entry::path)
        .containsExactly("webapps/jasperserver-pro/WEB-INF/lib/FOO-1.2.3.jar");
  }
}

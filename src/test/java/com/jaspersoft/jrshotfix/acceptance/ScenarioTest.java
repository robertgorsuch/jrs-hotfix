package com.jaspersoft.jrshotfix.acceptance;

import static com.jaspersoft.jrshotfix.acceptance.Fixture.LIB;
import static com.jaspersoft.jrshotfix.acceptance.Fixture.STANDARD_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.pkg.Packages;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The spec's scenarios, each driven through the shaded jar against its own fake installation.
 * Invariants: every test asserts on the exit code and on the real files under the installation;
 * each test has its own temporary directory and its own stub server, so nothing is shared between
 * tests or forks. Scenario 5 (a killed run) lives in {@link CrashRecoveryTest}.
 */
class ScenarioTest {

  @TempDir Path tmp;

  @Test
  void s1_should_apply_a_package_when_the_install_is_clean() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Cli.Result r = f.cli.run("apply", f.pkg().toString(), "--yes").assertExit(0);

      assertThat(r.stdout())
          .contains("preflight")
          .contains("record-installed")
          .contains(STANDARD_ID);
      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
      assertThat(read(f, LIB + "new-1.0.jar")).isEqualTo("brand new");
      assertThat(f.target(LIB + "bar-0.9.jar")).doesNotExist();
      assertThat(f.target(LIB + "foo-1.0.0.jar")).doesNotExist();
      assertThat(read(f, "buildomatic/lib/tool-2.0.jar")).isEqualTo("patched tool");
      assertThat(read(f, LIB + "jasperserver-api-10.0.0.jar")).isEqualTo("api");
      assertThat(f.home.resolve("runs"))
          .isDirectoryContaining(p -> Files.exists(p.resolve("notes.txt")));
      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.listRow(STANDARD_ID)).contains("INSTALLED").contains("tool");
      assertThat(f.tomcatRunning()).as("service started again").isTrue();
    }
  }

  @Test
  void s2_should_apply_a_second_package_over_the_first_when_both_are_for_the_release()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      f.cli.run("apply", f.pkg().toString(), "--yes").assertExit(0);
      // the standard package's IMPORTANT glob removed the foo-1.*.jar an earlier hotfix left
      assertThat(f.target(LIB + "foo-1.0.0.jar")).doesNotExist();

      f.cli.run("apply", f.laterPkg().toString(), "--yes").assertExit(0);

      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("later foo");
      assertThat(read(f, LIB + "new-1.0.jar")).isEqualTo("brand new");
      assertThat(f.target(LIB + "foo-1.0.0.jar")).doesNotExist();
      assertThat(f.listRow(STANDARD_ID)).contains("INSTALLED");
      assertThat(f.listRow(Packages.laterId())).contains("INSTALLED");
    }
  }

  @Test
  void s3_should_restore_every_file_when_the_latest_hotfix_is_rolled_back() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      f.cli.run("apply", f.pkg().toString(), "--yes").assertExit(0);

      f.cli.run("rollback", STANDARD_ID, "--yes").assertExit(0);

      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("old foo");
      assertThat(f.target(LIB + "new-1.0.jar")).doesNotExist();
      assertThat(read(f, LIB + "bar-0.9.jar")).isEqualTo("bar");
      assertThat(read(f, LIB + "foo-1.0.0.jar")).isEqualTo("older foo left by an earlier hotfix");
      assertThat(read(f, "buildomatic/lib/tool-2.0.jar")).isEqualTo("old tool");
      assertThat(f.listRow(STANDARD_ID)).contains("ROLLED_BACK");
    }
  }

  @Test
  void s4_should_refuse_an_older_rollback_when_a_later_hotfix_owns_its_files_unless_cascade()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      f.cli.run("apply", f.pkg().toString(), "--yes").assertExit(0);
      f.cli.run("apply", f.laterPkg().toString(), "--yes").assertExit(0);

      Cli.Result refused = f.cli.run("rollback", STANDARD_ID, "--yes").assertExit(2);
      assertThat(refused.stderr()).contains(Packages.laterId());
      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("later foo");

      f.cli.run("rollback", STANDARD_ID, "--cascade", "--yes").assertExit(0);

      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("old foo");
      assertThat(f.target(LIB + "new-1.0.jar")).doesNotExist();
      assertThat(read(f, LIB + "bar-0.9.jar")).isEqualTo("bar");
      assertThat(read(f, "buildomatic/lib/tool-2.0.jar")).isEqualTo("old tool");
      assertThat(f.listRow(STANDARD_ID)).contains("ROLLED_BACK");
      assertThat(f.listRow(Packages.laterId())).contains("ROLLED_BACK");
    }
  }

  @Test
  void s6_should_record_a_hand_applied_package_and_refuse_to_roll_it_back_when_asked()
      throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      f.cli.run("record", f.pkg().toString(), "--yes").assertExit(0);

      assertThat(f.listRow(STANDARD_ID)).contains("INSTALLED").contains("recorded");
      Cli.Result refused = f.cli.run("rollback", STANDARD_ID, "--yes").assertExit(2);
      assertThat(refused.stdout() + refused.stderr()).contains("by hand");
      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("old foo");
      assertThat(f.listRow(STANDARD_ID)).contains("INSTALLED");
    }
  }

  @Test
  void s7_should_preview_and_verify_without_touching_anything_when_asked() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Map<Path, String> before = snapshot(f);

      f.cli.run("apply", f.pkg().toString(), "--plan").assertExit(0);
      assertThat(snapshot(f)).isEqualTo(before);
      assertThat(journals(f)).isEmpty();

      Cli.Result verify = f.cli.run("verify", f.pkg().toString()).assertExit(0);
      assertThat(verify.stdout())
          .contains(STANDARD_ID)
          .contains("add")
          .contains("new-1.0.jar")
          .contains("replace")
          .contains("foo-1.2.3.jar")
          .contains("delete")
          .contains("bar-0.9.jar");
      assertThat(snapshot(f)).isEqualTo(before);

      Path notAPackage =
          Packages.zip(
              tmp.resolve("packages/holiday-photos.zip"),
              Map.of("photo.txt", "not a hotfix".getBytes(StandardCharsets.UTF_8)));
      f.cli.run("verify", notAPackage.toString()).assertExit(6);
      f.cli.run("apply", notAPackage.toString(), "--yes").assertExit(6);
      assertThat(snapshot(f)).isEqualTo(before);
      assertThat(journals(f)).isEmpty();
    }
  }

  @Test
  void s8_should_report_exit_9_when_another_run_holds_the_lock() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Cli.Running paused = Crash.startAndPauseAt(f, "atomic-swap");
      try {
        f.cli.run("list").assertExit(0);
        Cli.Result blocked = f.cli.run("apply", f.laterPkg().toString(), "--yes").assertExit(9);
        assertThat(blocked.stdout() + blocked.stderr()).containsIgnoringCase("lock");
      } finally {
        Crash.kill(paused);
      }
      String runId = f.pendingRunId();

      f.cli.run("runs", "rollback", runId, "--yes").assertExit(0);

      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("old foo");
      assertThat(read(f, LIB + "bar-0.9.jar")).isEqualTo("bar");
      assertThat(f.target(LIB + "new-1.0.jar")).doesNotExist();
      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.tomcatRunning()).as("service started by the rollback").isTrue();
    }
  }

  @Test
  void s9_should_print_the_manual_notes_when_planning_and_after_the_run() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Cli.Result plan = f.cli.run("apply", f.pkg().toString(), "--plan").assertExit(0);
      assertThat(plan.stdout())
          .contains("Additional Notes")
          .contains("For PostgreSQL run the SQL in js-install/sql/postgresql.sql");

      Cli.Result run = f.cli.run("apply", f.pkg().toString(), "--yes").assertExit(0);
      assertThat(run.stdout())
          .contains("Additional Notes")
          .contains("Manual steps from the package readme")
          .contains("For PostgreSQL run the SQL in js-install/sql/postgresql.sql")
          .contains("notes.txt");

      List<Path> notes = runFiles(f, "notes.txt");
      assertThat(notes).hasSize(1);
      assertThat(Files.readString(notes.get(0), StandardCharsets.UTF_8))
          .contains("Additional Notes")
          .contains("For PostgreSQL run the SQL in js-install/sql/postgresql.sql");
    }
  }

  private static String read(Fixture f, String relative) throws Exception {
    return Files.readString(f.target(relative), StandardCharsets.UTF_8);
  }

  /** Every file of the installation and the SHA-256 of its bytes. */
  private static Map<Path, String> snapshot(Fixture f) throws Exception {
    Path install = f.installDir();
    Map<Path, String> state = new TreeMap<>();
    try (Stream<Path> files = Files.walk(install)) {
      for (Path p : files.filter(Files::isRegularFile).toList()) {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(p), sha)) {
          in.transferTo(OutputStream.nullOutputStream());
        }
        state.put(install.relativize(p), HexFormat.of().formatHex(sha.digest()));
      }
    }
    return state;
  }

  private static List<Path> journals(Fixture f) throws Exception {
    return runFiles(f, "journal.jsonl");
  }

  private static List<Path> runFiles(Fixture f, String name) throws Exception {
    Path runs = f.home.resolve("runs");
    if (!Files.isDirectory(runs)) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(runs)) {
      return dirs.map(d -> d.resolve(name)).filter(Files::exists).toList();
    }
  }
}

package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrshotfix.engine.RunLock;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.LastHome;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.hotfix.HotfixFixture;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.state.FileJournal;
import com.jaspersoft.jrshotfix.state.RunPlans;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The commands end to end, in process: {@link Main#commandLine} over a fixture installation whose
 * platform is the hotfix tests' fake (real files, a recording service controller) and whose wait
 * probe answers from a stub HTTP server.
 */
class CommandsTest {

  @TempDir Path tmp;

  private final List<Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void stopServers() {
    fixtures.forEach(Fixture::close);
  }

  @Test
  void should_apply_verify_list_and_roll_back_through_the_commands() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("verify", f.pkg.toString())).isEqualTo(0);
    assertThat(f.out()).contains("JRSHF-10.0.0-20260730-0457").contains("replace");
    assertThat(f.run("apply", f.pkg.toString(), "--plan")).isEqualTo(0);
    assertThat(f.out()).contains("preflight").contains("atomic-swap");
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
        .isEqualTo("old foo");
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
        .isEqualTo("patched foo");
    assertThat(f.run("list")).isEqualTo(0);
    assertThat(f.out()).contains("can be undone:  JRSHF-10.0.0-20260730-0457");
    // the apply's snapshot is the undo now, not a leftover of its run
    assertThat(f.home.undo().resolve("undo.json")).isRegularFile();
    try (var runs = Files.list(f.home.runs())) {
      assertThat(runs.filter(r -> Files.exists(r.resolve("snapshot")))).isEmpty();
    }
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(2);
    assertThat(f.run("rollback", "--yes")).isEqualTo(0);
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
        .isEqualTo("old foo");
    // used up, and the rollback kept neither its own snapshot nor the undo it used
    assertThat(f.home.undo()).doesNotExist();
    try (var runs = Files.list(f.home.runs())) {
      assertThat(runs.flatMap(CommandsTest::children).map(p -> p.getFileName().toString()))
          .noneMatch(n -> n.equals("undone") || n.startsWith("pre-rollback-"));
    }
    assertThat(f.run("list")).isEqualTo(0);
    assertThat(f.out()).contains("can be undone:  nothing");
    assertThat(f.run("rollback", "--yes")).isEqualTo(2);
    assertThat(f.err()).contains("nothing to undo");
  }

  private static void copyTree(Path from, Path to) throws IOException {
    try (java.util.stream.Stream<Path> all = Files.walk(from)) {
      for (Path p : all.toList()) {
        Path target = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target);
        }
      }
    }
  }

  private static java.util.stream.Stream<Path> children(Path dir) {
    try (java.util.stream.Stream<Path> listed = Files.list(dir)) {
      return listed.toList().stream();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void should_apply_again_after_a_redeploy_with_nothing_to_forget() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    // the operator redeploys the webapp from the WAR: the files are the old ones again
    Files.writeString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"), "old foo");
    // the earlier undo would put back files the server no longer has: refused, naming them
    assertThat(f.run("rollback", "--yes")).isEqualTo(2);
    assertThat(f.out()).contains("changed since").contains("foo-1.2.3.jar");

    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
        .isEqualTo("patched foo");
    // the new apply is the one to undo
    assertThat(f.run("rollback", "--yes")).isEqualTo(0);
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
        .isEqualTo("old foo");
  }

  @Test
  void should_print_the_readme_notes_in_the_plan_preview_when_planning() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--plan")).isEqualTo(0);
    assertThat(f.out()).contains("Additional Notes");
    assertThat(f.out()).contains("For PostgreSQL run the SQL in js-install/sql/postgresql.sql");
  }

  @Test
  void should_save_and_reprint_the_readme_notes_when_a_run_ends() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    assertThat(f.out())
        .contains("Manual steps from the package readme")
        .contains("For PostgreSQL run the SQL in js-install/sql/postgresql.sql");
    assertThat(f.run("runs", "list")).isEqualTo(0);
    String row = f.out().lines().filter(l -> l.contains("hotfix.apply")).findFirst().orElseThrow();
    String runId = row.substring(0, row.indexOf(' '));
    assertThat(Files.readString(f.home.notesFile(runId), StandardCharsets.UTF_8))
        .contains("Additional Notes")
        .contains("For PostgreSQL run the SQL in js-install/sql/postgresql.sql");
  }

  @Test
  void should_not_reprint_the_readme_notes_when_the_run_rolls_back() throws Exception {
    Fixture f = fixture();
    // NEW is an added file with nothing to restore, so the swap fails but the rollback that
    // follows (restoring the other, snapshotted files) is clean: exit 3, not 4.
    f.hf.platform.failAtomicReplaceFor(f.target(HotfixFixture.NEW));
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(3);
    assertThat(f.out()).doesNotContain("Manual steps from the package readme");
  }

  @Test
  void should_write_the_run_log_with_the_checksum_audit_when_applying() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    assertThat(f.run("runs", "list")).isEqualTo(0);
    String row = f.out().lines().filter(l -> l.contains("hotfix.apply")).findFirst().orElseThrow();
    String runId = row.substring(0, row.indexOf(' '));
    String log = Files.readString(f.home.logFile(runId), StandardCharsets.UTF_8);
    assertThat(log)
        .contains(HotfixPlans.AUDIT_CHECKSUM_CONFIRMED + " skipped with --yes")
        .contains("StepSucceeded [atomic-swap]");
    assertThat(f.run("runs", "show", runId)).isEqualTo(0);
    assertThat(f.out()).contains("SUCCEEDED").contains("promote-undo");
  }

  @Test
  void should_exit_2_without_touching_anything_when_confirmation_is_missing_non_interactively()
      throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString())).isEqualTo(2);
    assertThat(f.err()).contains("--yes");
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar")))
        .isEqualTo("old foo");
    assertThat(new FileJournal(f.home, Clock.systemUTC()).runs()).isEmpty();
  }

  @Test
  void should_exit_9_when_another_process_holds_the_lock() throws Exception {
    Fixture f = fixture();
    try (RunLock held = new RunLock(f.home, "other", Instant.now())) {
      assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(9);
      assertThat(f.err()).contains("other");
    }
  }

  @Test
  void should_exit_8_until_a_pending_run_is_recovered() throws Exception {
    Fixture f = fixture();
    new FileJournal(f.home, Clock.systemUTC())
        .recordRunStart("stuck", "hotfix.apply", Optional.of("p"), Instant.now());
    new RunPlans(f.home)
        .store(
            "stuck",
            f.plans().planApply(new HotfixPlans.ApplyArgs(f.pkg, true)),
            "hotfix.apply",
            HotfixPlans.applyArgsJson(new HotfixPlans.ApplyArgs(f.pkg, true)));
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(8);
    assertThat(f.err()).contains("runs resume stuck").contains("runs rollback stuck");
    assertThat(f.run("runs", "rollback", "stuck", "--yes")).isEqualTo(0);
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
  }

  @Test
  void should_resume_a_rollback_run_that_ended_after_its_last_step_when_the_undo_is_used_up()
      throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    Path usedUp = tmp.resolve("used-up-undo");
    copyTree(f.home.undo(), usedUp);
    assertThat(f.run("rollback", "--yes")).isEqualTo(0);
    assertThat(f.run("runs", "list")).isEqualTo(0);
    String row =
        f.out().lines().filter(l -> l.contains("hotfix.rollback")).findFirst().orElseThrow();
    String runId = row.substring(0, row.indexOf(' '));
    // the process died after the last step: every step SUCCEEDED, the undo it used up is still in
    // the run (it goes when the run ends), and no run end was recorded
    copyTree(usedUp, f.home.runDir(runId).resolve("undone"));
    Path runJson = f.home.runDir(runId).resolve("run.json");
    ObjectNode run = (ObjectNode) Json.mapper().readTree(Files.readString(runJson));
    run.remove(List.of("endedAt", "terminalState", "exitCode"));
    Files.writeString(runJson, Json.write(run));
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(8);

    assertThat(f.run("runs", "resume", runId, "--yes")).isEqualTo(0);

    assertThat(new FileJournal(f.home, Clock.systemUTC()).run(runId).orElseThrow().pending())
        .isFalse();
    assertThat(f.run("runs", "list")).isEqualTo(0);
    assertThat(f.out()).doesNotContain("PENDING");
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
  }

  @Test
  void should_refuse_recovery_when_the_package_changed_since_the_run_started() throws Exception {
    Fixture f = fixture();
    new FileJournal(f.home, Clock.systemUTC())
        .recordRunStart("stuck", "hotfix.apply", Optional.of("p"), Instant.now());
    new RunPlans(f.home)
        .store(
            "stuck",
            f.plans().planApply(new HotfixPlans.ApplyArgs(f.pkg, true)),
            "hotfix.apply",
            HotfixPlans.applyArgsJson(new HotfixPlans.ApplyArgs(f.pkg, true)));
    Packages.later(f.pkg);

    assertThat(f.run("runs", "resume", "stuck", "--yes")).isEqualTo(2);
    assertThat(f.err()).contains("package");
  }

  /** The mutating commands outside the engine, each with the arguments that would otherwise run. */
  private static List<List<String>> gatedCommands() {
    return List.of(
        List.of("runs", "prune", "--older-than", "0", "--yes"),
        List.of("settings", "set", "service.stopTimeoutSeconds", "42"),
        List.of("settings", "detect", "--yes"));
  }

  @Test
  void should_exit_9_for_every_mutating_command_when_the_lock_is_held() throws Exception {
    Fixture f = fixture();
    try (RunLock held = new RunLock(f.home, "other", Instant.now())) {
      for (List<String> command : gatedCommands()) {
        assertThat(f.run(command.toArray(String[]::new))).as(command.toString()).isEqualTo(9);
      }
    }
    assertThat(f.hf.undo.read()).isEmpty();
    assertThat(SettingsStore.load(f.home).orElseThrow().stopTimeoutSeconds()).isNotEqualTo(42);
  }

  @Test
  void should_exit_8_for_every_mutating_command_while_a_run_is_pending() throws Exception {
    Fixture f = fixture();
    new FileJournal(f.home, Clock.systemUTC())
        .recordRunStart("stuck", "hotfix.apply", Optional.of("p"), Instant.now());
    for (List<String> command : gatedCommands()) {
      assertThat(f.run(command.toArray(String[]::new))).as(command.toString()).isEqualTo(8);
      assertThat(f.err()).contains("runs resume stuck");
    }
    assertThat(f.hf.undo.read()).isEmpty();
    assertThat(SettingsStore.load(f.home).orElseThrow().stopTimeoutSeconds()).isNotEqualTo(42);
  }

  @Test
  void should_prune_nothing_while_a_run_is_pending() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    List<String> before;
    try (var dirs = Files.list(f.home.runs())) {
      before = dirs.map(p -> p.getFileName().toString()).sorted().toList();
    }
    new FileJournal(f.home, Clock.systemUTC())
        .recordRunStart("stuck", "hotfix.apply", Optional.of("p"), Instant.now());

    assertThat(f.run("runs", "prune", "--older-than", "0", "--yes")).isEqualTo(8);

    try (var dirs = Files.list(f.home.runs())) {
      assertThat(dirs.map(p -> p.getFileName().toString()).sorted().toList())
          .containsAll(before)
          .contains("stuck");
    }
    assertThat(f.hf.undo.read()).isPresent();
  }

  @Test
  void should_show_and_set_settings_when_asked() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("settings", "set", "service.stopTimeoutSeconds", "42")).isEqualTo(0);
    assertThat(f.run("settings", "show")).isEqualTo(0);
    assertThat(f.out()).contains("service.stopTimeoutSeconds").contains("42");
    assertThat(f.run("settings", "set", "nope", "1")).isEqualTo(1);
  }

  @Test
  void should_exit_6_when_the_zip_is_not_a_package() throws Exception {
    Fixture f = fixture();
    Path other = Packages.zip(tmp.resolve("dl/other.zip"), Map.of("a.txt", new byte[] {1}));
    assertThat(f.run("apply", other.toString(), "--yes")).isEqualTo(6);
    assertThat(f.run("verify", other.toString())).isEqualTo(6);
    assertThat(f.err()).doesNotContain("\tat ");
  }

  @Test
  void should_exit_2_with_the_remediation_when_there_are_no_settings() throws Exception {
    Fixture f = fixture();
    Files.delete(f.home.settingsFile());
    assertThat(f.run("list")).isEqualTo(2);
    assertThat(f.err())
        .contains("no settings found")
        .contains("--home <installDir>/jrs-hotfix")
        .contains("JRS_HOTFIX_HOME")
        .contains("settings detect");
  }

  @Test
  void should_exit_2_naming_every_home_when_two_installations_hold_settings() throws Exception {
    Fixture f = fixture();
    Path other = tmp.resolve("other");
    SettingsStore.save(
        new Home(other.resolve("jrs-hotfix")),
        SettingsStore.load(f.home).orElseThrow().withKey("webappName", "jasperserver"));
    f.hf.platform.candidates.add(f.hf.paths.installDir());
    f.hf.platform.candidates.add(other);
    assertThat(f.runExactly(List.of("list", "--non-interactive"))).isEqualTo(2);
    assertThat(f.err())
        .contains(f.home.root().toString())
        .contains(new Home(other.resolve("jrs-hotfix")).root().toString())
        .contains("--home");
    assertThat(Files.exists(f.lastHomeFile())).isFalse();
  }

  @Test
  void should_name_the_home_first_when_the_plan_or_a_run_is_shown() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--plan")).isEqualTo(0);
    assertThat(f.out().lines().findFirst()).contains("home: " + f.home.root());
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    String runId = new FileJournal(f.home, Clock.systemUTC()).runs().get(0).runId();
    assertThat(f.run("runs", "show", runId)).isEqualTo(0);
    assertThat(f.out()).contains("home: " + f.home.root());
  }

  @Test
  void should_create_no_home_when_a_command_finds_no_settings() throws Exception {
    Fixture f = fixture();
    Path elsewhere = tmp.resolve("elsewhere/jrs-hotfix");
    Map<String, String> env = Map.of("JRS_HOTFIX_HOME", elsewhere.toString());
    assertThat(f.runExactly(env, List.of("list", "--non-interactive"))).isEqualTo(2);
    assertThat(f.runExactly(env, List.of("verify", f.pkg.toString(), "--non-interactive")))
        .isEqualTo(2);
    assertThat(f.runExactly(env, List.of("runs", "list", "--non-interactive"))).isEqualTo(0);
    assertThat(elsewhere).doesNotExist();
    assertThat(elsewhere.getParent()).doesNotExist();
  }

  @Test
  void should_create_no_jrs_hotfix_directory_in_the_working_directory_without_settings()
      throws Exception {
    Path cwdHome = Path.of("jrs-hotfix").toAbsolutePath();
    Assumptions.assumeFalse(Files.exists(cwdHome), "the working directory has a jrs-hotfix");
    Fixture f = fixture();
    try {
      // no --home, no JRS_HOTFIX_HOME, no pointer and nothing the scan can see
      assertThat(f.runExactly(List.of("list", "--non-interactive"))).isEqualTo(2);
      assertThat(cwdHome).doesNotExist();
    } finally {
      Trees.deleteRecursively(cwdHome);
    }
  }

  @Test
  void should_find_the_last_home_when_no_home_is_given_and_no_scan_sees_it() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("list")).isEqualTo(0);
    assertThat(Files.readString(f.lastHomeFile()).strip()).isEqualTo(f.home.root().toString());
    // no --home, no JRS_HOTFIX_HOME, no ./jrs-hotfix and no installation the scan can see
    assertThat(f.hf.platform.scanInstallDirs().candidates()).isEmpty();
    assertThat(f.runExactly(List.of("list", "--non-interactive"))).isEqualTo(0);
    assertThat(f.out()).contains("can be undone:  nothing");
  }

  @Test
  void should_say_the_installed_build_when_listing() throws Exception {
    Fixture f = fixture();
    Path stamps = f.hf.settings.webappDir().resolve("WEB-INF/internal/jasperserver-pro.properties");
    Files.createDirectories(stamps.getParent());
    Files.writeString(
        stamps, "PRO_VERSION=10.0.0\n  BUILD_DATE_STAMP=20260121\n  BUILD_TIME_STAMP=2317\n");
    assertThat(f.run("list")).isEqualTo(0);
    assertThat(f.out())
        .contains("on this server: 10.0.0 PRO, build 20260121_2317")
        .contains("can be undone:  nothing");
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    assertThat(f.run("list")).isEqualTo(0);
    assertThat(f.out())
        .contains("on this server: 10.0.0 PRO, build 20260121_2317")
        .contains("JRSHF-10.0.0-20260730-0457");
  }

  @Test
  void should_exit_1_on_a_usage_error_or_without_a_subcommand() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply")).isEqualTo(1);
    assertThat(f.run()).isEqualTo(1);
    assertThat(f.run("--version")).isEqualTo(0);
    assertThat(f.out()).contains("jrs-hotfix").contains("Jaspersoft");
  }

  @Test
  void should_prune_nothing_that_is_still_needed_after_an_apply() throws Exception {
    Fixture f = fixture();
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
    assertThat(f.run("runs", "prune", "--older-than", "0")).isEqualTo(0);
    assertThat(f.run("rollback", "--yes")).isEqualTo(0);
  }

  private Fixture fixture() throws IOException {
    Fixture f = Fixture.create(tmp);
    fixtures.add(f);
    return f;
  }

  /** A fake installation with settings in its home, a stub server, and captured streams. */
  static final class Fixture implements AutoCloseable {
    final HotfixFixture hf;
    final Home home;
    final Path pkg;
    final Path config;
    private final HttpServer server;
    private StringWriter out = new StringWriter();
    private StringWriter err = new StringWriter();

    private Fixture(HotfixFixture hf, HttpServer server) throws IOException {
      this.hf = hf;
      this.home = hf.home;
      this.server = server;
      this.pkg = hf.packageFile();
      this.config = hf.root.resolve("config");
      URI base =
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jasperserver-pro");
      SettingsStore.save(home, hf.settings.withKey("baseUrl", base.toString()));
    }

    static Fixture create(Path tmp) throws IOException {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/",
          exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();
      return new Fixture(HotfixFixture.create(tmp), server);
    }

    int run(String... args) {
      List<String> all = new ArrayList<>(List.of(args));
      if (args.length > 0 && !args[0].startsWith("--")) {
        all.addAll(List.of("--home", home.root().toString(), "--non-interactive"));
      }
      return runExactly(all);
    }

    /** Runs {@code args} as given, without the {@code --home} {@link #run} adds. */
    int runExactly(List<String> args) {
      return runExactly(Map.of(), args);
    }

    /** As {@link #runExactly(List)}, with {@code extra} added to the environment. */
    int runExactly(Map<String, String> extra, List<String> args) {
      out = new StringWriter();
      err = new StringWriter();
      Map<String, String> env = new java.util.HashMap<>(env());
      env.putAll(extra);
      Bootstrap.Opener opener = Bootstrap.opener(prompt -> hf.platform, env);
      return Main.commandLine(new PrintWriter(out, true), new PrintWriter(err, true), opener)
          .execute(args.toArray(String[]::new));
    }

    /** The environment the commands see: the configuration directory is the fixture's own. */
    Map<String, String> env() {
      return Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString());
    }

    Path lastHomeFile() {
      return LastHome.file(env(), false);
    }

    String out() {
      return out.toString();
    }

    String err() {
      return err.toString();
    }

    Path target(String packagePath) {
      return hf.target(packagePath);
    }

    HotfixPlans plans() {
      return hf.plans;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}

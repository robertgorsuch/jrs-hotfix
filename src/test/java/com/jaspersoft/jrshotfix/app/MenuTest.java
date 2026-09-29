package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.home.LastHome;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.hotfix.HotfixFixture;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The menu an operator at a terminal gets from a bare {@code jrs-hotfix}: seven entries, each
 * running an ordinary command line through the runner and printing it first.
 */
class MenuTest {

  @TempDir Path tmp;

  private final StringWriter sw = new StringWriter();
  private final PrintWriter out = new PrintWriter(sw, true);
  private final List<String[]> ran = new ArrayList<>();
  private Settings settings;
  private Path pkg;

  @BeforeEach
  void setUp() throws Exception {
    settings =
        new Settings(
            tmp.resolve("jrs"),
            tmp.resolve("jrs/apache-tomcat"),
            "jasperserver-pro",
            ServiceConfig.Kind.MANUAL,
            Optional.empty(),
            Optional.empty(),
            180,
            Optional.empty(),
            URI.create("http://localhost:8080/jasperserver-pro"));
    pkg = Files.writeString(tmp.resolve("hotfix.zip"), "zip");
  }

  @AfterEach
  void restore() {
    Prompter.reset();
  }

  /** The runner every test but one uses: records the argv and succeeds. */
  private int recorder(String[] args) {
    ran.add(args);
    return 0;
  }

  private String text() {
    return sw.toString();
  }

  @Test
  void should_print_seven_entries_and_run_apply_when_1_is_chosen() {
    Prompter.override(new StringReader("1\n" + pkg + "\nq\n"));
    Menu m =
        new Menu(
            out,
            List.of(),
            this::recorder,
            List::of,
            () -> Optional.of(settings),
            () -> "10.0.0 PRO");
    assertThat(m.run()).isZero();
    assertThat(text())
        .contains("1) Apply a hotfix")
        .contains("7) Settings")
        .contains("q) Quit")
        .contains("release 10.0.0 PRO")
        .contains("Running: jrs-hotfix apply " + pkg)
        .contains("Done.");
    assertThat(ran).hasSize(1);
    assertThat(ran.get(0)).containsExactly("apply", pkg.toString());
  }

  @Test
  void should_print_the_menu_exactly_as_designed_when_started() {
    Prompter.override(new StringReader("q\n"));
    new Menu(
            out,
            List.of(),
            this::recorder,
            List::of,
            () -> Optional.of(settings),
            () -> "10.0.0 PRO")
        .run();
    String nl = System.lineSeparator();
    assertThat(text())
        .startsWith(
            "jrs-hotfix - JasperReports Server hotfix tool   (release 10.0.0 PRO at "
                + settings.installDir()
                + ")"
                + nl)
        .contains(
            String.join(
                nl,
                "What do you want to do?",
                "  1) Apply a hotfix",
                "  2) Roll back a hotfix",
                "  3) Verify a hotfix package",
                "  4) List installed hotfixes",
                "  5) Record a hotfix applied by hand",
                "  6) Recent runs and recovery",
                "  7) Settings",
                "  q) Quit",
                "Each entry runs an ordinary command and prints it first. jrs-hotfix --help lists"
                    + " every command.",
                "Choose [1-7, q]: "));
    assertThat(ran).isEmpty();
  }

  @Test
  void should_offer_only_recovery_when_a_run_is_pending() {
    Prompter.override(new StringReader("1\n6\n1\nq\n"));
    Menu m =
        new Menu(
            out,
            List.of(),
            this::recorder,
            () -> List.of("stuck"),
            () -> Optional.of(settings),
            () -> "10.0.0 PRO");
    m.run();
    assertThat(text())
        .contains("interrupted")
        .contains("jrs-hotfix runs resume stuck     (or runs rollback stuck)")
        .contains("finish or undo the interrupted job first (entry 6)");
    // choosing 1 while pending printed a refusal and did not run apply; 6 lists the runs first
    assertThat(ran).hasSize(2);
    assertThat(ran.get(0)).containsExactly("runs", "list");
    assertThat(ran.get(1)).containsExactly("runs", "resume", "stuck");
  }

  @Test
  void should_refuse_every_changing_entry_when_a_run_is_pending() {
    Prompter.override(new StringReader("2\n3\n5\n7\n1\n7\n2\n4\nq\n"));
    new Menu(
            out,
            List.of(),
            this::recorder,
            () -> List.of("stuck"),
            () -> Optional.of(settings),
            () -> "10.0.0 PRO")
        .run();
    assertThat(ran)
        .extracting(a -> String.join(" ", a))
        .containsExactly("settings show", "settings show", "list");
  }

  @Test
  void should_run_the_wizard_first_when_no_settings_exist() {
    Prompter.override(new StringReader("q\n"));
    List<Integer> menuShownAt = new ArrayList<>();
    Function<String[], Integer> runner =
        a -> {
          ran.add(a);
          if (text().contains("What do you want to do?")) {
            menuShownAt.add(ran.size());
          }
          return 0;
        };
    new Menu(out, List.of(), runner, List::of, Optional::empty, () -> "unknown").run();
    assertThat(ran).hasSize(1);
    assertThat(ran.get(0)).containsExactly("settings", "detect");
    assertThat(menuShownAt).isEmpty();
    assertThat(text()).contains("no settings yet");
  }

  @Test
  void should_pass_cascade_when_the_operator_says_yes_to_later_hotfixes() {
    Prompter.override(new StringReader("2\nJRSHF-1\ny\nq\n"));
    new Menu(
            out,
            List.of(),
            this::recorder,
            List::of,
            () -> Optional.of(settings),
            () -> "10.0.0 PRO")
        .run();
    assertThat(ran).hasSize(2);
    assertThat(ran.get(0)).containsExactly("list");
    assertThat(ran.get(1)).containsExactly("rollback", "JRSHF-1", "--cascade");
  }

  @Test
  void should_pass_the_global_options_on_and_report_a_failure_when_a_command_fails() {
    Prompter.override(new StringReader("4\n7\n1\nbaseUrl\nhttp://h:1/x\nq\n"));
    new Menu(
            out,
            List.of("--home", "h"),
            a -> {
              ran.add(a);
              return 2;
            },
            List::of,
            () -> Optional.of(settings),
            () -> "10.0.0 PRO")
        .run();
    assertThat(ran)
        .extracting(a -> String.join(" ", a))
        .containsExactly(
            "list --home h",
            "settings show --home h",
            "settings set baseUrl http://h:1/x --home h");
    assertThat(text())
        .contains("Running: jrs-hotfix list --home h")
        .contains("Finished with exit code 2 (jrs-hotfix --docs explains the codes).");
  }

  @Test
  void should_quit_with_0_when_input_ends() {
    Prompter.override(new StringReader(""));
    assertThat(
            new Menu(
                    out,
                    List.of(),
                    this::recorder,
                    List::of,
                    () -> Optional.of(settings),
                    () -> "10.0.0 PRO")
                .run())
        .isZero();
    assertThat(ran).isEmpty();
  }

  @Test
  void should_pass_on_home_and_output_options_but_never_yes_when_the_menu_starts() {
    GlobalOptions g = new GlobalOptions();
    g.home = tmp;
    g.noColor = true;
    g.ascii = true;
    g.nonInteractive = true;
    assertThat(RootCommand.passOn(g))
        .containsExactly("--home", tmp.toString(), "--no-color", "--ascii");
  }

  @Test
  void should_open_once_and_hand_every_command_the_resolved_home_when_the_menu_runs()
      throws Exception {
    HotfixFixture hf = HotfixFixture.create(tmp.resolve("fx"));
    SettingsStore.save(hf.home, hf.settings);
    Map<String, String> env = Map.of("XDG_CONFIG_HOME", tmp.resolve("config").toString());
    LastHome.write(LastHome.file(env, false), hf.home);
    AtomicInteger opens = new AtomicInteger();
    Bootstrap.Opener real = Bootstrap.opener(prompt -> hf.platform, env);
    RootCommand root = new RootCommand();
    root.global = new GlobalOptions();
    root.opener =
        options -> {
          opens.incrementAndGet();
          return real.open(options);
        };
    Prompter.override(new StringReader("4\n6\n4\nq\n"));
    assertThat(root.menu(out, this::recorder).run()).isZero();
    assertThat(opens).hasValue(1);
    assertThat(ran).hasSize(3);
    for (String[] argv : ran) {
      assertThat(String.join(" ", argv)).contains("--home " + hf.home.root());
    }
  }
}

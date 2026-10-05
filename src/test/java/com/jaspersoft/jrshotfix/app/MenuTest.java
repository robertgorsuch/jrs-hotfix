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
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The menu an operator at a terminal gets from a bare {@code jrs-hotfix}: eleven entries, each
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
  void should_print_eleven_entries_and_run_apply_when_1_is_chosen() {
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
        .contains("4) Check the server for customizations")
        .contains("7) Settings")
        .contains("11) Baselines")
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
                "  2) Undo the latest hotfix",
                "  3) Verify a hotfix package",
                "  4) Check the server for customizations",
                "  5) Show the installed build",
                "  6) Recent runs and recovery",
                "  7) Settings",
                "  8) Hotfix a WAR or webapp directory",
                "  9) Compare WARs, webapps, distributions or packages",
                " 10) Merges",
                " 11) Baselines",
                "  q) Quit",
                "Each entry runs an ordinary command and prints it first. jrs-hotfix --help lists"
                    + " every command.",
                "Choose [1-11, q]: "));
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
        .contains("jrs-hotfix runs resume stuck     (or runs undo stuck)")
        .contains("finish or undo the interrupted job first (entry 6)");
    // choosing 1 while pending printed a refusal and did not run apply; 6 lists the runs first
    assertThat(ran).hasSize(2);
    assertThat(ran.get(0)).containsExactly("runs", "list");
    assertThat(ran.get(1)).containsExactly("runs", "resume", "stuck");
  }

  @Test
  void should_refuse_every_changing_entry_when_a_run_is_pending() {
    Prompter.override(new StringReader("2\n3\n7\n1\n7\n2\n5\nq\n"));
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
  void should_run_rollback_without_asking_which_hotfix_when_2_is_chosen() {
    Prompter.override(new StringReader("2\nq\n"));
    new Menu(
            out,
            List.of(),
            this::recorder,
            List::of,
            () -> Optional.of(settings),
            () -> "10.0.0 PRO")
        .run();
    // the plan names the latest hotfix and asks; there is no other to choose
    assertThat(ran).hasSize(1);
    assertThat(ran.get(0)).containsExactly("rollback");
  }

  @Test
  void should_pass_the_global_options_on_and_report_a_failure_when_a_command_fails() {
    Prompter.override(new StringReader("5\n7\n1\nbaseUrl\nhttp://h:1/x\nq\n"));
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
  void should_run_the_scan_and_offer_to_add_a_baseline_when_there_is_none() {
    Prompter.override(new StringReader("4\n" + pkg + "\n4\n\nq\n"));
    List<Integer> codes = new ArrayList<>(List.of(2, 0, 0, 2));
    new Menu(
            out,
            List.of(),
            a -> {
              ran.add(a);
              return codes.removeFirst();
            },
            List::of,
            () -> Optional.of(settings),
            () -> "10.0.0 PRO")
        .run();
    // the first scan found no baseline: the file given is added and the scan runs again; the
    // second time the operator gives nothing and is back at the menu
    assertThat(ran)
        .extracting(a -> String.join(" ", a))
        .containsExactly("scan", "baseline add " + pkg, "scan", "scan");
    assertThat(text())
        .contains("the vendor's own files")
        .contains("distribution ZIP this server was installed from (recommended");
  }

  @Test
  void should_scan_but_not_offer_a_baseline_when_a_run_is_pending() {
    Prompter.override(new StringReader("4\nq\n"));
    new Menu(
            out,
            List.of(),
            a -> {
              ran.add(a);
              return 2;
            },
            () -> List.of("stuck"),
            () -> Optional.of(settings),
            () -> "10.0.0 PRO")
        .run();
    assertThat(ran).extracting(a -> String.join(" ", a)).containsExactly("scan");
    assertThat(text()).doesNotContain("the vendor's own files");
  }

  /** A menu over the recorder whose commands get {@code --home h}. */
  private Menu menu(Supplier<List<String>> pending) {
    return menu(pending, this::recorder, pkg -> Optional.empty());
  }

  private Menu menu(
      Supplier<List<String>> pending,
      Function<String[], Integer> runner,
      Function<Path, Optional<Menu.WaitingMerge>> waiting) {
    return new Menu(
        out,
        () -> List.of("--home", "h"),
        List.of("--no-color"),
        runner,
        pending,
        () -> Optional.of(settings),
        () -> "10.0.0 PRO",
        () -> {},
        waiting);
  }

  /** A runner that records the argv and exits 2 for a plain apply and a refused resolve. */
  private Function<String[], Integer> refusing(String... refused) {
    List<String> left = new ArrayList<>(List.of(refused));
    return a -> {
      ran.add(a);
      return left.remove(String.join(" ", a)) ? 2 : 0;
    };
  }

  @Test
  void should_decide_each_waiting_file_and_apply_again_when_apply_waits_on_a_merge() {
    List<Path> asked = new ArrayList<>();
    Prompter.override(
        new StringReader(
            String.join("\n", "1", pkg.toString(), "", "", "1", "3", "2", "", "q", "")));
    menu(
            List::of,
            refusing("apply " + pkg + " --home h"),
            p -> {
              asked.add(p);
              return Optional.of(new Menu.WaitingMerge("m1", List.of("a.xml", "b.properties")));
            })
        .run();
    assertThat(asked).containsExactly(pkg);
    assertThat(ranLines())
        .containsExactly(
            "apply " + pkg + " --home h",
            "merge show m1 a.xml --home h",
            "merge resolve m1 a.xml --mine --home h",
            "merge resolve m1 b.properties --merged --home h",
            "apply " + pkg + " --merge m1 --home h");
    assertThat(text())
        .contains("2 file(s) changed by both this site and the hotfix wait for your decision")
        .contains("    a.xml");
  }

  @Test
  void should_ask_again_after_a_refused_decision_and_stop_without_applying_on_enter() {
    Prompter.override(
        new StringReader(String.join("\n", "1", pkg.toString(), "", "y", "2", "", "q", "")));
    menu(
            List::of,
            refusing("apply " + pkg + " --home h", "merge resolve m1 a.xml --merged --home h"),
            p -> Optional.of(new Menu.WaitingMerge("m1", List.of("a.xml"))))
        .run();
    // the merged file failed its checks: asked again, and Enter stops with no second apply
    assertThat(ranLines())
        .containsExactly("apply " + pkg + " --home h", "merge resolve m1 a.xml --merged --home h");
    assertThat(text()).contains("entry 10 continues it");
  }

  @Test
  void should_offer_nothing_more_when_apply_fails_without_a_waiting_merge() {
    Prompter.override(new StringReader("1\n" + pkg + "\n\nq\n"));
    menu(List::of, refusing("apply " + pkg + " --home h"), p -> Optional.empty()).run();
    assertThat(ranLines()).containsExactly("apply " + pkg + " --home h");
    assertThat(text()).doesNotContain("wait for your decision");
  }

  private List<String> ranLines() {
    return ran.stream().map(a -> String.join(" ", a)).toList();
  }

  @Test
  void should_work_on_a_war_in_the_home_the_command_line_would_use_when_8_is_chosen()
      throws Exception {
    Path war = Files.createDirectories(tmp.resolve("webapps/jasperserver-pro"));
    Path target = tmp.resolve("out.war");
    Prompter.override(
        new StringReader(
            String.join(
                "\n",
                "8",
                war.toString(),
                "1",
                pkg.toString(),
                target.toString(),
                "y",
                "",
                "",
                "8",
                war.toString(),
                "2",
                pkg.toString(),
                "8",
                war.toString(),
                "3",
                "q",
                "")));
    menu(List::of).run();
    // the server's home is not passed on: a WAR's home is beside it unless one was given
    assertThat(ranLines())
        .containsExactly(
            "apply " + pkg + " --war " + war + " --out " + target + " --generic --no-color",
            "verify " + pkg + " --war " + war + " --no-color",
            "scan --war " + war + " --no-color");
  }

  @Test
  void should_compare_two_or_three_inputs_when_9_is_chosen() {
    Prompter.override(
        new StringReader(
            String.join(
                "\n",
                "9",
                "a.war",
                "server",
                "",
                "",
                "9",
                "base",
                "mine",
                "theirs",
                "",
                "out",
                "9",
                "base",
                "mine",
                "theirs",
                "WEB-INF/web.xml",
                "q",
                "")));
    menu(List::of).run();
    assertThat(ranLines())
        .containsExactly(
            "compare a.war server --home h",
            "compare base mine theirs --out out --home h",
            "compare base mine theirs --show WEB-INF/web.xml --home h");
  }

  @Test
  void should_list_the_merges_then_run_the_chosen_merge_command_when_10_is_chosen() {
    Prompter.override(
        new StringReader(
            String.join(
                "\n",
                "10",
                "1",
                "m1",
                "10",
                "2",
                "m1",
                "WEB-INF/web.xml",
                "10",
                "3",
                "m1",
                "WEB-INF/web.xml",
                "2",
                "10",
                "3",
                "m1",
                "WEB-INF/web.xml",
                "4",
                "10",
                "3",
                "m1",
                "WEB-INF/web.xml",
                "5",
                pkg.toString(),
                "10",
                "4",
                pkg.toString(),
                "Mine",
                "10",
                "5",
                "m1",
                "q",
                "")));
    menu(List::of).run();
    assertThat(ranLines())
        .containsExactly(
            "merge list --home h",
            "merge status m1 --home h",
            "merge list --home h",
            "merge show m1 WEB-INF/web.xml --home h",
            "merge list --home h",
            "merge resolve m1 WEB-INF/web.xml --merged --home h",
            "merge list --home h",
            "merge resolve m1 WEB-INF/web.xml --theirs --home h",
            "merge list --home h",
            "merge resolve m1 WEB-INF/web.xml --merged " + pkg + " --home h",
            "merge list --home h",
            "merge prepare " + pkg + " --on-conflict mine --home h",
            "merge list --home h",
            "merge discard m1 --home h");
  }

  @Test
  void should_list_the_baselines_then_add_or_remove_one_when_11_is_chosen() {
    Prompter.override(new StringReader("11\n1\n" + pkg + "\n11\n2\nb1\nq\n"));
    menu(List::of).run();
    assertThat(ranLines())
        .containsExactly(
            "baseline list --home h",
            "baseline add " + pkg + " --home h",
            "baseline list --home h",
            "baseline remove b1 --home h");
  }

  @Test
  void should_show_a_run_or_prune_old_runs_when_none_is_pending() {
    Prompter.override(new StringReader("6\n1\nr1\n6\n2\n30\ny\n6\n2\n\n\nq\n"));
    menu(List::of).run();
    assertThat(ranLines())
        .containsExactly(
            "runs list --home h",
            "runs show r1 --home h",
            "runs list --home h",
            "runs prune --older-than 30 --include-failed --home h",
            "runs list --home h",
            "runs prune --home h");
  }

  @Test
  void should_refuse_the_new_changing_entries_but_not_the_reading_ones_when_a_run_is_pending()
      throws Exception {
    Path war = Files.writeString(tmp.resolve("jasperserver-pro.war"), "war");
    Prompter.override(
        new StringReader(
            String.join(
                "\n",
                "8",
                war.toString(),
                "1",
                "10",
                "3",
                "10",
                "4",
                "10",
                "5",
                "11",
                "1",
                "11",
                "2",
                "6",
                "3",
                "stuck",
                "9",
                "a",
                "b",
                "",
                "",
                "q",
                "")));
    menu(() -> List.of("stuck")).run();
    assertThat(ranLines())
        .containsExactly(
            "merge list --home h",
            "merge list --home h",
            "merge list --home h",
            "baseline list --home h",
            "baseline list --home h",
            "runs list --home h",
            "runs show stuck --home h",
            "compare a b --home h");
  }

  @Test
  void should_pass_the_apply_options_asked_for_and_keep_them_when_applying_again() {
    Prompter.override(
        new StringReader(
            String.join(
                "\n",
                "1",
                pkg.toString(),
                "y",
                "y",
                "maybe",
                "MINE", // a wrong rule is asked again
                "",
                "4",
                "",
                "q",
                "")));
    menu(
            List::of,
            refusing("apply " + pkg + " --keep-superseded --on-conflict mine --home h"),
            p -> Optional.of(new Menu.WaitingMerge("m1", List.of("a.xml"))))
        .run();
    // a merge named is applied as prepared: only --keep-superseded still means something
    assertThat(ranLines())
        .containsExactly(
            "apply " + pkg + " --keep-superseded --on-conflict mine --home h",
            "merge resolve m1 a.xml --theirs --home h",
            "apply " + pkg + " --merge m1 --keep-superseded --home h");
    assertThat(text()).contains("please type ask, mine, theirs or fail");
  }

  @Test
  void should_work_on_the_merges_of_a_war_in_its_own_home_when_8_then_4_is_chosen()
      throws Exception {
    Path war = Files.createDirectories(tmp.resolve("webapps/jasperserver-pro"));
    Path home = tmp.resolve("jrs-hotfix");
    Prompter.override(
        new StringReader(
            String.join(
                "\n",
                "8",
                war.toString(),
                "4",
                "4",
                pkg.toString(),
                "",
                "8",
                war.toString(),
                "4",
                "1",
                "m1",
                "q",
                "")));
    Function<String[], Integer> runner =
        a -> {
          ran.add(a);
          if (a[1].equals("prepare")) {
            // the first merge makes the home beside the webapps directory
            try {
              Files.createDirectories(home);
              Files.writeString(new com.jaspersoft.jrshotfix.home.Home(home).settingsFile(), "{}");
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }
          return 0;
        };
    menu(List::of, runner, p -> Optional.empty()).run();
    assertThat(text()).contains("no merges for this WAR yet");
    assertThat(ranLines())
        .containsExactly(
            "merge prepare " + pkg + " --war " + war + " --no-color --home " + home,
            "merge list --no-color --home " + home,
            "merge status m1 --no-color --home " + home);
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
    g.nonInteractive = true;
    assertThat(RootCommand.passOn(g)).containsExactly("--home", tmp.toString(), "--no-color");
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
    Prompter.override(new StringReader("5\n6\n\n5\nq\n"));
    assertThat(root.menu(out, this::recorder).run()).isZero();
    assertThat(opens).hasValue(1);
    assertThat(ran).hasSize(3);
    for (String[] argv : ran) {
      assertThat(String.join(" ", argv)).contains("--home " + hf.home.root());
    }
  }
}

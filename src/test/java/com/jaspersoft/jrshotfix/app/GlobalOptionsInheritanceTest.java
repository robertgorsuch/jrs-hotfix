package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The global options given before a subcommand's name reach the command that runs. */
class GlobalOptionsInheritanceTest {

  @TempDir Path tmp;

  private final List<GlobalOptions> opened = new ArrayList<>();

  private GlobalOptions run(String... args) {
    Bootstrap.Opener capture =
        options -> {
          opened.add(options);
          throw new IllegalStateException("captured, not opened");
        };
    Main.commandLine(
            new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), capture)
        .execute(args);
    assertThat(opened).hasSize(1);
    return opened.get(0);
  }

  @Test
  void should_accept_non_interactive_but_leave_it_out_of_the_help() {
    StringWriter help = new StringWriter();
    Main.commandLine(
            new PrintWriter(help, true), new PrintWriter(new StringWriter()), Bootstrap.DEFAULT)
        .execute("--help");
    assertThat(help.toString()).contains("--yes").doesNotContain("--non-interactive");
    // hidden since 0.6, still honoured: scripts that pass it keep working
    assertThat(run("--non-interactive", "list").nonInteractive()).isTrue();
  }

  @Test
  void should_use_the_home_given_before_the_command_when_the_command_names_none() {
    Path home = tmp.resolve("home");

    GlobalOptions seen = run("--home", home.toString(), "--yes", "list");

    assertThat(seen.home()).contains(home);
    assertThat(seen.yes()).isTrue();
    assertThat(seen.nonInteractive()).isTrue();
  }

  @Test
  void should_pass_the_options_through_every_level_when_the_command_is_nested() {
    Path home = tmp.resolve("home");

    GlobalOptions seen = run("--home", home.toString(), "--non-interactive", "runs", "list");

    assertThat(seen.home()).contains(home);
    assertThat(seen.nonInteractive()).isTrue();
    assertThat(seen.yes()).isFalse();
  }

  @Test
  void should_prefer_the_commands_own_home_when_both_are_given() {
    Path outer = tmp.resolve("outer");
    Path inner = tmp.resolve("inner");

    GlobalOptions seen = run("--home", outer.toString(), "list", "--home", inner.toString());

    assertThat(seen.home()).contains(inner);
  }
}

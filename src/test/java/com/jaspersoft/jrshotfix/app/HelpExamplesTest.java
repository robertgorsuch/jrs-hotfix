package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** Every leaf command's {@code --help} ends with one example, and that example really parses. */
class HelpExamplesTest {

  private static final Pattern BLANK = Pattern.compile("\\s+");

  @Test
  void should_have_exactly_one_example_for_every_leaf_command() {
    List<String> missing = new ArrayList<>();
    for (CommandLine cmd : all()) {
      CommandSpec spec = cmd.getCommandSpec();
      if (leaf(spec) && exampleLine(spec).isEmpty()) {
        missing.add(spec.qualifiedName(" "));
      }
    }
    assertThat(missing).as("leaf commands without an Example: footer").isEmpty();
  }

  @Test
  void should_resolve_every_example_against_the_command_tree() {
    CommandLine root = commandLine();
    for (CommandLine cmd : all()) {
      CommandSpec spec = cmd.getCommandSpec();
      Optional<String> example = exampleLine(spec);
      if (example.isEmpty()) {
        continue;
      }
      List<String> words = List.of(BLANK.split(example.get().trim()));
      assertThat(words.get(0)).as(example.get()).isEqualTo("jrs-hotfix");

      CommandLine resolved = root;
      int i = 1;
      while (i < words.size() && resolved.getSubcommands().containsKey(words.get(i))) {
        resolved = resolved.getSubcommands().get(words.get(i));
        i++;
      }
      assertThat(resolved.getCommandSpec().qualifiedName(" "))
          .as(example.get())
          .isEqualTo(spec.qualifiedName(" "));

      for (; i < words.size(); i++) {
        String word = words.get(i);
        if (word.startsWith("--")) {
          String name = word.contains("=") ? word.substring(0, word.indexOf('=')) : word;
          assertThat(resolved.getCommandSpec().findOption(name))
              .as(word + " in \"" + example.get() + "\"")
              .isNotNull();
        }
      }
    }
  }

  private static CommandLine commandLine() {
    return Main.commandLine(
        new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
  }

  private static List<CommandLine> all() {
    List<CommandLine> out = new ArrayList<>();
    collect(commandLine(), out);
    return out;
  }

  private static void collect(CommandLine cmd, List<CommandLine> out) {
    out.add(cmd);
    for (CommandLine sub : cmd.getSubcommands().values()) {
      collect(sub, out);
    }
  }

  /**
   * A command with no subcommands of its own: apply, rollback, verify, list, record, runs
   * list/show/resume/rollback/prune, settings show/set/detect. The root and the two groups ({@code
   * runs}, {@code settings}) are not leaves and need no example.
   */
  private static boolean leaf(CommandSpec spec) {
    return spec.parent() != null && spec.subcommands().isEmpty();
  }

  private static Optional<String> exampleLine(CommandSpec spec) {
    String[] footer = spec.usageMessage().footer();
    for (int i = 0; i < footer.length; i++) {
      if (footer[i].equals("Example:") && i + 1 < footer.length) {
        return Optional.of(footer[i + 1]);
      }
    }
    return Optional.empty();
  }
}

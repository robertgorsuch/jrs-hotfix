package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.platform.UserPaths;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import picocli.CommandLine;

/**
 * Process entry point. Invariants: the only place that calls {@code System.exit}; the fully
 * configured command line (the {@code Path} converter that expands a leading {@code ~}, the exit
 * code and usage-error handlers, exit 1 for invalid input on every command) is built by exactly one
 * factory, {@link #commandLine(PrintWriter, PrintWriter, Bootstrap.Opener)}, so tests drive the
 * same tree an operator gets.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String... args) {
    CommandLine cl = build(Bootstrap.DEFAULT);
    if (Arrays.asList(args).contains("--no-color") || Env.vars().containsKey("NO_COLOR")) {
      // picocli colours help and usage errors on its own; --no-color covers those too
      cl.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
    }
    return cl.execute(args);
  }

  /** The command tree writing to {@code out} and {@code err}, for in-process callers. */
  static CommandLine commandLine(PrintWriter out, PrintWriter err) {
    return commandLine(out, err, Bootstrap.DEFAULT);
  }

  static CommandLine commandLine(PrintWriter out, PrintWriter err, Bootstrap.Opener opener) {
    CommandLine cl = build(opener);
    cl.setOut(out);
    cl.setErr(err);
    return cl;
  }

  private static CommandLine build(Bootstrap.Opener opener) {
    CommandLine cl =
        new CommandLine(RootCommand.class, new Factory(opener))
            // a leading ~ means the operator's home in every Path option
            .registerConverter(Path.class, s -> Path.of(UserPaths.expand(s, Env.vars())))
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setUsageHelpAutoWidth(true)
            .setExecutionExceptionHandler(new ExitCodes.Handler())
            .setParameterExceptionHandler(new ExitCodes.ParameterHandler())
            .setExecutionStrategy(
                parsed -> {
                  inheritGlobalOptions(parsed);
                  return new CommandLine.RunLast().execute(parsed);
                });
    usageErrorsExitOne(cl);
    return cl;
  }

  /**
   * Hands the global options given before a subcommand's name down to it: each command mixes the
   * options in on its own, so without this {@code --home} before {@code apply} reached only the
   * root command and the leaf silently resolved another home.
   */
  private static void inheritGlobalOptions(CommandLine.ParseResult parsed) {
    Optional<GlobalOptions> above = Optional.empty();
    CommandLine.ParseResult level = parsed;
    while (true) {
      Optional<GlobalOptions> here = globalOptionsOf(level.commandSpec());
      if (here.isPresent()) {
        above.ifPresent(here.get()::inheritFrom);
        above = here;
      }
      if (!level.hasSubcommand()) {
        return;
      }
      level = level.subcommand();
    }
  }

  private static Optional<GlobalOptions> globalOptionsOf(CommandLine.Model.CommandSpec spec) {
    return spec.mixins().values().stream()
        .map(CommandLine.Model.CommandSpec::userObject)
        .filter(GlobalOptions.class::isInstance)
        .map(GlobalOptions.class::cast)
        .findFirst();
  }

  private static void usageErrorsExitOne(CommandLine cl) {
    cl.getCommandSpec().exitCodeOnInvalidInput(ExitCodes.USAGE);
    for (CommandLine sub : cl.getSubcommands().values()) {
      usageErrorsExitOne(sub);
    }
  }

  /** Creates commands through picocli's default factory and hands each the opener. */
  private static final class Factory implements CommandLine.IFactory {
    private final Bootstrap.Opener opener;

    Factory(Bootstrap.Opener opener) {
      this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public <K> K create(Class<K> cls) throws Exception {
      K instance = CommandLine.defaultFactory().create(cls);
      if (instance instanceof AppCommand command) {
        command.opener = opener;
      } else if (instance instanceof RootCommand root) {
        root.opener = opener;
      }
      return instance;
    }
  }
}

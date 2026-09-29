package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.platform.UserPaths;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
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
            .setParameterExceptionHandler(new ExitCodes.ParameterHandler());
    usageErrorsExitOne(cl);
    return cl;
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

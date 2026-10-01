package com.jaspersoft.jrshotfix.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Base of every command: the command spec for its output streams, the global options, and the
 * {@link Bootstrap.Opener} the command line was built with. Invariants: a command writes only
 * through {@link #out()} and {@link #err()}, so an in-process caller captures everything; the
 * opener is set by {@link Main}'s factory before {@code call()} runs; the global options are the
 * root command's, which picocli inherits into every subcommand, so they are the same wherever on
 * the line they were given.
 */
abstract class AppCommand implements Callable<Integer> {

  Bootstrap.Opener opener = Bootstrap.DEFAULT;

  @Spec CommandSpec spec;

  PrintWriter out() {
    return spec.commandLine().getOut();
  }

  PrintWriter err() {
    return spec.commandLine().getErr();
  }

  GlobalOptions global() {
    return ((RootCommand) spec.root().userObject()).global;
  }

  Bootstrap open() {
    return opener.open(global());
  }

  Ansi ansi() {
    return Ansi.forStdout(global(), Env.vars());
  }

  PlanExecutor executor(Bootstrap boot) {
    return new PlanExecutor(boot, new RunService(boot), out(), err(), ansi(), global().yes());
  }

  /** A table as wide as the terminal. */
  static TextTable table() {
    return new TextTable(Terminal.width(Env.vars()));
  }
}

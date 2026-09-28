package com.jaspersoft.jrshotfix.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Base of every leaf command: the global options, the command spec for its output streams, and the
 * {@link Bootstrap.Opener} the command line was built with. Invariants: a command writes only
 * through {@link #out()} and {@link #err()}, so an in-process caller captures everything; the
 * opener is set by {@link Main}'s factory before {@code call()} runs.
 */
abstract class AppCommand implements Callable<Integer> {

  Bootstrap.Opener opener = Bootstrap.DEFAULT;

  @Spec CommandSpec spec;

  @Mixin GlobalOptions global;

  PrintWriter out() {
    return spec.commandLine().getOut();
  }

  PrintWriter err() {
    return spec.commandLine().getErr();
  }

  Bootstrap open() {
    return opener.open(global);
  }

  Ansi ansi() {
    return Ansi.forStdout(global, Env.vars());
  }

  PlanExecutor executor(Bootstrap boot) {
    return new PlanExecutor(boot, new RunService(boot), out(), err(), ansi(), global.yes());
  }
}

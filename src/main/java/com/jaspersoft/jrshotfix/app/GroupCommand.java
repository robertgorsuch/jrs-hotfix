package com.jaspersoft.jrshotfix.app;

/**
 * A command that only groups subcommands ({@code runs}, {@code baseline}, {@code merge}, {@code
 * settings}). Invariant: run without a subcommand, it prints its usage on stderr and exits 1.
 */
abstract class GroupCommand extends AppCommand {

  @Override
  public Integer call() {
    spec.commandLine().usage(err());
    return ExitCodes.USAGE;
  }
}

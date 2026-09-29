package com.jaspersoft.jrshotfix.app;

import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Option;

/**
 * Options every command accepts, mixed into each subcommand so they may be given after the command
 * name. Invariants: {@code --yes} is the only flag that answers a confirmation, and it implies
 * {@code --non-interactive}; {@code --non-interactive} alone never confirms anything, so a prompt
 * it suppresses fails closed; colour is decided by {@link Ansi}, never here.
 */
public final class GlobalOptions {

  @Option(
      names = "--home",
      paramLabel = "<dir>",
      description =
          "jrs-hotfix home: settings, ledger, snapshots, runs and logs live here (default:"
              + " $JRS_HOTFIX_HOME, else <installDir>/jrs-hotfix).")
  Path home;

  @Option(
      names = "--yes",
      description = "Answer yes to confirmations without asking; implies --non-interactive.")
  boolean yes;

  @Option(
      names = "--non-interactive",
      description =
          "Never prompt; exit 2 where a confirmation is needed. Does not confirm anything: pair"
              + " it with --yes to run unattended.")
  boolean nonInteractive;

  @Option(names = "--no-color", description = "Disable ANSI colour in text output.")
  boolean noColor;

  @Option(
      names = "--no-pager",
      description = "Print long text at once instead of a screen at a time.")
  boolean noPager;

  @Option(
      names = "--ascii",
      description =
          "Use ASCII only in text output; the default uses tick and arrow glyphs when the output"
              + " encoding can carry them.")
  boolean ascii;

  public Optional<Path> home() {
    return Optional.ofNullable(home);
  }

  /** True only for {@code --yes}: the one flag that answers a confirmation. */
  public boolean yes() {
    return yes;
  }

  /** True when no prompt may be shown: {@code --non-interactive}, or {@code --yes} implying it. */
  public boolean nonInteractive() {
    return nonInteractive || yes;
  }

  public boolean noColor() {
    return noColor;
  }

  public boolean noPager() {
    return noPager;
  }

  /** True when the operator asked for ASCII-only output. */
  public boolean ascii() {
    return ascii;
  }

  /**
   * Takes what {@code outer} (the same options given before this command's name) set and this
   * command did not: {@code jrs-hotfix --home <dir> apply ...} must mean the same as {@code
   * jrs-hotfix apply ... --home <dir>}. A value given on this command wins; a flag set on either is
   * set.
   */
  void inheritFrom(GlobalOptions outer) {
    if (home == null) {
      home = outer.home;
    }
    yes |= outer.yes;
    nonInteractive |= outer.nonInteractive;
    noColor |= outer.noColor;
    noPager |= outer.noPager;
    ascii |= outer.ascii;
  }
}

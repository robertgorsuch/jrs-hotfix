package com.jaspersoft.jrshotfix.app;

import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * Options every command accepts, mixed into the root command and inherited by every subcommand, so
 * they may be given before or after a command's name and land in this one object (given both before
 * and after the name, the later value wins). Invariants: {@code --yes} is the only flag that
 * answers a confirmation, and it implies {@code --non-interactive}; {@code --non-interactive} alone
 * never confirms anything, so a prompt it suppresses fails closed; colour is decided by {@link
 * Ansi}, never here.
 */
public final class GlobalOptions {

  @Option(
      names = "--home",
      scope = ScopeType.INHERIT,
      paramLabel = "<dir>",
      description =
          "jrs-hotfix home: settings, the undo, runs and logs live here (default:"
              + " $JRS_HOTFIX_HOME, else <installDir>/jrs-hotfix).")
  Path home;

  @Option(
      names = "--yes",
      scope = ScopeType.INHERIT,
      description = "Answer yes to confirmations without asking; implies --non-interactive.")
  boolean yes;

  @Option(
      names = "--non-interactive",
      scope = ScopeType.INHERIT,
      description =
          "Never prompt; exit 2 where a confirmation is needed. Does not confirm anything: pair"
              + " it with --yes to run unattended.")
  boolean nonInteractive;

  @Option(
      names = "--no-color",
      scope = ScopeType.INHERIT,
      description = "Disable ANSI colour in text output.")
  boolean noColor;

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
}

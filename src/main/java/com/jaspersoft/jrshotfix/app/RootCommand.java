package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.Version;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code jrs-hotfix} command. Invariant: without a subcommand it prints the usage and exits 1;
 * the guided menu for an operator at a terminal replaces that in a later task.
 */
@Command(
    name = "jrs-hotfix",
    header = "jrs-hotfix - JasperReports Server hotfix tool (Jaspersoft)",
    mixinStandardHelpOptions = true,
    versionProvider = RootCommand.VersionProvider.class,
    subcommands = {
      ApplyCommand.class,
      RollbackCommand.class,
      VerifyCommand.class,
      ListCommand.class,
      RecordCommand.class,
      RunsCommand.class,
      SettingsCommand.class
    })
final class RootCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Mixin GlobalOptions global;

  @Override
  public Integer call() {
    // Task 13 replaces this with the guided menu when a terminal is present
    spec.commandLine().usage(spec.commandLine().getErr());
    return ExitCodes.USAGE;
  }

  /** {@code --version}: the product, its version and the vendor line. */
  static final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
      Version v = Version.current();
      return new String[] {v.product() + " " + v.version(), v.vendor()};
    }
  }
}

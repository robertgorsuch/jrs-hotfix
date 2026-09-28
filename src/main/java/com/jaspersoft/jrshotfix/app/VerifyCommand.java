package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.pkg.OfficialPackage;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code jrs-hotfix verify <package.zip>}: what the package is and whether it applies here.
 * Invariants: writes nothing; exit 0 when it applies, 2 when it is readable but does not apply (or
 * cannot be read), 6 when the file is not an official hotfix package at all; the reasons go to
 * standard error.
 */
@Command(
    name = "verify",
    mixinStandardHelpOptions = true,
    description = "Check a hotfix package against this installation; changes nothing.")
final class VerifyCommand extends AppCommand {

  @Parameters(index = "0", paramLabel = "<package.zip>", description = "The hotfix ZIP.")
  Path file;

  @Override
  public Integer call() {
    Bootstrap boot = open();
    HotfixPlans.VerifyReport r = boot.plans().verify(file);
    PrintWriter out = out();
    if (r.readable()) {
      out.println(r.id() + "  " + r.title());
      out.println("  release  " + r.release() + " " + r.edition());
      out.println(
          "  files    "
              + r.adds().size()
              + " to add, "
              + r.replaces().size()
              + " to replace, "
              + r.deletes().size()
              + " to delete");
      section(out, "add", r.adds());
      section(out, "replace", r.replaces());
      section(out, "delete", r.deletes());
      section(out, "notes", r.notes());
      out.println(r.applicable() ? "applies to this installation" : "does not apply here");
      out.flush();
    }
    PrintWriter err = err();
    for (String problem : r.problems()) {
      err.println(boot.redactor().redact("error: " + problem));
    }
    err.flush();
    if (r.ok()) {
      return ExitCodes.SUCCESS;
    }
    boolean notAPackage =
        !r.readable() && Files.isRegularFile(file) && !OfficialPackage.looksOfficial(file);
    return notAPackage ? ExitCodes.UNSUPPORTED : ExitCodes.PRECHECK_FAILED;
  }

  private static void section(PrintWriter out, String heading, List<String> items) {
    if (items.isEmpty()) {
      return;
    }
    out.println(heading);
    for (String item : items) {
      out.println("  " + item);
    }
  }
}

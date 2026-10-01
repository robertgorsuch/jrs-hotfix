package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.state.UndoRecord;
import java.io.PrintWriter;
import java.util.Optional;
import picocli.CommandLine.Command;

/**
 * {@code jrs-hotfix list}: what the webapp says it is, release, edition and build, then the hotfix
 * {@code rollback} would undo (0.6 design, section 5). The packages are cumulative, so the build is
 * the hotfix level of the files on disk, whoever applied it. Invariant: read-only.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "Show the build this server states and the hotfix rollback would undo.",
    footer = {"", "Example:", "  jrs-hotfix list"})
final class ListCommand extends AppCommand {

  @Override
  public Integer call() {
    Bootstrap boot = open();
    Optional<UndoRecord> undo = boot.plans().undo();
    PrintWriter out = out();
    Optional<String> stated = Optional.empty();
    if (boot.settings().isPresent()) {
      out.println("on this server: " + InstalledBuild.describe(boot.settings().get().webappDir()));
      stated =
          InstalledBuild.ofWebapp(boot.settings().get().webappDir()).map(InstalledBuild::build);
    }
    if (undo.isEmpty()) {
      out.println("can be undone:  nothing");
    } else {
      UndoRecord u = undo.get();
      out.println(
          "can be undone:  " + u.id() + ", applied " + u.appliedAt() + " by run " + u.runId());
      if (stated.isPresent() && !stated.get().equals(u.build())) {
        out.println(
            "  the webapp now states build "
                + stated.get()
                + ", not "
                + u.build()
                + ": the server has changed since that apply, and `rollback` would check every file"
                + " before it starts");
      }
    }
    out.flush();
    return ExitCodes.SUCCESS;
  }
}

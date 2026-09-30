package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.state.HotfixState;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Compares the build the webapp states about itself with what the ledger says is installed, before
 * a package is applied. Invariants: read-only; a problem refuses the apply (exit 2) and a warning
 * does not; only a ledger entry whose hotfix shipped the build file is compared, since a package
 * without it leaves the stamps as they were; builds compare as text, which is their order in time
 * ({@code yyyymmdd_hhmm}).
 */
record BuildCheck(List<String> problems, List<String> warnings) {

  /** The file the stamps are read from, as the tail of an owned file's path. */
  private static final String BUILD_FILE = "WEB-INF/internal/jasperserver-pro.properties";

  BuildCheck {
    problems = List.copyOf(problems);
    warnings = List.copyOf(warnings);
  }

  /**
   * {@code releaseBuilds} are the builds known to be a release as the vendor shipped it (from the
   * release baselines); with none known and an empty ledger, a build cannot be told from a hotfix
   * applied by hand, and nothing is said about it.
   */
  static BuildCheck of(HotfixRuntime rt, PackageContents c, Set<String> releaseBuilds) {
    List<String> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Optional<InstalledBuild> stated = InstalledBuild.ofWebapp(rt.settings().webappDir());
    if (stated.isEmpty()) {
      warnings.add(
          "the webapp states no build ("
              + BUILD_FILE
              + " is absent or unreadable), so a hotfix applied outside jrs-hotfix would not be"
              + " noticed");
      return new BuildCheck(problems, warnings);
    }
    String installed = stated.get().build();
    List<LedgerEntry> entries =
        rt.ledger().all().stream()
            .filter(e -> e.state() == HotfixState.INSTALLED && shipsBuildFile(e))
            .toList();
    boolean inLedger =
        rt.ledger().find(c.id()).filter(e -> e.state() == HotfixState.INSTALLED).isPresent();
    if (installed.equals(c.build()) && !inLedger) {
      problems.add(
          c.id()
              + " is already on this server (the webapp states build "
              + installed
              + ") but the ledger does not list it as installed; it was applied by hand or by"
              + " another tool, so run `jrs-hotfix record <package.zip>` and the ledger will know"
              + " it");
      return new BuildCheck(problems, warnings);
    }
    Optional<LedgerEntry> newest = entries.stream().max(Comparator.comparing(LedgerEntry::build));
    if (newest.isPresent() && installed.compareTo(newest.get().build()) < 0) {
      problems.add(
          "the webapp states build "
              + installed
              + ", older than "
              + newest.get().id()
              + " which the ledger lists as installed: the webapp was replaced under the ledger"
              + " (redeployed from a WAR?). If the webapp is right, run `jrs-hotfix forget "
              + newest.get().id()
              + "` for it and for every later entry; if the ledger is right, put back the webapp"
              + " it describes");
      return new BuildCheck(problems, warnings);
    }
    boolean known =
        newest.map(e -> e.build().equals(installed)).orElse(false)
            || releaseBuilds.contains(installed);
    if (!known && !inLedger && (newest.isPresent() || !releaseBuilds.isEmpty())) {
      warnings.add(
          "a hotfix with build "
              + installed
              + " was applied outside jrs-hotfix: the ledger has no entry for it; `jrs-hotfix"
              + " record <its package.zip>` adds one");
    }
    return new BuildCheck(problems, warnings);
  }

  private static boolean shipsBuildFile(LedgerEntry e) {
    return e.files().stream()
        .anyMatch(
            f ->
                !f.action().equals("delete")
                    && f.path().toString().replace('\\', '/').endsWith("/" + BUILD_FILE));
  }
}

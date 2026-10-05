package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.platform.UserPaths;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The menu {@code jrs-hotfix} offers when it is started without a command at a terminal.
 * Invariants: every job is carried out by running the ordinary command line through {@code runner},
 * so plans, confirmations, the run lock and exit codes are exactly the CLI's; the command line is
 * printed before it runs; the global options the menu was started with are passed on to every
 * command, with the home the menu resolved (except to the wizard, which places settings itself, and
 * to the commands on a WAR or webapp directory, whose home is beside it unless one was given);
 * without settings the wizard ({@code settings detect}) runs before the menu is shown; while a run
 * is pending, every entry that would change the installation or the settings refuses and points at
 * entry 6; an empty answer where one is required returns to the menu; end of input quits with exit
 * 0; nothing is written by the menu itself.
 */
final class Menu {

  static final String PENDING_REFUSAL = "finish or undo the interrupted job first (entry 6)";

  private final PrintWriter out;
  private final Supplier<List<String>> globalArgs;
  private final List<String> givenArgs;
  private final Function<String[], Integer> runner;
  private final Supplier<List<String>> pendingRuns;
  private final Supplier<Optional<Settings>> settings;
  private final Supplier<String> installedRelease;
  private final Runnable settingsChanged;

  /**
   * {@code settings} gives the current settings, empty when there are none yet; {@code
   * installedRelease} gives the release and edition of the configured webapp, e.g. {@code "10.0.0
   * PRO"}, for the header line.
   */
  Menu(
      PrintWriter out,
      List<String> globalArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Settings>> settings,
      Supplier<String> installedRelease) {
    this(
        out,
        () -> globalArgs,
        globalArgs,
        runner,
        pendingRuns,
        settings,
        installedRelease,
        () -> {});
  }

  /**
   * As above, with the global options read from {@code globalArgs} before every command (they name
   * the home the menu resolved), {@code givenArgs} for {@code settings detect} and the commands on
   * a WAR (the options as the operator gave them, so the wizard places new settings beside the
   * installation it picks, and a WAR is worked on in the home the command line would use), and
   * {@code settingsChanged} run after the wizard or entry 7, so the header and the home are read
   * again.
   */
  Menu(
      PrintWriter out,
      Supplier<List<String>> globalArgs,
      List<String> givenArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Settings>> settings,
      Supplier<String> installedRelease,
      Runnable settingsChanged) {
    this.out = Objects.requireNonNull(out, "out");
    this.globalArgs = Objects.requireNonNull(globalArgs, "globalArgs");
    this.givenArgs = List.copyOf(givenArgs);
    this.runner = Objects.requireNonNull(runner, "runner");
    this.pendingRuns = Objects.requireNonNull(pendingRuns, "pendingRuns");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.installedRelease = Objects.requireNonNull(installedRelease, "installedRelease");
    this.settingsChanged = Objects.requireNonNull(settingsChanged, "settingsChanged");
  }

  /** Runs the wizard when there are no settings, then shows the menu until the operator quits. */
  int run() {
    if (settings.get().isEmpty()) {
      detect();
      out.println();
    }
    Optional<Settings> current = settings.get();
    out.println(
        "jrs-hotfix - JasperReports Server hotfix tool   ("
            + current
                .map(s -> "release " + installedRelease.get() + " at " + s.installDir())
                .orElse("no settings yet")
            + ")");
    List<String> pending = pendingRuns.get();
    if (!pending.isEmpty()) {
      out.println();
      out.println("! An earlier job was interrupted and must be finished or undone first:");
      for (String id : pending) {
        out.println("    jrs-hotfix runs resume " + id + "     (or runs undo " + id + ")");
      }
      out.println("  Choose 6 below to do this.");
    }
    while (true) {
      out.println();
      out.println("What do you want to do?");
      out.println("  1) Apply a hotfix");
      out.println("  2) Undo the latest hotfix");
      out.println("  3) Verify a hotfix package");
      out.println("  4) Check the server for customizations");
      out.println("  5) Show the installed build");
      out.println("  6) Recent runs and recovery");
      out.println("  7) Settings");
      out.println("  8) Hotfix a WAR or webapp directory");
      out.println("  9) Compare WARs, webapps, distributions or packages");
      out.println(" 10) Merges");
      out.println(" 11) Baselines");
      out.println("  q) Quit");
      out.println(
          "Each entry runs an ordinary command and prints it first. jrs-hotfix --help lists every"
              + " command.");
      Optional<String> choice = Prompter.line(out, "Choose [1-11, q]: ");
      if (choice.isEmpty() || choice.get().equalsIgnoreCase("q")) {
        return ExitCodes.SUCCESS;
      }
      switch (choice.get()) {
        case "1" -> {
          if (notPending()) {
            existingFile("Hotfix package (.zip)").ifPresent(p -> execute("apply", p));
          }
        }
        case "2" -> {
          if (notPending()) {
            // the plan names the hotfix it undoes, and asks; with nothing to undo it says so
            execute("rollback");
          }
        }
        case "3" -> {
          if (notPending()) {
            existingFile("Hotfix package (.zip)").ifPresent(p -> execute("verify", p));
          }
        }
        case "4" -> scan();
        case "5" -> execute("list");
        case "6" -> runs();
        case "7" -> settingsEntry();
        case "8" -> war();
        case "9" -> compare();
        case "10" -> merges();
        case "11" -> baselines();
        default -> out.println("Please type a number from 1 to 11, or q.");
      }
    }
  }

  // ---- jobs -------------------------------------------------------------------------------------

  /** True when no run is pending; otherwise prints the refusal. */
  private boolean notPending() {
    if (pendingRuns.get().isEmpty()) {
      return true;
    }
    out.println("  " + PENDING_REFUSAL);
    return false;
  }

  /**
   * The scan; when there is no baseline to compare with (exit 2) and no job is pending, the
   * vendor's WAR is asked for, added as the baseline, and the scan is run again.
   */
  private void scan() {
    if (execute("scan") != ExitCodes.PRECHECK_FAILED || !pendingRuns.get().isEmpty()) {
      return;
    }
    out.println();
    out.println(
        "The scan compares this server with the vendor's own files. Give the jasperserver-pro.war"
            + " this server was installed from (or the hotfix ZIP the message above asks for).");
    Optional<String> source = path("WAR or hotfix ZIP (Enter to go back)");
    if (source.isPresent() && execute("baseline", "add", source.get()) == ExitCodes.SUCCESS) {
      execute("scan");
    }
  }

  /**
   * The runs; a pending one is offered to finish or undo first, and old runs can be removed only
   * when none is pending.
   */
  private void runs() {
    execute("runs", "list");
    List<String> pending = pendingRuns.get();
    out.println();
    if (pending.isEmpty()) {
      out.println("  1) Show a run");
      out.println("  2) Remove old runs");
      switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
        case "1" -> showRun();
        case "2" -> prune();
        default -> {
          // back to the menu
        }
      }
      return;
    }
    String id = pending.get(0);
    out.println("  1) Finish job " + id);
    out.println("  2) Undo job " + id);
    out.println("  3) Show a run");
    switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
      case "1" -> execute("runs", "resume", id);
      case "2" -> execute("runs", "undo", id);
      case "3" -> showRun();
      default -> {
        // back to the menu
      }
    }
  }

  private void showRun() {
    text("Run id (as listed above)").ifPresent(id -> execute("runs", "show", id));
  }

  private void prune() {
    List<String> command = new ArrayList<>(List.of("runs", "prune"));
    Optional<String> days = text("Remove runs older than how many days (Enter for the default)");
    days.ifPresent(d -> command.addAll(List.of("--older-than", d)));
    if (Prompter.yes(out, "Also remove failed runs and their snapshots? [y/N] ", false)) {
      command.add("--include-failed");
    }
    execute(command.toArray(String[]::new));
  }

  /**
   * A WAR or a deployed or exploded webapp directory, worked on instead of the server: hotfixed
   * into a new WAR, a package verified against it, or checked for customizations. These commands
   * get the options as the operator gave them, not the server's home, so their home is the one the
   * command line would use: {@code --home} if given, else {@code jrs-hotfix} beside the input.
   */
  private void war() {
    Optional<String> war = existing("WAR or webapp directory (Enter to go back)");
    if (war.isEmpty()) {
      return;
    }
    out.println();
    out.println("  1) Hotfix it into a new WAR");
    out.println("  2) Verify a hotfix package against it");
    out.println("  3) Check it for customizations");
    switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
      case "1" -> {
        if (notPending()) {
          hotfixWar(war.get());
        }
      }
      case "2" ->
          existingFile("Hotfix package (.zip)")
              .ifPresent(p -> run(List.of("verify", p, "--war", war.get()), givenArgs));
      case "3" -> run(List.of("scan", "--war", war.get()), givenArgs);
      default -> {
        // back to the menu
      }
    }
  }

  private void hotfixWar(String war) {
    Optional<String> pkg = existingFile("Hotfix package (.zip)");
    if (pkg.isEmpty()) {
      return;
    }
    Optional<String> target = path("Write the hotfixed WAR to");
    if (target.isEmpty()) {
      return;
    }
    List<String> command =
        new ArrayList<>(List.of("apply", pkg.get(), "--war", war, "--out", target.get()));
    if (Prompter.yes(
        out,
        "Write the vendor's database configuration instead of this site's (--generic)? [y/N] ",
        false)) {
      command.add("--generic");
    }
    path("Installation tree for the buildomatic and samples files (Enter to leave them out)")
        .ifPresent(dir -> command.addAll(List.of("--install-out", dir)));
    run(command, givenArgs);
  }

  /** Two inputs, or three for base, mine and theirs; nothing is changed. */
  private void compare() {
    out.println(
        "Each input is a WAR, a webapp directory, the vendor's distribution, a hotfix ZIP, or"
            + " server. Give two to list what differs, three (base, mine, theirs) to merge.");
    List<String> command = new ArrayList<>(List.of("compare"));
    for (String which : List.of("First", "Second")) {
      Optional<String> input = path(which + " input");
      if (input.isEmpty()) {
        return;
      }
      command.add(input.get());
    }
    Optional<String> third = path("Third input (Enter to compare two)");
    if (third.isPresent()) {
      command.add(third.get());
      path("Directory to write the merged result to (Enter for the report only)")
          .ifPresent(dir -> command.addAll(List.of("--out", dir)));
    }
    execute(command.toArray(String[]::new));
  }

  /** The merges in the home, then one looked at, decided, prepared or discarded. */
  private void merges() {
    execute("merge", "list");
    out.println();
    out.println("  1) Show the files of a merge");
    out.println("  2) Show one file of a merge");
    out.println("  3) Decide one file of a merge");
    out.println("  4) Prepare a merge for a hotfix");
    out.println("  5) Discard a merge");
    switch (Prompter.line(out, "Choose [1-5]: ").orElse("")) {
      case "1" -> mergeId().ifPresent(id -> execute("merge", "status", id));
      case "2" -> {
        Optional<String> id = mergeId();
        if (id.isPresent()) {
          mergePath().ifPresent(p -> execute("merge", "show", id.get(), p));
        }
      }
      case "3" -> {
        if (notPending()) {
          resolve();
        }
      }
      case "4" -> {
        if (notPending()) {
          existingFile("Hotfix package (.zip)").ifPresent(p -> execute("merge", "prepare", p));
        }
      }
      case "5" -> {
        if (notPending()) {
          mergeId().ifPresent(id -> execute("merge", "discard", id));
        }
      }
      default -> {
        // back to the menu
      }
    }
  }

  private void resolve() {
    Optional<String> id = mergeId();
    if (id.isEmpty()) {
      return;
    }
    Optional<String> file = mergePath();
    if (file.isEmpty()) {
      return;
    }
    out.println("  1) Take the merged file");
    out.println("  2) Keep the server's file (mine)");
    out.println("  3) Take the hotfix's file (theirs)");
    Optional<String> decision =
        switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
          case "1" -> Optional.of("--merged");
          case "2" -> Optional.of("--mine");
          case "3" -> Optional.of("--theirs");
          default -> Optional.empty();
        };
    decision.ifPresent(d -> execute("merge", "resolve", id.get(), file.get(), d));
  }

  private Optional<String> mergeId() {
    return text("Merge id (as listed above)");
  }

  private Optional<String> mergePath() {
    return text("File (as merge status lists it)");
  }

  /** The baselines in the home, then one added or removed. */
  private void baselines() {
    execute("baseline", "list");
    out.println();
    out.println("  1) Add a baseline");
    out.println("  2) Remove a baseline");
    switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
      case "1" -> {
        if (notPending()) {
          existing("WAR, directory or hotfix ZIP")
              .ifPresent(source -> execute("baseline", "add", source));
        }
      }
      case "2" -> {
        if (notPending()) {
          text("Baseline id (as listed above)").ifPresent(id -> execute("baseline", "remove", id));
        }
      }
      default -> {
        // back to the menu
      }
    }
  }

  private void settingsEntry() {
    try {
      settingsMenu();
    } finally {
      settingsChanged.run();
    }
  }

  private void settingsMenu() {
    execute("settings", "show");
    out.println();
    out.println("  1) Change a value");
    out.println("  2) Detect again");
    switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
      case "1" -> {
        if (notPending()) {
          Optional<String> key = text("Setting to change (as listed above)");
          if (key.isEmpty()) {
            return;
          }
          text("New value for " + key.get())
              .ifPresent(value -> execute("settings", "set", key.get(), value));
        }
      }
      case "2" -> {
        if (notPending()) {
          detect();
        }
      }
      default -> {
        // back to the menu
      }
    }
  }

  // ---- helpers ----------------------------------------------------------------------------------

  /** The wizard, with the options as given, then the settings and the home are read again. */
  private void detect() {
    run(List.of("settings", "detect"), givenArgs);
    settingsChanged.run();
  }

  private int execute(String... command) {
    return run(List.of(command), globalArgs.get());
  }

  private int run(List<String> command, List<String> global) {
    List<String> args = new ArrayList<>(command);
    args.addAll(global);
    out.println();
    out.println("Running: jrs-hotfix " + String.join(" ", args));
    out.flush();
    int code = runner.apply(args.toArray(String[]::new));
    out.println(
        code == ExitCodes.SUCCESS
            ? "Done."
            : "Finished with exit code " + code + " (jrs-hotfix --docs explains the codes).");
    return code;
  }

  /** A non-empty answer, or empty when the operator pressed Enter or input ended. */
  private Optional<String> text(String prompt) {
    return Prompter.line(out, prompt + ": ").filter(s -> !s.isEmpty());
  }

  /** Like {@link #text}, for a prompt whose answer is a filesystem path. */
  private Optional<String> path(String prompt) {
    return Prompter.path(out, prompt + ": ").filter(s -> !s.isEmpty());
  }

  /** The path as the command will read it: a leading {@code ~} is the operator's home. */
  private static Path local(String typed) {
    return Path.of(UserPaths.expand(typed, Env.vars()));
  }

  /** An existing file, asked again until it exists; empty on Enter or at end of input. */
  private Optional<String> existingFile(String prompt) {
    while (true) {
      Optional<String> answer = path(prompt);
      if (answer.isEmpty() || Files.isRegularFile(local(answer.get()))) {
        return answer;
      }
      out.println("  no such file: " + answer.get() + " (press Enter to go back)");
    }
  }

  /** Like {@link #existingFile}, for a file or a directory. */
  private Optional<String> existing(String prompt) {
    while (true) {
      Optional<String> answer = path(prompt);
      if (answer.isEmpty() || Files.exists(local(answer.get()))) {
        return answer;
      }
      out.println("  no such file or directory: " + answer.get() + " (press Enter to go back)");
    }
  }
}

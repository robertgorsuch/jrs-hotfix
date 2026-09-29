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
 * command, with the home the menu resolved (except to the wizard, which places settings itself);
 * without settings the wizard ({@code settings detect}) runs before the menu is shown; while a run
 * is pending, every entry that would change the installation or the settings refuses and points at
 * entry 6; an empty answer where one is required returns to the menu; end of input quits with exit
 * 0; nothing is written by the menu itself.
 */
final class Menu {

  static final String PENDING_REFUSAL = "finish or undo the interrupted job first (entry 6)";

  private final PrintWriter out;
  private final Supplier<List<String>> globalArgs;
  private final List<String> detectArgs;
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
   * the home the menu resolved), {@code detectArgs} for {@code settings detect} (the options as the
   * operator gave them, so the wizard places new settings beside the installation it picks), and
   * {@code settingsChanged} run after the wizard or entry 7, so the header and the home are read
   * again.
   */
  Menu(
      PrintWriter out,
      Supplier<List<String>> globalArgs,
      List<String> detectArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Settings>> settings,
      Supplier<String> installedRelease,
      Runnable settingsChanged) {
    this.out = Objects.requireNonNull(out, "out");
    this.globalArgs = Objects.requireNonNull(globalArgs, "globalArgs");
    this.detectArgs = List.copyOf(detectArgs);
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
        out.println("    jrs-hotfix runs resume " + id + "     (or runs rollback " + id + ")");
      }
      out.println("  Choose 6 below to do this.");
    }
    while (true) {
      out.println();
      out.println("What do you want to do?");
      out.println("  1) Apply a hotfix");
      out.println("  2) Roll back a hotfix");
      out.println("  3) Verify a hotfix package");
      out.println("  4) List installed hotfixes");
      out.println("  5) Record a hotfix applied by hand");
      out.println("  6) Recent runs and recovery");
      out.println("  7) Settings");
      out.println("  q) Quit");
      out.println(
          "Each entry runs an ordinary command and prints it first. jrs-hotfix --help lists every"
              + " command.");
      Optional<String> choice = Prompter.line(out, "Choose [1-7, q]: ");
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
            rollback();
          }
        }
        case "3" -> {
          if (notPending()) {
            existingFile("Hotfix package (.zip)").ifPresent(p -> execute("verify", p));
          }
        }
        case "4" -> execute("list");
        case "5" -> {
          if (notPending()) {
            existingFile("Hotfix package (.zip)").ifPresent(p -> execute("record", p));
          }
        }
        case "6" -> runs();
        case "7" -> settingsEntry();
        default -> out.println("Please type a number from 1 to 7, or q.");
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

  private void rollback() {
    execute("list");
    Optional<String> id = text("Hotfix id to roll back");
    if (id.isEmpty()) {
      return;
    }
    // a cumulative hotfix applied later owns the same files; rollback is last-in-first-out, so
    // without --cascade a blocked rollback stops with exit 2
    if (Prompter.yes(out, "Also roll back the hotfixes applied after it, if any? [y/N] ", false)) {
      execute("rollback", id.get(), "--cascade");
    } else {
      execute("rollback", id.get());
    }
  }

  private void runs() {
    execute("runs", "list");
    List<String> pending = pendingRuns.get();
    if (pending.isEmpty()) {
      return;
    }
    String id = pending.get(0);
    out.println();
    out.println("  1) Finish job " + id);
    out.println("  2) Undo job " + id);
    switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
      case "1" -> execute("runs", "resume", id);
      case "2" -> execute("runs", "rollback", id);
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
    run(List.of("settings", "detect"), detectArgs);
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
}

package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.HomeResolver;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.platform.DefaultFileOps;
import com.jaspersoft.jrshotfix.platform.UserPaths;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
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
 * is pending in the server's home, every entry that would change the installation or the settings
 * refuses and points at entry 6, where it is finished, undone or closed (the entries on a WAR work
 * in the WAR's own home, where the commands refuse a run of their own with exit 8); an apply
 * refused because files of its merge wait for a decision, on the server (entry 1) or into a WAR
 * (entry 8), is followed through file by file in the home the merge waits in, and run again with
 * that merge and the same options; an empty answer where one is required returns to the menu; end
 * of input quits with exit 0; nothing is written by the menu itself.
 */
final class Menu {

  static final String PENDING_REFUSAL = "finish, undo or close the interrupted job first (entry 6)";

  private static final String KEEP_SUPERSEDED = "--keep-superseded";

  /** A merge of a package whose files still wait for the operator's decision. */
  record WaitingMerge(String id, List<String> files) {
    WaitingMerge {
      Objects.requireNonNull(id, "id");
      files = List.copyOf(files);
    }
  }

  private final PrintWriter out;
  private final Supplier<List<String>> globalArgs;
  private final List<String> givenArgs;
  private final Function<String[], Integer> runner;
  private final Supplier<List<String>> pendingRuns;
  private final Supplier<Optional<Settings>> settings;
  private final Supplier<String> installedRelease;
  private final Runnable settingsChanged;
  private final Function<Path, Optional<WaitingMerge>> waitingMerge;
  private final BiFunction<Path, Path, Optional<WaitingMerge>> waitingMergeIn;

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
        () -> {},
        pkg -> Optional.empty());
  }

  /**
   * As above, with the global options read from {@code globalArgs} before every command (they name
   * the home the menu resolved), {@code givenArgs} for {@code settings detect} and the commands on
   * a WAR (the options as the operator gave them, so the wizard places new settings beside the
   * installation it picks, and a WAR is worked on in the home the command line would use), and
   * {@code settingsChanged} run after the wizard or entry 7, so the header and the home are read
   * again; {@code waitingMerge} gives the merge of a package whose files still wait, if any.
   */
  Menu(
      PrintWriter out,
      Supplier<List<String>> globalArgs,
      List<String> givenArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Settings>> settings,
      Supplier<String> installedRelease,
      Runnable settingsChanged,
      Function<Path, Optional<WaitingMerge>> waitingMerge) {
    this(
        out,
        globalArgs,
        givenArgs,
        runner,
        pendingRuns,
        settings,
        installedRelease,
        settingsChanged,
        waitingMerge,
        (pkg, home) ->
            RootCommand.waitingIn(new Home(home), pkg, new DefaultFileOps(), Clock.systemUTC()));
  }

  /**
   * As above, with {@code waitingMergeIn} giving the merge of a package whose files still wait in a
   * WAR's own home, the second argument.
   */
  Menu(
      PrintWriter out,
      Supplier<List<String>> globalArgs,
      List<String> givenArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Settings>> settings,
      Supplier<String> installedRelease,
      Runnable settingsChanged,
      Function<Path, Optional<WaitingMerge>> waitingMerge,
      BiFunction<Path, Path, Optional<WaitingMerge>> waitingMergeIn) {
    this.out = Objects.requireNonNull(out, "out");
    this.globalArgs = Objects.requireNonNull(globalArgs, "globalArgs");
    this.givenArgs = List.copyOf(givenArgs);
    this.runner = Objects.requireNonNull(runner, "runner");
    this.pendingRuns = Objects.requireNonNull(pendingRuns, "pendingRuns");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.installedRelease = Objects.requireNonNull(installedRelease, "installedRelease");
    this.settingsChanged = Objects.requireNonNull(settingsChanged, "settingsChanged");
    this.waitingMerge = Objects.requireNonNull(waitingMerge, "waitingMerge");
    this.waitingMergeIn = Objects.requireNonNull(waitingMergeIn, "waitingMergeIn");
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
      out.println("! An earlier job was interrupted and must be finished, undone or closed first:");
      for (String id : pending) {
        out.println(
            "    jrs-hotfix runs resume " + id + "     (or runs undo, or runs abandon " + id + ")");
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
            existingFile("Hotfix package (.zip)").ifPresent(this::apply);
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
        case "10" -> merges(globalArgs.get(), Optional.empty());
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
   * The apply, with the options asked for; when it is refused (exit 2) because files of the
   * package's merge wait for a decision, each is shown and decided here, and the apply is run again
   * with that merge.
   */
  private void apply(String pkg) {
    List<String> options = applyOptions();
    List<String> command = new ArrayList<>(List.of("apply", pkg));
    command.addAll(options);
    if (run(command, globalArgs.get()) != ExitCodes.PRECHECK_FAILED) {
      return;
    }
    Optional<WaitingMerge> waiting = waitingMerge.apply(local(pkg));
    if (waiting.isEmpty() || !decideAll(waiting.get(), globalArgs.get(), "entry 10")) {
      return;
    }
    if (Prompter.yes(out, "Every file is decided. Apply " + pkg + " now? [Y/n] ", true)) {
      List<String> again = new ArrayList<>(List.of("apply", pkg, "--merge", waiting.get().id()));
      if (options.contains(KEEP_SUPERSEDED)) {
        again.add(KEEP_SUPERSEDED);
      }
      run(again, globalArgs.get());
    }
  }

  /**
   * Lists the files of {@code waiting} and, when the operator agrees, decides each in turn with
   * {@code args} (the server's home, or a WAR's); true when every file is decided. When the
   * operator stops, says where the merge is continued: {@code where}.
   */
  private boolean decideAll(WaitingMerge waiting, List<String> args, String where) {
    List<String> files = waiting.files();
    out.println();
    out.println(
        files.size()
            + " file(s) changed by both this site and the hotfix wait for your decision in merge "
            + waiting.id()
            + ":");
    files.forEach(f -> out.println("    " + f));
    if (!Prompter.yes(out, "Decide them now? [Y/n] ", true)) {
      return false;
    }
    for (String file : files) {
      if (!decide(waiting.id(), file, args)) {
        out.println("  the merge keeps its decisions so far; " + where + " continues it");
        return false;
      }
    }
    return true;
  }

  /**
   * The apply options most operators leave as they are, asked for only on request: {@code
   * --keep-superseded} and {@code --on-conflict}.
   */
  private List<String> applyOptions() {
    List<String> options = new ArrayList<>();
    out.println();
    out.println("Before applying, two choices most people leave as they are:");
    out.println(
        "  - Older versions of libraries this hotfix replaces are deleted from WEB-INF/lib.");
    out.println("  - When this site and the hotfix both changed the same setting, you are asked.");
    if (!Prompter.yes(out, "Change either? [y/N] ", false)) {
      return options;
    }
    if (Prompter.yes(
        out, "Keep the older library versions instead of deleting them? [y/N] ", false)) {
      options.add(KEEP_SUPERSEDED);
    }
    onConflict().ifPresent(rule -> options.addAll(List.of("--on-conflict", rule)));
    return options;
  }

  /**
   * What to do with a setting both this site and the hotfix changed ({@code --on-conflict}), asked
   * until valid; empty for the default, the setting {@code merge.onConflict}.
   */
  private Optional<String> onConflict() {
    out.println(
        "When this site and the hotfix both changed the same setting in a .properties file:");
    out.println("  1) Ask me: the file waits for my decision (the usual choice)");
    out.println("  2) Keep this site's value (the hotfix's is kept beside it as a comment)");
    out.println("  3) Take the hotfix's value (this site's is kept beside it as a comment)");
    out.println("  4) Stop with an error, for a scripted run");
    while (true) {
      Optional<String> answer = text("Choose [1-4, Enter for the default]");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      Optional<String> rule =
          switch (answer.get()) {
            case "1" -> Optional.of("ask");
            case "2" -> Optional.of("mine");
            case "3" -> Optional.of("theirs");
            case "4" -> Optional.of("fail");
            default -> Optional.empty();
          };
      if (rule.isPresent()) {
        return rule;
      }
      out.println("  please type a number from 1 to 4, or press Enter");
    }
  }

  /**
   * One file of a merge, asked about until it is decided; false when the operator stops. The
   * commands get {@code args}: the server's home, or the home of a WAR.
   */
  private boolean decide(String id, String file, List<String> args) {
    while (true) {
      out.println();
      out.println("File " + file + " in merge " + id);
      out.println("  1) Show what this site and the hotfix changed");
      out.println("  2) Take the merged file");
      out.println("  3) Keep the server's file (mine)");
      out.println("  4) Take the hotfix's file (theirs)");
      out.println("  5) Install a file you merged yourself");
      List<String> decision;
      switch (Prompter.line(out, "Choose [1-5, Enter to stop]: ").orElse("")) {
        case "1" -> {
          run(List.of("merge", "show", id, file), args);
          continue;
        }
        case "2" -> decision = List.of("--merged");
        case "3" -> decision = List.of("--mine");
        case "4" -> decision = List.of("--theirs");
        case "5" -> {
          Optional<String> own = existingFile("Your merged file (Enter to choose again)");
          if (own.isEmpty()) {
            continue;
          }
          decision = List.of("--merged", own.get());
        }
        case "" -> {
          return false;
        }
        default -> {
          out.println("Please type a number from 1 to 5, or press Enter to stop.");
          continue;
        }
      }
      List<String> command = new ArrayList<>(List.of("merge", "resolve", id, file));
      command.addAll(decision);
      // a merged file that fails its checks is refused; the operator chooses again
      if (run(command, args) == ExitCodes.SUCCESS) {
        return true;
      }
    }
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
        "The scan compares this server with the vendor's own files. Give the vendor's distribution"
            + " ZIP this server was installed from (recommended: it covers buildomatic and samples"
            + " too), its jasperserver-pro.war, or the hotfix ZIP the message above asks for.");
    Optional<String> source = path("Distribution ZIP, WAR or hotfix ZIP (Enter to go back)");
    if (source.isPresent() && execute("baseline", "add", source.get()) == ExitCodes.SUCCESS) {
      execute("scan");
    }
  }

  /**
   * The runs; a pending one is offered to finish, undo or close first, and old runs can be removed
   * only when none is pending. Closing ({@code runs abandon}) keeps the server as it is: the
   * command shows what the run left and asks before it records anything.
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
    out.println(
        "  3) Close job " + id + " without finishing or undoing it (keep the server as is)");
    out.println("  4) Show a run");
    switch (Prompter.line(out, "Choose [1-4]: ").orElse("")) {
      case "1" -> execute("runs", "resume", id);
      case "2" -> execute("runs", "undo", id);
      case "3" -> execute("runs", "abandon", id);
      case "4" -> showRun();
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
   * into a new WAR, a package verified against it, checked for customizations, or its merges. These
   * commands get the options as the operator gave them, not the server's home, so their home is the
   * one the command line would use: {@code --home} if given, else {@code jrs-hotfix} beside the
   * input.
   */
  private void war() {
    Optional<String> war = existing("WAR or webapp directory (Enter to go back)");
    if (war.isEmpty()) {
      return;
    }
    if (!warHomeGiven()) {
      offerServerBaselines(war.get(), warArgs(war.get()));
    }
    out.println();
    out.println("  1) Hotfix it into a new WAR");
    out.println("  2) Verify a hotfix package against it");
    out.println("  3) Check it for customizations");
    out.println("  4) Its merges");
    out.println("  5) Its baselines");
    switch (Prompter.line(out, "Choose [1-5]: ").orElse("")) {
        // a WAR has its own home: a run interrupted on the server does not block it, and one
        // interrupted in the WAR's home is refused by the command itself (exit 8)
      case "1" -> hotfixWar(war.get());
      case "2" ->
          existingFile("Hotfix package (.zip)")
              .ifPresent(p -> run(List.of("verify", p, "--war", war.get()), givenArgs));
      case "3" -> run(List.of("scan", "--war", war.get()), givenArgs);
      case "4" -> merges(warArgs(war.get()), Optional.of(war.get()));
      case "5" -> baselines(warArgs(war.get()), Optional.of(war.get()));
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
    List<String> options = applyOptions();
    List<String> first = new ArrayList<>(command);
    first.addAll(options);
    if (run(first, givenArgs) != ExitCodes.PRECHECK_FAILED) {
      return;
    }
    // as on the server: the WAR's merge waits in the WAR's own home, decided there
    Optional<WaitingMerge> waiting = waitingMergeIn.apply(local(pkg.get()), warHome(war));
    if (waiting.isEmpty() || !decideAll(waiting.get(), warArgs(war), "entry 8, then 4,")) {
      return;
    }
    if (Prompter.yes(out, "Every file is decided. Hotfix " + war + " now? [Y/n] ", true)) {
      List<String> again = new ArrayList<>(command);
      again.addAll(List.of("--merge", waiting.get().id()));
      if (options.contains(KEEP_SUPERSEDED)) {
        again.add(KEEP_SUPERSEDED);
      }
      run(again, givenArgs);
    }
  }

  /**
   * The home a WAR is worked on in, as {@code apply --war} chooses it: {@code --home} when the
   * operator gave one, else the environment's, else {@code jrs-hotfix} beside the WAR.
   */
  private Path warHome(String war) {
    int at = givenArgs.indexOf("--home");
    if (at >= 0 && at + 1 < givenArgs.size()) {
      return local(givenArgs.get(at + 1));
    }
    String env = Env.vars().get(HomeResolver.ENV);
    if (env != null && !env.isBlank()) {
      return local(env);
    }
    return Bootstrap.besideWar(local(war)).root();
  }

  /**
   * The options for the merges of a WAR: the home {@code apply --war} uses, named with {@code
   * --home} when the operator gave none, so the merge commands, which take no {@code --war}, find
   * it.
   */
  private List<String> warArgs(String war) {
    if (warHomeGiven()) {
      return givenArgs;
    }
    List<String> args = new ArrayList<>(givenArgs);
    args.addAll(List.of("--home", Bootstrap.besideWar(local(war)).root().toString()));
    return args;
  }

  /** True when the operator named the home, with {@code --home} or the environment. */
  private boolean warHomeGiven() {
    String env = Env.vars().get(HomeResolver.ENV);
    return givenArgs.contains("--home") || (env != null && !env.isBlank());
  }

  /** Two inputs, or three for base, mine and theirs, or one file of them; nothing is changed. */
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
    third.ifPresent(command::add);
    Optional<String> show =
        text("One file to show the differences of (Enter for the whole report)");
    if (show.isPresent()) {
      command.addAll(List.of("--show", show.get()));
    } else if (third.isPresent()) {
      path("Directory to write the merged result to (Enter for the report only)")
          .ifPresent(dir -> command.addAll(List.of("--out", dir)));
    }
    execute(command.toArray(String[]::new));
  }

  /**
   * The merges in a home, then one looked at, decided, prepared or discarded: the server's, with
   * {@code war} empty, or a WAR's, whose merges are prepared with {@code --war}.
   */
  private void merges(List<String> args, Optional<String> war) {
    boolean none =
        war.isPresent()
            && !warHomeGiven()
            && !Files.isRegularFile(Bootstrap.besideWar(local(war.get())).settingsFile());
    if (none) {
      // the home beside the WAR is made by its first merge or apply
      out.println();
      out.println("  no merges for this WAR yet");
    } else {
      run(List.of("merge", "list"), args);
    }
    out.println();
    out.println("  1) Show the files of a merge");
    out.println("  2) Show one file of a merge");
    out.println("  3) Decide one file of a merge");
    out.println("  4) Prepare a merge for a hotfix");
    out.println("  5) Discard a merge");
    switch (Prompter.line(out, "Choose [1-5]: ").orElse("")) {
      case "1" -> mergeId().ifPresent(id -> run(List.of("merge", "status", id), args));
      case "2" -> {
        Optional<String> id = mergeId();
        if (id.isPresent()) {
          mergePath().ifPresent(p -> run(List.of("merge", "show", id.get(), p), args));
        }
      }
      case "3" -> {
        if (war.isPresent() || notPending()) {
          Optional<String> id = mergeId();
          if (id.isPresent()) {
            mergePath().ifPresent(p -> decide(id.get(), p, args));
          }
        }
      }
      case "4" -> {
        if (war.isPresent() || notPending()) {
          prepare(args, war);
        }
      }
      case "5" -> {
        if (war.isPresent() || notPending()) {
          mergeId().ifPresent(id -> run(List.of("merge", "discard", id), args));
        }
      }
      default -> {
        // back to the menu
      }
    }
  }

  private void prepare(List<String> args, Optional<String> war) {
    Optional<String> pkg = existingFile("Hotfix package (.zip)");
    if (pkg.isEmpty()) {
      return;
    }
    List<String> command = new ArrayList<>(List.of("merge", "prepare", pkg.get()));
    war.ifPresent(w -> command.addAll(List.of("--war", w)));
    onConflict().ifPresent(rule -> command.addAll(List.of("--on-conflict", rule)));
    run(command, args);
  }

  private Optional<String> mergeId() {
    return text("Merge id (as listed above)");
  }

  private Optional<String> mergePath() {
    return text("File (as merge status lists it)");
  }

  /** The baselines in the home, then one added or removed. */
  private void baselines() {
    baselines(globalArgs.get(), Optional.empty());
  }

  /**
   * The baselines in a home, then one added or removed: the server's, with {@code war} empty, or
   * the home of a WAR, which can also copy the server's ({@code baseline import}). A run
   * interrupted on the server does not hold up a WAR's home, whose commands refuse their own with
   * exit 8.
   */
  private void baselines(List<String> args, Optional<String> war) {
    Optional<String> server = war.flatMap(w -> serverHome());
    if (war.isEmpty() || hasBaselines(war)) {
      run(List.of("baseline", "list"), args);
    } else {
      out.println();
      out.println("  no baselines in this WAR's home yet");
    }
    out.println();
    out.println("  1) Add a baseline");
    out.println("  2) Remove a baseline");
    if (server.isPresent()) {
      out.println("  3) Copy the server's baselines");
    }
    String choices = server.isPresent() ? "[1-3]" : "[1-2]";
    switch (Prompter.line(out, "Choose " + choices + ": ").orElse("")) {
      case "1" -> {
        if (war.isPresent() || notPending()) {
          existing("WAR, directory or hotfix ZIP")
              .ifPresent(source -> run(List.of("baseline", "add", source), args));
        }
      }
      case "2" -> {
        if (war.isPresent() || notPending()) {
          text("Baseline id (as listed above)")
              .ifPresent(id -> run(List.of("baseline", "remove", id), args));
        }
      }
      case "3" -> server.ifPresent(home -> run(List.of("baseline", "import", home), args));
      default -> {
        // back to the menu
      }
    }
  }

  /**
   * Offers to copy the server's baselines into the home of {@code war} when that home has none and
   * the server's has some: without one, a WAR's changes cannot be told from the vendor's, and an
   * apply replaces them. Asked only when the WAR's home is the one beside it.
   */
  private void offerServerBaselines(String war, List<String> args) {
    Optional<String> server = serverHome();
    if (server.isEmpty() || hasBaselines(Optional.of(war))) {
      return;
    }
    Path serverBaselines = new Home(Path.of(server.get())).baselines();
    if (!holdsBaseline(serverBaselines)) {
      return;
    }
    out.println();
    out.println(
        "This WAR's home has no baselines, so its changes cannot be told from the vendor's and an"
            + " apply would replace them. The server's home has baselines.");
    if (Prompter.yes(out, "Copy the server's baselines into this WAR's home? [Y/n] ", true)) {
      run(List.of("baseline", "import", server.get()), args);
    }
  }

  /**
   * True when the home of {@code war} (beside it, as {@link #warArgs} names it) holds a baseline.
   */
  private boolean hasBaselines(Optional<String> war) {
    if (war.isEmpty() || warHomeGiven()) {
      return true; // the operator named the home: list what it holds
    }
    return holdsBaseline(Bootstrap.besideWar(local(war.get())).baselines());
  }

  private static boolean holdsBaseline(Path baselines) {
    if (!Files.isDirectory(baselines)) {
      return false;
    }
    try (var dirs = Files.list(baselines)) {
      return dirs.anyMatch(d -> Files.isRegularFile(d.resolve("manifest.json")));
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /** The server's home, as the global options name it once there are settings; else empty. */
  private Optional<String> serverHome() {
    List<String> global = globalArgs.get();
    int at = global.indexOf("--home");
    return at >= 0 && at + 1 < global.size() ? Optional.of(global.get(at + 1)) : Optional.empty();
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

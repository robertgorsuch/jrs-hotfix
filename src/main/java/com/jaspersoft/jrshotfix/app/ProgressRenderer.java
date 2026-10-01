package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepFailure;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.redact.Redactor;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns the engine's {@link Event} stream into terminal output while a plan runs (spec §6.3,
 * §13.2). Text mode prints a phase header when a phase starts and one line per finished step
 * ({@code ✔ NN title 0.4s}, {@code ✖} with the cause indented, {@code ↻} retries, {@code ↩} rolled
 * back, {@code -} skipped, log lines indented and dimmed), then an outcome block naming step,
 * cause, affected paths, backups and the next action. Invariants: icons fall back to ASCII words
 * ({@code OK}, {@code FAIL}, {@code RETRY}, {@code UNDO}, {@code SKIP}) whenever colour is off so
 * the meaning survives any code page; every line is redacted; the renderer never throws back into
 * the runner.
 */
final class ProgressRenderer implements EventSink {

  private final PrintWriter out;
  private final Ansi ansi;
  private final Redactor redactor;
  private final Map<String, Integer> positions = new HashMap<>();
  private final Map<String, Step> steps = new HashMap<>();
  private String phase = "";
  private Optional<StepFailure> lastFailure = Optional.empty();
  private Optional<String> lastFailedStep = Optional.empty();

  ProgressRenderer(Plan plan, PrintWriter out, Ansi ansi, Redactor redactor) {
    this.out = Objects.requireNonNull(out, "out");
    this.ansi = Objects.requireNonNull(ansi, "ansi");
    this.redactor = Objects.requireNonNull(redactor, "redactor");
    List<Step> all = plan.steps();
    for (int i = 0; i < all.size(); i++) {
      positions.put(all.get(i).id(), i);
      steps.put(all.get(i).id(), all.get(i));
    }
  }

  @Override
  public void emit(Event event) {
    switch (event) {
      case Event.PlanCreated e -> {}
      case Event.StepPending e -> phaseHeader(e.phase());
      case Event.StepRunning e -> phaseHeader(e.phase());
      case Event.StepSucceeded e -> line(Icon.OK, e.stepId(), elapsed(e.elapsedMillis()));
      case Event.StepFailed e -> {
        lastFailure = Optional.of(e.failure());
        lastFailedStep = e.stepId();
        line(Icon.FAIL, e.stepId(), "");
        indented(e.failure().cause());
      }
      case Event.StepRetry e ->
          line(
              Icon.RETRY,
              e.stepId(),
              "retry "
                  + e.attempt()
                  + "/"
                  + e.maxAttempts()
                  + " in "
                  + elapsed(e.delayMillis())
                  + ": "
                  + e.cause());
      case Event.StepRolledBack e -> line(Icon.UNDO, e.stepId(), elapsed(e.elapsedMillis()));
      case Event.StepRollbackFailed e -> {
        line(Icon.FAIL, e.stepId(), "rollback failed");
        indented(e.cause());
      }
      case Event.StepSkipped e -> line(Icon.SKIP, e.stepId(), "skipped: " + e.reason());
      case Event.Log e -> indented(e.level().name().toLowerCase(Locale.ROOT) + "  " + e.message());
      case Event.RunSucceeded e -> {}
      case Event.RunFailed e -> {}
      case Event.RunCancelled e -> {}
      case Event.RunRolledBack e -> {}
    }
  }

  /** Prints the outcome block. */
  void outcome(String runId, RunOutcome outcome) {
    List<String[]> rows = new ArrayList<>();
    String headline =
        switch (outcome) {
          case RunOutcome.Succeeded s -> icon(Icon.OK) + " run " + runId + " succeeded";
          case RunOutcome.RolledBack r -> {
            rows.add(new String[] {"cause", r.cause()});
            yield icon(Icon.FAIL)
                + " run "
                + runId
                + " failed; rolled back to phase "
                + r.rolledBackToPhase();
          }
          case RunOutcome.Failed f -> {
            rows.add(new String[] {"cause", f.cause()});
            rows.add(new String[] {"backup", paths(f.backups())});
            rows.add(new String[] {"next", f.nextAction()});
            yield icon(Icon.FAIL)
                + " run "
                + runId
                + (f.rollbackIncomplete()
                    ? " failed; rollback incomplete, manual action required"
                    : " failed before any change");
          }
          case RunOutcome.Cancelled c -> {
            rows.add(new String[] {"cause", "cancelled: " + c.reason()});
            yield icon(Icon.FAIL) + " run " + runId + " cancelled and rolled back";
          }
          case RunOutcome.PrecheckFailed p -> {
            rows.add(new String[] {"step", describe(Optional.of(p.stepId()))});
            rows.add(new String[] {"cause", p.message()});
            rows.add(new String[] {"next", p.remediation()});
            yield icon(Icon.FAIL) + " run " + runId + " stopped at a precheck; nothing changed";
          }
        };
    List<String[]> ordered = new ArrayList<>();
    if (lastFailure.isPresent() && !(outcome instanceof RunOutcome.Succeeded)) {
      StepFailure f = lastFailure.get();
      ordered.add(new String[] {"step", describe(lastFailedStep)});
      if (!has(rows, "cause")) {
        ordered.add(new String[] {"cause", f.cause()});
      }
      if (!f.affectedPaths().isEmpty() || !f.affectedUris().isEmpty()) {
        ordered.add(new String[] {"affected", affected(f)});
      }
      if (!has(rows, "backup") && !f.backups().isEmpty()) {
        ordered.add(new String[] {"backup", paths(f.backups())});
      }
      if (!has(rows, "next") && !f.nextAction().isBlank()) {
        ordered.add(new String[] {"next", f.nextAction()});
      }
      rows.removeIf(r -> r[0].equals("step"));
    }
    ordered.addAll(rows);
    println("");
    println(headline);
    TextTable table = new TextTable(Terminal.width(Env.vars()));
    for (String[] row : ordered) {
      table.row("  " + row[0], row[1]);
    }
    table.lines().forEach(this::println);
  }

  // ---- text helpers ---------------------------------------------------------------------------

  private enum Icon {
    OK,
    FAIL,
    RETRY,
    UNDO,
    SKIP
  }

  /**
   * Review 3.5: colour and glyphs are separate decisions. A code page that cannot carry the tick
   * and arrows gets the ASCII word, even in a terminal that does colour; a colourless terminal that
   * can carry them still gets them.
   */
  private String icon(Icon icon) {
    if (!ansi.unicode()) {
      String word = String.format(Locale.ROOT, "%-5s", icon.name());
      return ansi.enabled() ? colour(icon) + word + Ansi.RESET : word;
    }
    String glyph =
        switch (icon) {
          case OK -> "✔";
          case FAIL -> "✖";
          case RETRY -> "↻";
          case UNDO -> "↩";
          case SKIP -> "-";
        };
    String body = ansi.enabled() ? colour(icon) + glyph + Ansi.RESET : glyph;
    return body + "    ";
  }

  private static String colour(Icon icon) {
    return switch (icon) {
      case OK -> Ansi.GREEN;
      case FAIL -> Ansi.RED;
      case RETRY, UNDO -> Ansi.YELLOW;
      case SKIP -> Ansi.DIM;
    };
  }

  private void phaseHeader(String phase) {
    if (!phase.equals(this.phase)) {
      this.phase = phase;
      println("  " + phase);
    }
  }

  private void line(Icon icon, Optional<String> stepId, String trailer) {
    String label = describe(stepId);
    String text = icon(icon) + " " + label;
    if (!trailer.isEmpty()) {
      text = text + "   " + ansi.dim(trailer);
    }
    println(text);
  }

  private void indented(String text) {
    for (String part : text.lines().toList()) {
      println(ansi.dim("        " + part));
    }
  }

  private String describe(Optional<String> stepId) {
    if (stepId.isEmpty()) {
      return "run";
    }
    String id = stepId.get();
    Integer pos = positions.get(id);
    Step step = steps.get(id);
    if (pos == null || step == null) {
      return id;
    }
    return PlanPrinter.number(pos) + "  " + step.title();
  }

  private static boolean has(List<String[]> rows, String label) {
    return rows.stream().anyMatch(r -> r[0].equals(label));
  }

  private static String elapsed(long millis) {
    return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
  }

  private static String paths(List<Path> paths) {
    return paths.isEmpty()
        ? "none"
        : String.join(", ", paths.stream().map(Path::toString).toList());
  }

  private static String affected(StepFailure f) {
    List<String> all = new ArrayList<>();
    f.affectedPaths().stream().map(Path::toString).forEach(all::add);
    f.affectedUris().stream().map(URI::toString).forEach(all::add);
    return String.join(", ", all);
  }

  private void println(String line) {
    out.println(redactor.redact(line));
    out.flush();
  }
}

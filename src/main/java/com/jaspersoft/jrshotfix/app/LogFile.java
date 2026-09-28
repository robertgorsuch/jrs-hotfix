package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.platform.Diag;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * The per-run text log, in place of jrsctl's logback setup. Invariants: one file per run, {@code
 * home.logFile(runId)}, opened for append in UTF-8 with its parent directories created; while it is
 * open the platform's {@link Diag} sink writes into it, and closing it resets that sink; every line
 * is flushed as it is written, so a crash leaves the log complete up to the last event; nothing
 * secret is written here because every event reaching {@link #sink} has already passed the
 * redacting sink.
 */
final class LogFile implements AutoCloseable {

  private final PrintWriter writer;

  private LogFile(PrintWriter writer) {
    this.writer = writer;
  }

  /** Opens (or continues) the run's log and routes platform diagnostics into it. */
  static LogFile open(Home home, String runId) {
    Path file = home.logFile(runId);
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      PrintWriter w =
          new PrintWriter(
              Files.newBufferedWriter(
                  file,
                  StandardCharsets.UTF_8,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.APPEND,
                  StandardOpenOption.WRITE),
              true);
      LogFile log = new LogFile(w);
      Diag.install((level, message) -> log.line(level.name() + " " + message));
      return log;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot open the run log " + file, e);
    }
  }

  /** A sink that writes one line per event: its type, its step id when it has one, its message. */
  EventSink sink() {
    return event -> line(describe(event));
  }

  PrintWriter writer() {
    return writer;
  }

  synchronized void line(String text) {
    writer.println(Instant.now() + " " + text);
    writer.flush();
  }

  static String describe(Event event) {
    String step = event.stepId().map(s -> " [" + s + "]").orElse("");
    return event.type() + step + " " + message(event);
  }

  private static String message(Event event) {
    return switch (event) {
      case Event.PlanCreated e -> e.planId() + " " + e.fingerprint();
      case Event.StepPending e -> e.title();
      case Event.StepRunning e -> e.title();
      case Event.StepRetry e ->
          "attempt " + e.attempt() + "/" + e.maxAttempts() + " after " + e.cause();
      case Event.StepSucceeded e -> e.elapsedMillis() + " ms";
      case Event.StepFailed e -> e.failure().cause();
      case Event.StepSkipped e -> e.reason();
      case Event.StepRolledBack e -> e.elapsedMillis() + " ms";
      case Event.StepRollbackFailed e -> e.cause() + " backups " + e.backups();
      case Event.Log e -> e.level() + " " + e.message();
      case Event.RunSucceeded e -> e.elapsedMillis() + " ms";
      case Event.RunFailed e -> e.cause() + "; next: " + e.nextAction();
      case Event.RunCancelled e -> e.detail();
      case Event.RunRolledBack e -> e.cause() + "; rolled back to " + e.rolledBackToPhase();
    };
  }

  @Override
  public void close() {
    Diag.reset();
    writer.close();
  }
}

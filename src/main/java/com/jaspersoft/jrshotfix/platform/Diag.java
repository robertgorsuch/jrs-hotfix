package com.jaspersoft.jrshotfix.platform;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The platform layer's diagnostic sink, in place of a logging framework. Invariants: the default
 * sink discards; the front end installs one that writes the run log; a message is formatted only
 * when a sink is installed; {@code {}} placeholders are filled in order, slf4j style, and a
 * trailing Throwable argument is appended as its toString.
 */
public final class Diag {
  public enum Level {
    DEBUG,
    INFO,
    WARN
  }

  private static volatile BiConsumer<Level, String> sink = (level, message) -> {};

  private Diag() {}

  public static void install(BiConsumer<Level, String> newSink) {
    sink = Objects.requireNonNull(newSink, "newSink");
  }

  public static void reset() {
    sink = (level, message) -> {};
  }

  public static void debug(String message, Object... args) {
    emit(Level.DEBUG, message, args);
  }

  public static void info(String message, Object... args) {
    emit(Level.INFO, message, args);
  }

  public static void warn(String message, Object... args) {
    emit(Level.WARN, message, args);
  }

  private static void emit(Level level, String message, Object... args) {
    StringBuilder out = new StringBuilder();
    int argIndex = 0;
    int from = 0;
    int at;
    while ((at = message.indexOf("{}", from)) >= 0 && argIndex < args.length) {
      out.append(message, from, at).append(args[argIndex++]);
      from = at + 2;
    }
    out.append(message.substring(from));
    if (argIndex < args.length && args[args.length - 1] instanceof Throwable t) {
      out.append(": ").append(t);
    }
    sink.accept(level, out.toString());
  }
}

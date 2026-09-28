package com.jaspersoft.jrshotfix.event;

import com.jaspersoft.jrshotfix.platform.Diag;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Synchronous fan-out of {@link Event}s to subscribers (spec §6.3). Invariants: subscribers receive
 * every event in subscription order on the emitting thread; a subscriber that throws is logged and
 * skipped for that event but keeps its subscription and never fails the emitter; subscribing and
 * unsubscribing are safe while an emit is in progress (the emit finishes with the subscriber list
 * it started with).
 */
public final class EventBus implements EventSink {

  private final List<Registration> subscribers = new CopyOnWriteArrayList<>();

  /**
   * Registers {@code sink}; closing the returned subscription removes exactly this registration.
   */
  public Subscription subscribe(EventSink sink) {
    Objects.requireNonNull(sink, "sink");
    Registration r = new Registration(sink);
    subscribers.add(r);
    return () -> subscribers.remove(r);
  }

  /** Removes every registration of {@code sink}; a no-op when it is not subscribed. */
  // a sink is usually a lambda, which has no equals of its own: identity is what was registered
  @SuppressWarnings("ReferenceEquality")
  public void unsubscribe(EventSink sink) {
    subscribers.removeIf(r -> r.sink == sink);
  }

  public int subscriberCount() {
    return subscribers.size();
  }

  @Override
  public void emit(Event event) {
    Objects.requireNonNull(event, "event");
    for (Registration r : subscribers) {
      try {
        r.sink.emit(event);
      } catch (RuntimeException e) {
        Diag.warn(
            "event subscriber {} failed on {} for run {}; dropping the event for it",
            r.sink,
            event.type(),
            event.runId(),
            e);
      }
    }
  }

  /** Handle returned by {@link #subscribe}; {@link #close()} is idempotent. */
  @FunctionalInterface
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  /** Identity wrapper so the same sink can be registered twice and removed individually. */
  private static final class Registration {
    final EventSink sink;

    Registration(EventSink sink) {
      this.sink = sink;
    }
  }
}

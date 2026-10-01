package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import java.util.Optional;

/**
 * The base of the hotfix plans' own steps: the runtime, the step's input ({@link ApplyInput} for an
 * apply, {@link RollbackSteps.Input} for a rollback), and the lines a step writes to the run's
 * event stream. It decides nothing about the step: whether it mutates, and what its execute and
 * compensate do, stay the step's own.
 */
abstract class HotfixStep<I> implements Step {
  final HotfixRuntime rt;
  final I in;

  HotfixStep(HotfixRuntime rt, I in) {
    this.rt = rt;
    this.in = in;
  }

  /** One line of this step in the run's event stream. */
  void log(Context ctx, EventSink out, Event.Log.Level level, String message) {
    out.emit(
        new Event.Log(
            rt.clock().instant(), ctx.runId(), Optional.of(id()), phase(), level, message));
  }

  /** An audit line: {@code audit <kind>: <text>}, at INFO. */
  void audit(Context ctx, EventSink out, String kind, String text) {
    log(ctx, out, Event.Log.Level.INFO, "audit " + kind + ": " + text);
  }
}

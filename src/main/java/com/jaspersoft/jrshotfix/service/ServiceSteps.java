package com.jaspersoft.jrshotfix.service;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.RetryPolicy;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepFailure;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.platform.ServiceControlException;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * The service stop, start and wait steps of every plan that touches the service: hotfix apply and
 * rollback (spec §8.2 steps 6 and 10, §8.3), upgrade, reconcile and rollback (spec §10.2 steps 4,
 * 9, 11) and vendor export and import (spec §7.3, §7.4). One implementation, since every copy that
 * preceded it drifted (review finding 1.13 for hotfix and upgrade, issue #43 for the vendor
 * strategy's copy, which recorded its stop too late and waited out a refused command). Invariants:
 * stop and start consult the controller's state first and leave a service already in the wanted
 * state alone; a state the controller cannot determine is refused at precheck, before anything is
 * stopped; a stop writes a run-scoped marker <em>before</em> it acts, so its compensation starts
 * the service exactly when this run tried to stop it, including a stop that went wrong half-way,
 * and never when the operator had it stopped; a start's compensation stops it again;
 * wait-for-server polls the server through {@link ServiceRuntime#probe()} with the spec §6.5
 * backoff under a ten-minute cap and mutates nothing. A platform that refuses a command outright
 * ({@link ServiceControlException}) fails at once with the rights remediation rather than being
 * waited out (spec §5.3).
 */
public final class ServiceSteps {

  public static final String STOP = "stop-service";
  public static final String START = "start-service";
  public static final String WAIT = "wait-for-server";
  public static final Duration WAIT_CAP = Duration.ofMinutes(10);

  /** How long the undo of a stop lets one request wait to tell a live server from an ending JVM. */
  static final Duration ENDING_JVM_PATIENCE = Duration.ofSeconds(30);

  /** The platform refused the service command outright; waiting would not have helped. */
  public static final String RIGHTS_REMEDIATION =
      "run jrs-hotfix with the rights the service manager demands (see the message), then"
          + " re-run; nothing was changed";

  private static final String CONFIG_REMEDIATION =
      "check service.kind, service.name and service.scriptPath with `jrs-hotfix settings show`";

  private ServiceSteps() {}

  public static Step stop(ServiceRuntime rt, String phase, String id) {
    return new StopService(rt, phase, id);
  }

  public static Step start(ServiceRuntime rt, String phase, String id) {
    return new StartService(rt, phase, id);
  }

  public static Step waitForServer(ServiceRuntime rt, String phase, String id) {
    return new WaitForServer(rt, phase, id);
  }

  /** Precheck shared by stop and start: the service must be identifiable and its state known. */
  public static CheckResult controllerCheck(ServiceRuntime rt) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.UNKNOWN) {
        return CheckResult.fail(
            "service state cannot be determined (" + controller.describe() + ")",
            "check service.kind, service.name and service.scriptPath with `jrs-hotfix settings"
                + " show`; the manual kind needs an interactive session");
      }
      return CheckResult.pass();
    } catch (RuntimeException e) {
      return CheckResult.fail("cannot query the service: " + describe(e), CONFIG_REMEDIATION);
    }
  }

  /** Stops the service unless it is stopped already; a refusal or a timeout is recoverable. */
  public static StepResult stop(ServiceRuntime rt, BooleanSupplier cancelled) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      ServiceController.State result = controller.stop(rt.serviceTimeout(), cancelled);
      if (result == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      return recoverable(
          "service did not stop within "
              + rt.serviceTimeout().toSeconds()
              + "s (state "
              + result
              + ", "
              + controller.describe()
              + ")",
          "stop the service by hand or raise service.stopTimeoutSeconds, then run again");
    } catch (ServiceControlException e) {
      return recoverable(e.getMessage(), RIGHTS_REMEDIATION);
    } catch (RuntimeException e) {
      return recoverable("cannot stop the service: " + describe(e), CONFIG_REMEDIATION);
    }
  }

  /** Starts the service unless it is running already, waiting up to {@link #WAIT_CAP}. */
  public static StepResult start(ServiceRuntime rt, BooleanSupplier cancelled) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      // installation guide p.51 (issue #113): the bundled database first, then Tomcat
      Optional<ServiceController> database = rt.databaseController();
      if (database.isPresent() && database.get().state() != ServiceController.State.RUNNING) {
        ServiceController.State db;
        try {
          db = database.get().start(WAIT_CAP, cancelled);
        } catch (ServiceControlException e) {
          return recoverable(
              "the bundled database service "
                  + database.get().describe()
                  + " refused to start: "
                  + e.getMessage(),
              RIGHTS_REMEDIATION);
        }
        if (db != ServiceController.State.RUNNING) {
          return recoverable(
              "the bundled database service did not start within "
                  + WAIT_CAP.toMinutes()
                  + " minutes (state "
                  + db
                  + ", "
                  + database.get().describe()
                  + "); "
                  + CompanionDatabase.VENDOR_NOTE,
              "start the database service by hand, then run again");
        }
      }
      ServiceController.State result = controller.start(WAIT_CAP, cancelled);
      if (result == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      return recoverable(
          "service did not start within "
              + WAIT_CAP.toMinutes()
              + " minutes (state "
              + result
              + ", "
              + controller.describe()
              + ")",
          "check the Tomcat log and start the service by hand");
    } catch (ServiceControlException e) {
      return recoverable(e.getMessage(), RIGHTS_REMEDIATION);
    } catch (RuntimeException e) {
      return recoverable("cannot start the service: " + describe(e), CONFIG_REMEDIATION);
    }
  }

  static String fileSafe(String stepId) {
    return stepId.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static StepResult recoverable(String cause, String nextAction) {
    return StepResult.failed(StepFailure.recoverable(cause, nextAction));
  }

  private static String describe(Exception e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }

  private static void log(
      ServiceRuntime rt,
      Context ctx,
      EventSink out,
      Step step,
      Event.Log.Level level,
      String message) {
    out.emit(
        new Event.Log(
            rt.clock().instant(),
            ctx.runId(),
            Optional.of(step.id()),
            step.phase(),
            level,
            message));
  }

  /** Stops the service; compensation starts it only if this run tried to stop it. */
  private static final class StopService implements Step {
    private final ServiceRuntime rt;
    private final String phase;
    private final String id;

    StopService(ServiceRuntime rt, String phase, String id) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.phase = Objects.requireNonNull(phase, "phase");
      this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Run-scoped marker "this run stopped the service". Step ids may hold characters a file name
     * cannot (hotfix rollback ids carry a colon, illegal on Windows), so the id is made safe.
     */
    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(fileSafe(id) + ".stopped");
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return "stop the JasperReports Server service";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "timeout " + rt.serviceTimeout().toSeconds() + "s";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return controllerCheck(rt);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      ServiceController.State before;
      try {
        before = rt.controller().state();
      } catch (RuntimeException e) {
        return recoverable("cannot query the service: " + describe(e), CONFIG_REMEDIATION);
      }
      if (before == ServiceController.State.STOPPED) {
        log(rt, ctx, out, this, Event.Log.Level.INFO, "service already stopped");
        return StepResult.ok();
      }
      // The marker goes down before the stop is attempted (review 1.13, issue #43): a stop that
      // goes wrong half-way must still be undone by starting the service, and nothing has changed
      // yet if the marker cannot be written.
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), "stopped", StandardCharsets.UTF_8);
      } catch (IOException e) {
        return recoverable(
            "cannot record the service stop in " + marker(ctx) + ": " + e.getMessage(),
            "check that the run directory is writable; the service was not touched");
      }
      return stop(rt, ctx.cancel()::isCancelled);
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      ServiceController.State s = rt.controller().state();
      return s == ServiceController.State.STOPPED
          ? CheckResult.pass()
          : CheckResult.fail("service state is " + s + " after stop", "stop the service by hand");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      if (!Files.isRegularFile(marker(ctx))) {
        log(
            rt,
            ctx,
            out,
            this,
            Event.Log.Level.INFO,
            "service was not stopped by this run; leaving it as is");
        return StepResult.ok();
      }
      // A stop that timed out leaves a JVM that is still ending, or one that never will. Started
      // now, "start" would see it running and leave it, and the server would be down once it
      // ended (Linux laptop, 2026-09-29). A service that answers was never taken down and is
      // left alone; one that does not answer is waited for once more, then started.
      ServiceController.State now;
      try {
        now = rt.controller().state();
      } catch (RuntimeException e) {
        return recoverable("cannot query the service: " + describe(e), CONFIG_REMEDIATION);
      }
      if (now != ServiceController.State.STOPPED
          && rt.probe().problem(ENDING_JVM_PATIENCE).isPresent()) {
        log(
            rt,
            ctx,
            out,
            this,
            Event.Log.Level.INFO,
            "the service was asked to stop and its process is still there; waiting for it to end"
                + " before starting it again");
        StepResult ended = stop(rt, ctx.cancel()::isCancelled);
        if (!(ended instanceof StepResult.Ok)) {
          return recoverable(
              "the service was asked to stop and its process has not ended after twice the stop"
                  + " timeout, so it cannot be started again; it is not answering",
              "end the process by hand and start the service, or set"
                  + " service.forceStopAfterSeconds and run again");
        }
      }
      StepResult result = start(rt, ctx.cancel()::isCancelled);
      if (result instanceof StepResult.Ok) {
        try {
          Files.deleteIfExists(marker(ctx));
        } catch (IOException e) {
          log(
              rt,
              ctx,
              out,
              this,
              Event.Log.Level.WARN,
              "cannot delete " + marker(ctx) + ": " + e.getMessage());
        }
      }
      return result;
    }
  }

  /** Starts the service; compensation stops it again. */
  private static final class StartService implements Step {
    private final ServiceRuntime rt;
    private final String phase;
    private final String id;

    StartService(ServiceRuntime rt, String phase, String id) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.phase = Objects.requireNonNull(phase, "phase");
      this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return "start the JasperReports Server service";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "timeout " + WAIT_CAP.toMinutes() + "m";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return controllerCheck(rt);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return start(rt, ctx.cancel()::isCancelled);
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      ServiceController.State s = rt.controller().state();
      return s == ServiceController.State.RUNNING
          ? CheckResult.pass()
          : CheckResult.fail("service state is " + s + " after start", "check the Tomcat log");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return stop(rt, ctx.cancel()::isCancelled);
    }
  }

  /**
   * Asks {@code serverInfo}, uncached, until the server answers; read-only. One request is out at a
   * time and it may wait for the rest of the ten minutes: a request given up on is still answered
   * when the webapp comes up, together with every other one given up on, and on 2026-09-29 five
   * such requests at once left a JasperReports Server 10.0.0 answering HTTP 500 to everything (its
   * cookie filter shares a date format between threads). A request the server answers with an error
   * is asked again after the backoff. Cancellation is noticed while a request waits.
   */
  private static final class WaitForServer implements Step {
    private final ServiceRuntime rt;
    private final String phase;
    private final String id;

    WaitForServer(ServiceRuntime rt, String phase, String id) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.phase = Objects.requireNonNull(phase, "phase");
      this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return "wait for the server to answer";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "GET /rest_v2/serverInfo, up to " + WAIT_CAP.toMinutes() + " min";
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      RetryPolicy policy = RetryPolicy.HTTP_DEFAULT;
      Duration waited = Duration.ZERO;
      int attempt = 1;
      while (true) {
        ctx.cancel().checkpoint();
        Instant asked = rt.clock().instant();
        Optional<String> problem = ask(rt.probe(), WAIT_CAP.minus(waited), ctx);
        if (problem.isEmpty()) {
          log(rt, ctx, out, this, Event.Log.Level.INFO, "server answered");
          return StepResult.ok();
        }
        attempt++;
        Duration delay = policy.delayBefore(attempt);
        waited = waited.plus(Duration.between(asked, rt.clock().instant())).plus(delay);
        if (waited.compareTo(WAIT_CAP) >= 0) {
          return recoverable(
              "server did not answer within " + WAIT_CAP.toMinutes() + " minutes: " + problem.get(),
              "check the Tomcat and jasperserver logs; the service may still be starting");
        }
        log(
            rt,
            ctx,
            out,
            this,
            Event.Log.Level.DEBUG,
            "not yet: " + problem.get() + "; retry in " + delay.toSeconds() + "s");
        rt.sleeper().sleep(delay, ctx.cancel());
      }
    }

    /**
     * One request that may wait {@code patience}, on a thread of its own so that a cancellation is
     * seen within a quarter of a second; the request is then interrupted and the cancellation
     * raised.
     */
    private static Optional<String> ask(ServerProbe probe, Duration patience, Context ctx) {
      FutureTask<Optional<String>> request = new FutureTask<>(() -> probe.problem(patience));
      Thread.ofPlatform().daemon().name("jrs-hotfix-wait-for-server").start(request);
      while (true) {
        try {
          return request.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
          if (ctx.cancel().isCancelled()) {
            request.cancel(true);
            ctx.cancel().checkpoint();
          }
        } catch (ExecutionException e) {
          return Optional.of(String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
          request.cancel(true);
          Thread.currentThread().interrupt();
          throw new CancellationToken.CancelledException(
              "interrupted while waiting for the server");
        }
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }
}

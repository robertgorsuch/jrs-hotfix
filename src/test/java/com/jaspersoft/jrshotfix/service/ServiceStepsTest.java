package com.jaspersoft.jrshotfix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ServiceStepsTest {
  @TempDir Path tmp;

  ServiceRuntime runtime(FakeServiceController c, ServerProbe probe) {
    return new ServiceRuntime() {
      @Override
      public ServiceController controller() {
        return c;
      }

      @Override
      public Duration serviceTimeout() {
        return Duration.ofSeconds(5);
      }

      @Override
      public Clock clock() {
        return Clock.systemUTC();
      }

      @Override
      public Sleeper sleeper() {
        return Sleeper.none();
      }

      @Override
      public ServerProbe probe() {
        return probe;
      }
    };
  }

  Context ctx() {
    return new Context(
        "r1",
        new Home(tmp),
        new com.jaspersoft.jrshotfix.engine.FakePlatform(tmp),
        new CancellationToken(),
        Map.of());
  }

  @Test
  void should_start_the_service_on_compensation_only_when_this_run_stopped_it() throws Exception {
    // FakeServiceController (copied verbatim from jrsctl) has no no-arg constructor; STOPPED is
    // its documented starting state, so it is passed explicitly here.
    FakeServiceController c = new FakeServiceController(ServiceController.State.STOPPED);
    c.start(Duration.ofSeconds(1));
    Step stop = ServiceSteps.stop(runtime(c, Optional::empty), "apply", ServiceSteps.STOP);
    assertThat(stop.execute(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(c.state()).isEqualTo(ServiceController.State.STOPPED);
    assertThat(Files.isRegularFile(tmp.resolve("runs/r1/stop-service.stopped"))).isTrue();
    assertThat(stop.compensate(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(c.state()).isEqualTo(ServiceController.State.RUNNING);
  }

  /**
   * A probe that records the patience it is given and answers from {@code answers}, in order, the
   * last one for ever.
   */
  private static ServerProbe recording(List<Duration> patience, List<Optional<String>> answers) {
    return new ServerProbe() {
      @Override
      public Optional<String> problem() {
        throw new AssertionError("the wait must say how long its request may take");
      }

      @Override
      public Optional<String> problem(Duration p) {
        patience.add(p);
        return answers.get(Math.min(patience.size(), answers.size()) - 1);
      }
    };
  }

  @Test
  void should_let_its_request_wait_for_the_whole_startup_when_waiting_for_the_server() {
    // a request given up on is still answered when the webapp comes up: five of them at once
    // broke a real server (2026-09-29), so the wait never gives up on one before its own end
    List<Duration> patience = new ArrayList<>();
    Step wait =
        ServiceSteps.waitForServer(
            runtime(
                new FakeServiceController(ServiceController.State.STOPPED),
                recording(patience, List.of(Optional.empty()))),
            "apply",
            ServiceSteps.WAIT);
    assertThat(wait.execute(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(patience).hasSize(1);
    assertThat(patience.get(0)).isGreaterThan(ServiceSteps.WAIT_CAP.minusSeconds(5));
  }

  @Test
  void should_ask_again_with_the_time_that_is_left_when_the_server_answers_an_error() {
    List<Duration> patience = new ArrayList<>();
    Step wait =
        ServiceSteps.waitForServer(
            runtime(
                new FakeServiceController(ServiceController.State.STOPPED),
                recording(patience, List.of(Optional.of("HTTP 503"), Optional.empty()))),
            "apply",
            ServiceSteps.WAIT);
    assertThat(wait.execute(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(patience).hasSize(2);
    assertThat(patience.get(1)).isLessThan(patience.get(0));
  }

  @Test
  void should_fail_when_the_server_answers_errors_until_the_time_is_up() {
    List<Duration> patience = new ArrayList<>();
    Step wait =
        ServiceSteps.waitForServer(
            runtime(
                new FakeServiceController(ServiceController.State.STOPPED),
                recording(patience, List.of(Optional.of("HTTP 500")))),
            "apply",
            ServiceSteps.WAIT);
    StepResult result = wait.execute(ctx(), EventSink.discard());
    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(((StepResult.Failed) result).failure().cause()).contains("HTTP 500");
    assertThat(patience).allSatisfy(p -> assertThat(p).isPositive());
  }

  @Test
  @Timeout(20)
  void should_stop_waiting_when_the_run_is_cancelled_while_its_request_is_held() {
    CountDownLatch asked = new CountDownLatch(1);
    ServerProbe held =
        new ServerProbe() {
          @Override
          public Optional<String> problem() {
            throw new AssertionError("the wait must say how long its request may take");
          }

          @Override
          public Optional<String> problem(Duration patience) {
            asked.countDown();
            try {
              Thread.sleep(patience.toMillis());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return Optional.of("interrupted");
            }
            return Optional.of("request timed out");
          }
        };
    Context ctx = ctx();
    Step wait =
        ServiceSteps.waitForServer(
            runtime(new FakeServiceController(ServiceController.State.STOPPED), held),
            "apply",
            ServiceSteps.WAIT);
    Thread operator =
        new Thread(
            () -> {
              try {
                asked.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              ctx.cancel().cancel("Ctrl-C");
            });
    operator.start();
    assertThatThrownBy(() -> wait.execute(ctx, EventSink.discard()))
        .isInstanceOf(CancellationToken.CancelledException.class)
        .hasMessageContaining("Ctrl-C");
  }

  @Test
  void should_wait_until_the_probe_answers_when_the_server_is_starting() {
    AtomicInteger calls = new AtomicInteger();
    ServerProbe probe =
        () -> calls.incrementAndGet() < 3 ? Optional.of("connection refused") : Optional.empty();
    Step wait =
        ServiceSteps.waitForServer(
            runtime(new FakeServiceController(ServiceController.State.STOPPED), probe),
            "apply",
            ServiceSteps.WAIT);
    assertThat(wait.execute(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(calls.get()).isEqualTo(3);
  }
}

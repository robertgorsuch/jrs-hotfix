package com.jaspersoft.jrshotfix.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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

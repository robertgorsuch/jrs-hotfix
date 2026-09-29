package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.event.Event;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.platform.Diag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The run log lives at {@code home.logFile(runId)} and takes events and platform diagnostics. */
class LogFileTest {

  @TempDir Path tmp;

  @Test
  void should_write_events_and_diagnostics_to_the_run_log_under_the_home() throws Exception {
    Home home = new Home(tmp.resolve("home"));

    try (LogFile log = LogFile.open(home, "r1")) {
      log.sink()
          .emit(new Event.StepRunning(Instant.now(), "r1", Optional.of("preflight"), "p", "Check"));
      Diag.info("platform said {}", "hello");
      Diag.debug("connecting with password=hunter2");
    }
    Diag.info("after close");

    String text = Files.readString(home.logFile("r1"), StandardCharsets.UTF_8);
    assertThat(text).contains("StepRunning [preflight] Check").contains("INFO platform said hello");
    assertThat(text).doesNotContain("after close");
    assertThat(text).contains("password=[redacted]").doesNotContain("hunter2");
  }
}

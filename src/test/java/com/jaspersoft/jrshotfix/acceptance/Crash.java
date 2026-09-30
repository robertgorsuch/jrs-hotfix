package com.jaspersoft.jrshotfix.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Starting a run that stops at a chosen step and killing it there. Invariants: the run is held by
 * the product's test-only pause hook ({@code JRS_HOTFIX_TEST_PAUSE_AT}), which writes {@code
 * runs/<runId>/paused} before the step executes, so the only waiting is for that marker; the kill
 * is the operating system's own ({@code taskkill /F} on Windows, {@code kill -9} elsewhere), never
 * {@link Process#destroyForcibly}, and {@link #kill} fails the test unless the process is gone.
 */
final class Crash {

  private Crash() {}

  /**
   * Starts {@code apply <standard package> --yes} and returns once it is paused at {@code step}.
   */
  static Cli.Running startAndPauseAt(Fixture f, String step) throws Exception {
    return startAndPauseAt(f, step, "apply", f.pkg().toString(), "--yes");
  }

  /** Starts {@code args} and returns once the run is paused at {@code step}. */
  static Cli.Running startAndPauseAt(Fixture f, String step, String... args) throws Exception {
    Cli.Running r = f.cli.withEnv("JRS_HOTFIX_TEST_PAUSE_AT", step).start(args);
    Path runs = f.home.resolve("runs");
    for (int i = 0; i < 300; i++) {
      if (pausedMarker(runs)) {
        return r;
      }
      if (!r.alive()) {
        Cli.Result early = r.collect();
        throw new AssertionError(
            "jrs-hotfix exited "
                + early.exitCode()
                + " before pausing at "
                + step
                + "\nstdout:\n"
                + early.stdout()
                + "\nstderr:\n"
                + early.stderr());
      }
      Thread.sleep(200);
    }
    kill(r);
    Cli.Result never = r.collect();
    throw new AssertionError(
        "no runs/*/paused within 60 s for "
            + step
            + "\nstdout:\n"
            + never.stdout()
            + "\nstderr:\n"
            + never.stderr());
  }

  /** Kills {@code r} with the operating system's own command and asserts it is gone. */
  static void kill(Cli.Running r) throws Exception {
    String pid = Long.toString(r.pid());
    List<String> cmd =
        Cli.windows()
            ? List.of(
                Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))
                    .resolve("System32")
                    .resolve("taskkill.exe")
                    .toString(),
                "/F",
                "/PID",
                pid)
            : List.of("kill", "-9", pid);
    new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor();
    for (int i = 0; i < 100 && r.alive(); i++) {
      Thread.sleep(100);
    }
    assertThat(r.alive()).as("process %s after %s", pid, cmd).isFalse();
    assertThat(ProcessHandle.of(r.pid()).filter(ProcessHandle::isAlive)).isEmpty();
  }

  private static boolean pausedMarker(Path runs) throws IOException {
    if (!Files.isDirectory(runs)) {
      return false;
    }
    try (Stream<Path> dirs = Files.list(runs)) {
      return dirs.anyMatch(d -> Files.exists(d.resolve("paused")));
    }
  }
}

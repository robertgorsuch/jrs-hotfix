package com.jaspersoft.jrshotfix.acceptance;

import static com.jaspersoft.jrshotfix.acceptance.Fixture.LIB;
import static com.jaspersoft.jrshotfix.acceptance.Fixture.STANDARD_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scenario 5: a run killed by the operating system part way, then resumed or rolled back from the
 * journal by a new process. Invariants: the kill is real (the JVM is gone and leaves its lock file
 * behind); a pending run blocks a new mutating command with exit 8; recovery leaves the files and
 * the undo as a completed or a never-started run would.
 */
class CrashRecoveryTest {

  @TempDir Path tmp;

  @Test
  void should_resume_the_swap_and_start_the_service_when_killed_after_the_stop() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Crash.kill(Crash.startAndPauseAt(f, "atomic-swap"));
      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("old foo");
      assertThat(f.home.resolve("lock")).exists();
      assertThat(f.tomcatRunning()).as("stopped before the swap").isFalse();

      Cli.Result blocked = f.cli.run("apply", f.pkg().toString(), "--yes").assertExit(8);
      String runId = f.pendingRunId();
      assertThat(blocked.stdout() + blocked.stderr()).contains(runId);

      f.cli.run("runs", "resume", runId, "--yes").assertExit(0);

      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
      assertThat(read(f, LIB + "new-1.0.jar")).isEqualTo("brand new");
      assertThat(f.target(LIB + "bar-0.9.jar")).doesNotExist();
      assertThat(read(f, "buildomatic/lib/tool-2.0.jar")).isEqualTo("patched tool");
      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.undoable()).contains(STANDARD_ID);
      assertThat(f.tomcatRunning()).as("started by the resume").isTrue();
    }
  }

  @Test
  void should_roll_back_by_starting_the_service_only_when_killed_after_the_stop() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Crash.kill(Crash.startAndPauseAt(f, "atomic-swap"));
      String runId = f.pendingRunId();
      assertThat(f.tomcatRunning()).as("stopped before the swap").isFalse();

      f.cli.run("runs", "rollback", runId, "--yes").assertExit(0);

      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("old foo");
      assertThat(read(f, LIB + "bar-0.9.jar")).isEqualTo("bar");
      assertThat(read(f, "buildomatic/lib/tool-2.0.jar")).isEqualTo("old tool");
      assertThat(f.target(LIB + "new-1.0.jar")).doesNotExist();
      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.cli.run("list").assertExit(0).stdout()).doesNotContain(STANDARD_ID);
      assertThat(f.tomcatRunning()).as("started by the rollback").isTrue();

      f.cli.run("apply", f.pkg().toString(), "--yes").assertExit(0);
      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
    }
  }

  @Test
  void should_resume_the_promote_step_when_killed_after_the_swap() throws Exception {
    try (Fixture f = Fixture.create(tmp)) {
      Crash.kill(Crash.startAndPauseAt(f, "promote-undo"));
      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
      assertThat(f.tomcatRunning()).as("started before the promote step").isTrue();
      assertThat(f.cli.run("list").assertExit(0).stdout()).doesNotContain(STANDARD_ID);

      f.cli.run("runs", "resume", f.pendingRunId(), "--yes").assertExit(0);

      assertThat(f.pendingRunIds()).isEmpty();
      assertThat(f.undoable()).contains(STANDARD_ID);
      assertThat(read(f, LIB + "foo-1.2.3.jar")).isEqualTo("patched foo");
    }
  }

  private static String read(Fixture f, String relative) throws Exception {
    return Files.readString(f.target(relative), StandardCharsets.UTF_8);
  }
}

package com.jaspersoft.jrshotfix.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the shaded {@code jrs-hotfix.jar} as a separate process, the way an operator would.
 * Invariants: acceptance tests never call application classes to act on the installation, the
 * shaded jar is the unit under test; every process gets {@code --home <home> --non-interactive};
 * stdout and stderr go to files under the test's own directory (beside the home), never the system
 * temp directory; a process is ended forcibly only when it overruns its timeout, never as part of a
 * crash test, which kills with the operating system's own command.
 */
final class Cli {

  record Result(int exitCode, String stdout, String stderr) {
    Result assertExit(int expected) {
      assertThat(exitCode)
          .as("exit code\nstdout:\n%s\nstderr:\n%s", stdout, stderr)
          .isEqualTo(expected);
      return this;
    }
  }

  /**
   * A jrs-hotfix process that was started without waiting for it, so a test can observe it, kill it
   * mid-step with the operating system's own command, or collect its {@link Result} later.
   */
  record Running(Process process, Path out, Path err, List<String> cmd) {

    long pid() {
      return process.pid();
    }

    boolean alive() {
      return process.isAlive();
    }

    /** Waits for the process to exit (or forcibly ends it after {@code seconds}) and collects. */
    Result result(long seconds) throws IOException, InterruptedException {
      if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("jrs-hotfix did not exit within " + seconds + "s: " + cmd);
      }
      return collect();
    }

    /** Reads what the (already exited) process wrote and removes the capture files. */
    Result collect() throws IOException {
      String o = Files.readString(out, StandardCharsets.UTF_8);
      String e = Files.readString(err, StandardCharsets.UTF_8);
      Files.deleteIfExists(out);
      Files.deleteIfExists(err);
      return new Result(process.exitValue(), o, e);
    }
  }

  private final Path jar;
  private final Path java;
  private final Path home;
  private final Path captures;
  private final Map<String, String> env;

  Cli(Path home, Map<String, String> env) {
    this.jar = Path.of(System.getProperty("jrshotfix.jar", "target/jrs-hotfix.jar"));
    this.java = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
    this.home = home.toAbsolutePath();
    this.captures = this.home.getParent().resolve("cli-output");
    this.env = Map.copyOf(env);
    assertThat(jar).as("shaded jar; run `bash scripts/mvn.sh -q -DskipTests package`").exists();
  }

  /** The same command line with {@code name=value} added to the child's environment. */
  Cli withEnv(String name, String value) {
    Map<String, String> more = new HashMap<>(env);
    more.put(name, value);
    return new Cli(home, more);
  }

  /** Starts jrs-hotfix and returns at once; the caller decides when and how it ends. */
  Running start(String... args) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(java.toString());
    cmd.add("-jar");
    cmd.add(jar.toString());
    cmd.add("--home");
    cmd.add(home.toString());
    cmd.add("--non-interactive");
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    // the caller's own environment must not leak a home or a pause point into the child
    pb.environment().remove("JRS_HOTFIX_HOME");
    pb.environment().remove("JRS_HOTFIX_TEST_PAUSE_AT");
    pb.environment().putAll(env);
    Files.createDirectories(captures);
    Path out = Files.createTempFile(captures, "out", ".txt");
    Path err = Files.createTempFile(captures, "err", ".txt");
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    pb.redirectInput(
        ProcessBuilder.Redirect.from(Path.of(windows() ? "NUL" : "/dev/null").toFile()));
    return new Running(pb.start(), out, err, List.copyOf(cmd));
  }

  Result run(String... args) throws IOException, InterruptedException {
    return start(args).result(120);
  }

  static boolean windows() {
    return System.getProperty("os.name").startsWith("Windows");
  }
}

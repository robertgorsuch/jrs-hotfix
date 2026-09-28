package com.jaspersoft.jrshotfix.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.LockHeldException;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** The handler maps the three {@link HotfixException} kinds and a held lock to 2, 6, 9, 9. */
class ExitCodesTest {

  @Command(name = "t")
  static final class Dummy {}

  @Test
  void should_map_hotfix_exception_kinds_and_a_held_lock_to_their_exit_codes() {
    assertThat(handle(new HotfixException(HotfixException.PRECHECK, "p", "r"))).isEqualTo(2);
    assertThat(handle(new HotfixException(HotfixException.UNSUPPORTED, "u", "r"))).isEqualTo(6);
    assertThat(handle(new HotfixException(HotfixException.LOCK, "l", "r"))).isEqualTo(9);
    assertThat(handle(new LockHeldException("other", "123"))).isEqualTo(9);
  }

  @Test
  void should_print_message_and_remediation_without_a_stack_trace_for_a_hotfix_exception() {
    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Dummy());
    cmd.setErr(new PrintWriter(err, true));

    new ExitCodes.Handler()
        .handleExecutionException(
            new HotfixException(HotfixException.PRECHECK, "no settings yet", "run detect"),
            cmd,
            null);

    assertThat(err.toString()).contains("no settings yet").contains("run detect");
    assertThat(err.toString()).doesNotContain("at com.");
  }

  @Test
  void should_report_4_for_an_unexpected_exception() {
    assertThat(handle(new IllegalStateException("boom"))).isEqualTo(4);
  }

  @Test
  void should_print_error_and_remediation_and_return_the_code_from_fail() {
    StringWriter err = new StringWriter();

    int code =
        ExitCodes.fail(new PrintWriter(err, true), 2, "checksum not confirmed", Optional.of("x"));

    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).contains("error: checksum not confirmed").contains("x");
  }

  private static int handle(Exception e) {
    CommandLine cmd = new CommandLine(new Dummy());
    cmd.setErr(new PrintWriter(new StringWriter(), true));
    return new ExitCodes.Handler().handleExecutionException(e, cmd, null);
  }
}

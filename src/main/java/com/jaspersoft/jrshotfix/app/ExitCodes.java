package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.LockHeldException;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.platform.UnsupportedPlatformException;
import com.jaspersoft.jrshotfix.redact.Redactor;
import java.io.PrintWriter;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * Process exit codes and the one way a command reports a refusal or failure. Invariants: values
 * never change once released; a failure is {@code error: message} on standard error, followed by
 * the remediation when there is one, and never a stack trace; every message is redacted before it
 * is written.
 */
public final class ExitCodes {

  public static final int SUCCESS = 0;
  public static final int USAGE = 1;
  public static final int PRECHECK_FAILED = 2;
  public static final int FAILED_ROLLED_BACK = 3;
  public static final int FAILED_ROLLBACK_INCOMPLETE = 4;
  public static final int CANCELLED = 5;
  public static final int UNSUPPORTED = 6;

  /**
   * A warning, not a failure: {@code compare} ran and its inputs differ, or a three-way comparison
   * has conflicts (0.7 design, section 3). Only {@code compare} exits with it.
   */
  public static final int DIFFERENT = 7;

  public static final int RECOVERY_REQUIRED = 8;
  public static final int LOCK_HELD = 9;

  private ExitCodes() {}

  /**
   * Reports a refusal: {@code error: message} on {@code err}, then the remediation on its own line.
   * Returns {@code code} so callers can {@code return fail(...)}.
   */
  static int fail(PrintWriter err, int code, String message, Optional<String> remediation) {
    err.println(Redactor.global().redact("error: " + message));
    remediation
        .filter(r -> !r.isBlank())
        .ifPresent(r -> err.println(Redactor.global().redact("  " + r)));
    err.flush();
    return code;
  }

  static String messageOf(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  /**
   * Maps uncaught exceptions to an exit code: a {@link HotfixException} by its kind (precheck 2,
   * unsupported 6, lock 9), a held run lock 9, an unsupported platform 6, and anything else 4 so
   * nothing unexpected is ever reported as success. The operator sees the message and the
   * remediation, never a stack trace.
   */
  static final class Handler implements IExecutionExceptionHandler {
    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
      int code = codeFor(ex);
      Optional<String> remediation =
          ex instanceof HotfixException h ? Optional.of(h.remediation()) : Optional.empty();
      String message =
          code == FAILED_ROLLBACK_INCOMPLETE
              ? "unexpected " + ex.getClass().getSimpleName() + ": " + messageOf(ex)
              : messageOf(ex);
      return fail(cmd.getErr(), code, message, remediation);
    }

    static int codeFor(Throwable ex) {
      if (ex instanceof HotfixException h) {
        return switch (h.kind()) {
          case HotfixException.PRECHECK -> PRECHECK_FAILED;
          case HotfixException.UNSUPPORTED -> UNSUPPORTED;
          case HotfixException.LOCK -> LOCK_HELD;
          default -> FAILED_ROLLBACK_INCOMPLETE;
        };
      }
      if (ex instanceof LockHeldException) {
        return LOCK_HELD;
      }
      if (ex instanceof UnsupportedPlatformException) {
        return UNSUPPORTED;
      }
      return FAILED_ROLLBACK_INCOMPLETE;
    }
  }

  /** Usage errors (exit 1): the message, then suggestions or the usage text, on standard error. */
  static final class ParameterHandler implements IParameterExceptionHandler {
    @Override
    public int handleParseException(ParameterException ex, String[] args) {
      CommandLine cmd = ex.getCommandLine();
      int code = cmd.getCommandSpec().exitCodeOnInvalidInput();
      PrintWriter err = cmd.getErr();
      err.println(cmd.getColorScheme().errorText(Redactor.global().redact(messageOf(ex))));
      if (!UnmatchedArgumentException.printSuggestions(ex, err)) {
        cmd.usage(err);
      }
      err.flush();
      return code;
    }
  }
}

package com.jaspersoft.jrshotfix.hotfix;

import java.util.Objects;

/**
 * Refusal raised before any Plan exists: unreadable or unofficial package, unknown hotfix id, a
 * rollback blocked by later hotfixes, or a run lock held elsewhere. Invariants: nothing has been
 * mutated when it is thrown; {@link #kind()} is one of {@link #PRECHECK}, {@link #UNSUPPORTED} or
 * {@link #LOCK}, and the CLI maps it to its exit code; {@link #remediation()} tells the operator
 * what to change; the message never carries secrets.
 */
public final class HotfixException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Precheck-class refusal (exit 2). */
  public static final String PRECHECK = "precheck";

  /**
   * Something this tool does not handle, such as a file that is not an official package (exit 6).
   */
  public static final String UNSUPPORTED = "unsupported";

  /** Another run holds the lock (exit 9). */
  public static final String LOCK = "lock";

  private final String kind;
  private final String remediation;

  public HotfixException(String kind, String message, String remediation) {
    super(message);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public HotfixException(String kind, String message, String remediation, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public String kind() {
    return kind;
  }

  public String remediation() {
    return remediation;
  }
}

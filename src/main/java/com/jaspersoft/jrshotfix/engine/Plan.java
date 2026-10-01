package com.jaspersoft.jrshotfix.engine;

import java.util.List;
import java.util.Objects;

/**
 * Ordered steps grouped by phase plus the operator-facing summary and the input fingerprint (spec
 * §6.2). Invariant: step ids are unique within a plan and steps of one phase are contiguous, so a
 * phase boundary is a well-defined rollback point.
 */
public record Plan(
    String planId, List<Step> steps, PlanSummary summary, PlanFingerprint fingerprint) {

  public Plan {
    Objects.requireNonNull(planId, "planId");
    steps = List.copyOf(steps);
    var seen = new java.util.HashSet<String>();
    String lastPhase = null;
    var closed = new java.util.HashSet<String>();
    for (Step s : steps) {
      if (!seen.add(s.id())) {
        throw new IllegalArgumentException("duplicate step id " + s.id());
      }
      if (!s.phase().equals(lastPhase)) {
        if (!closed.add(s.phase())) {
          throw new IllegalArgumentException("phase " + s.phase() + " is not contiguous");
        }
        lastPhase = s.phase();
      }
    }
  }

  public boolean mutating() {
    return steps.stream().anyMatch(Step::mutating);
  }
}

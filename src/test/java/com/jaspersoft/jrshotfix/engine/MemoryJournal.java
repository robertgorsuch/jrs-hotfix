package com.jaspersoft.jrshotfix.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A journal in memory for engine tests: same semantics as the file journal, no disk. */
public final class MemoryJournal implements Journal {
  private final Map<String, RunRecord> runs = new LinkedHashMap<>();
  private final List<Transition> transitions = new ArrayList<>();

  @Override
  public synchronized void recordRunStart(
      String runId, String operation, Optional<String> planId, Instant startedAt) {
    runs.put(
        runId,
        new RunRecord(
            runId,
            operation,
            planId,
            startedAt,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
  }

  @Override
  public synchronized void recordRunEnd(
      String runId, Instant endedAt, TerminalState state, int exitCode) {
    RunRecord r = runs.get(runId);
    runs.put(
        runId,
        new RunRecord(
            r.runId(),
            r.operation(),
            r.planId(),
            r.startedAt(),
            Optional.of(endedAt),
            Optional.of(state),
            Optional.of(exitCode)));
  }

  @Override
  public synchronized Transition appendTransition(
      String runId,
      String stepId,
      String phase,
      Optional<String> fromState,
      String toState,
      Optional<String> detail) {
    Transition t =
        new Transition(
            transitions.size() + 1,
            Instant.now(),
            runId,
            stepId,
            phase,
            fromState,
            toState,
            detail);
    transitions.add(t);
    return t;
  }

  @Override
  public synchronized Optional<RunRecord> run(String runId) {
    return Optional.ofNullable(runs.get(runId));
  }

  @Override
  public synchronized List<RunRecord> pendingRuns() {
    return runs.values().stream().filter(RunRecord::pending).toList();
  }

  @Override
  public synchronized List<Transition> transitions(String runId) {
    return transitions.stream().filter(t -> t.runId().equals(runId)).toList();
  }
}

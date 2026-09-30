package com.jaspersoft.jrshotfix.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What the operator sees before confirming (spec §6.2): what changes, whether the server goes down,
 * where the backups are, how to undo, and any plain-language warnings such as "database rollback is
 * the operator's responsibility". {@code changes} says what happens to {@code filesTouched} in a
 * few lines, the totals first, and is shown in place of the paths; when it is empty the paths are
 * listed. Invariants: lists are immutable copies; a summary stored before {@code changes} existed
 * reads back with none.
 */
public record PlanSummary(
    String operation,
    String target,
    List<Path> filesTouched,
    List<String> resourcesTouched,
    boolean serviceRestart,
    List<Path> backupLocations,
    Map<String, String> rollbackPointsByPhase,
    String strategy,
    List<String> warnings,
    List<String> changes) {

  public PlanSummary {
    filesTouched = List.copyOf(filesTouched);
    resourcesTouched = List.copyOf(resourcesTouched);
    backupLocations = List.copyOf(backupLocations);
    rollbackPointsByPhase = Map.copyOf(rollbackPointsByPhase);
    warnings = List.copyOf(warnings);
    changes = changes == null ? List.of() : List.copyOf(changes);
  }

  /** A summary that lists its files instead of counting them. */
  public PlanSummary(
      String operation,
      String target,
      List<Path> filesTouched,
      List<String> resourcesTouched,
      boolean serviceRestart,
      List<Path> backupLocations,
      Map<String, String> rollbackPointsByPhase,
      String strategy,
      List<String> warnings) {
    this(
        operation,
        target,
        filesTouched,
        resourcesTouched,
        serviceRestart,
        backupLocations,
        rollbackPointsByPhase,
        strategy,
        warnings,
        List.of());
  }
}

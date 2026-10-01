package com.jaspersoft.jrshotfix.merge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One prepared merge, {@code merges/<id>/merge.json} under the home (0.2 design, 4.5): the package
 * it was prepared for, the state of the server it was prepared against, and one record for every
 * file the package ships under the webapp, saying what an apply does with it. Invariants: an apply
 * is built from the package and this document alone, never from the baseline again, so a plan
 * rebuilt in the middle of a run is the plan that was started; paths are relative to the webapp,
 * with {@code /}; hashes are of the bytes; lists are immutable.
 */
public record MergeDoc(
    String id,
    String hotfixId,
    String packageSha256,
    String installedBuild,
    List<String> baselines,
    String onConflict,
    Instant createdAt,
    List<Item> files) {

  public MergeDoc {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(hotfixId, "hotfixId");
    Objects.requireNonNull(packageSha256, "packageSha256");
    Objects.requireNonNull(installedBuild, "installedBuild");
    baselines = List.copyOf(baselines);
    Objects.requireNonNull(onConflict, "onConflict");
    Objects.requireNonNull(createdAt, "createdAt");
    files = List.copyOf(files);
  }

  /** What an apply does with one file, and whether the operator still has to decide. */
  public enum State {
    /** The package's copy lands and nothing of the site's is lost. */
    PLAIN,
    /**
     * The package's copy lands over a script, stylesheet or binary file the site changed, unless
     * the operator resolves it with {@code --mine}.
     */
    OVERWRITTEN,
    /** The file is not written: only the site changed it, or the installer wrote it. */
    KEPT,
    /** Merged without a conflict; the merged file lands. */
    AUTO,
    /** Merged without a conflict, and waiting for the operator to confirm it. */
    REVIEW,
    /** Both changed the same place; waiting for the operator. */
    CONFLICT,
    /** The operator's merged file lands. */
    RESOLVED,
    /** The operator chose the site's file: it is not written. */
    KEPT_MINE,
    /** The operator chose the package's copy. */
    TOOK_THEIRS;

    /** True while the operator has to decide: no apply until then. */
    public boolean blocks() {
      return this == REVIEW || this == CONFLICT;
    }

    /** True when the merged file lands. */
    public boolean merged() {
      return this == AUTO || this == RESOLVED;
    }

    /** True when the file on the server stays as it is. */
    public boolean keeps() {
      return this == KEPT || this == KEPT_MINE;
    }
  }

  /**
   * One file. {@code base}, {@code mine} and {@code theirs} are the hashes the merge was prepared
   * from, empty where there is no such file; {@code merged} is the hash of the merged file once
   * there is one; {@code checks} are the findings that keep it unresolved; {@code note} says in a
   * sentence what was done, by key or bean name, never by value.
   */
  public record Item(
      String path,
      String fileClass,
      String verdict,
      State state,
      Optional<String> base,
      Optional<String> mine,
      Optional<String> theirs,
      Optional<String> merged,
      Optional<String> resolvedBy,
      Optional<Instant> resolvedAt,
      List<String> checks,
      String note) {

    public Item {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(fileClass, "fileClass");
      Objects.requireNonNull(verdict, "verdict");
      Objects.requireNonNull(state, "state");
      base = base == null ? Optional.empty() : base;
      mine = mine == null ? Optional.empty() : mine;
      theirs = theirs == null ? Optional.empty() : theirs;
      merged = merged == null ? Optional.empty() : merged;
      resolvedBy = resolvedBy == null ? Optional.empty() : resolvedBy;
      resolvedAt = resolvedAt == null ? Optional.empty() : resolvedAt;
      checks = checks == null ? List.of() : List.copyOf(checks);
      note = note == null ? "" : note;
    }

    /** The hash the file has once the package is applied; empty when it is then absent. */
    public Optional<String> lands() {
      if (state.merged()) {
        return merged;
      }
      return state.keeps() ? mine : theirs;
    }

    Item with(
        State newState, Optional<String> newMerged, String by, Instant at, List<String> findings) {
      return with(newState, newMerged, by, at, findings, note);
    }

    /** As {@link #with(State, Optional, String, Instant, List)}, with a new note. */
    Item with(
        State newState,
        Optional<String> newMerged,
        String by,
        Instant at,
        List<String> findings,
        String newNote) {
      return new Item(
          path,
          fileClass,
          verdict,
          newState,
          base,
          mine,
          theirs,
          newMerged,
          Optional.of(by),
          Optional.of(at),
          findings,
          newNote);
    }
  }

  public Optional<Item> file(String path) {
    return files.stream().filter(r -> r.path().equals(path)).findFirst();
  }

  /** The records the operator still has to decide. */
  public List<Item> blocking() {
    return files.stream().filter(r -> r.state().blocks()).toList();
  }

  MergeDoc withFile(Item changed) {
    return new MergeDoc(
        id,
        hotfixId,
        packageSha256,
        installedBuild,
        baselines,
        onConflict,
        createdAt,
        files.stream().map(r -> r.path().equals(changed.path()) ? changed : r).toList());
  }
}

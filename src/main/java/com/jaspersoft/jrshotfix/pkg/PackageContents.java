package com.jaspersoft.jrshotfix.pkg;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one official package would do to this installation, decided at read time. Invariants: paths
 * are package paths ({@code webapps/<name>/...} or installation-relative); an add or replace
 * carries the SHA-256 of the payload; a delete carries none; entries are in package order with
 * deletions last; notes are the readme's manual steps, never executed.
 */
public record PackageContents(
    String id,
    String release,
    String edition,
    String build,
    String title,
    String sha256,
    List<Entry> entries,
    List<String> notes) {
  public PackageContents {
    entries = List.copyOf(entries);
    notes = List.copyOf(notes);
  }

  /** One file: where it lands, what happens, and where its bytes are in the package. */
  public record Entry(
      String path,
      Action action,
      Optional<String> sha256,
      Optional<String> source,
      String entryName) {
    public Entry {
      Objects.requireNonNull(path);
      Objects.requireNonNull(action);
      Objects.requireNonNull(sha256);
      Objects.requireNonNull(source);
      Objects.requireNonNull(entryName);
    }
  }

  public List<Entry> adds() {
    return entries.stream().filter(e -> e.action() == Action.ADD).toList();
  }

  public List<Entry> replaces() {
    return entries.stream().filter(e -> e.action() == Action.REPLACE).toList();
  }

  public List<Entry> deletes() {
    return entries.stream().filter(e -> e.action() == Action.DELETE).toList();
  }

  public boolean touchesWebInf() {
    return entries.stream().anyMatch(e -> PackagePaths.requiresServiceStop(e.path()));
  }
}

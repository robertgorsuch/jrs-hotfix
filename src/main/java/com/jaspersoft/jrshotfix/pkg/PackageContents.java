package com.jaspersoft.jrshotfix.pkg;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one official package would do to this installation, decided at read time. Invariants: paths
 * are package paths ({@code webapps/<name>/...} or installation-relative); an add or replace
 * carries the SHA-256 of the payload; a delete carries none; entries are in package order with
 * deletions last; notes are what the operator must know or do by hand, the readme's own lines among
 * them in the readme's order, never executed.
 */
public record PackageContents(
    String id,
    String release,
    String edition,
    String build,
    String title,
    String sha256,
    List<Entry> entries,
    List<Note> notes) {
  public PackageContents {
    entries = List.copyOf(entries);
    notes = List.copyOf(notes);
  }

  /**
   * One line for the operator: a sentence of jrs-hotfix's own, or, when {@code quoted}, a line of
   * the package readme exactly as the readme has it (it may be blank).
   */
  public record Note(String text, boolean quoted) {
    public Note {
      Objects.requireNonNull(text);
    }

    public static Note said(String sentence) {
      return new Note(sentence, false);
    }

    public static Note quoted(String line) {
      return new Note(line, true);
    }
  }

  /** The text of every note, in order. */
  public List<String> noteLines() {
    return notes.stream().map(Note::text).toList();
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

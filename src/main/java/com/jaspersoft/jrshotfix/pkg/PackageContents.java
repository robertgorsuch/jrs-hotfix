package com.jaspersoft.jrshotfix.pkg;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one official package would do to this installation, decided at read time. Invariants: paths
 * are package paths ({@code webapps/<name>/...} or installation-relative); an add or replace
 * carries the SHA-256 of the payload; a delete carries none; entries are in package order with
 * deletions last; a file the package ships that must not land here is in {@code kept}, never in
 * {@code entries}, so no step touches it; {@code vendorFiles} is every file the package ships, as
 * it ships it, whatever happens to it here, and {@code deletions} every path and glob its readmes
 * list for deletion, whether or not such a file is here; notes are what the operator must know or
 * do by hand, the readme's own lines among them in the readme's order, never executed.
 */
public record PackageContents(
    String id,
    String release,
    String edition,
    String build,
    String title,
    String sha256,
    List<Entry> entries,
    List<Kept> kept,
    List<VendorFile> vendorFiles,
    List<String> deletions,
    List<Note> notes) {
  public PackageContents {
    entries = List.copyOf(entries);
    kept = List.copyOf(kept);
    vendorFiles = List.copyOf(vendorFiles);
    deletions = List.copyOf(deletions);
    notes = List.copyOf(notes);
  }

  /**
   * One file as the package ships it: its package path, the hash of its bytes, the hash of its text
   * with line ends normalised, its size, and where its bytes are in the package.
   */
  public record VendorFile(
      String path,
      String sha256,
      String textSha256,
      long size,
      Optional<String> source,
      String entryName) {
    public VendorFile {
      Objects.requireNonNull(path);
      Objects.requireNonNull(sha256);
      Objects.requireNonNull(textSha256);
      Objects.requireNonNull(source);
      Objects.requireNonNull(entryName);
    }
  }

  /** The package's copy of {@code path}; empty when the package ships no such file. */
  public Optional<VendorFile> vendorFile(String path) {
    return vendorFiles.stream().filter(f -> f.path().equals(path)).findFirst();
  }

  /** Contents that keep nothing back. */
  public PackageContents(
      String id,
      String release,
      String edition,
      String build,
      String title,
      String sha256,
      List<Entry> entries,
      List<Note> notes) {
    this(
        id,
        release,
        edition,
        build,
        title,
        sha256,
        entries,
        List.of(),
        vendorFilesOf(entries),
        List.of(),
        notes);
  }

  private static List<VendorFile> vendorFilesOf(List<Entry> entries) {
    return entries.stream()
        .filter(e -> e.action() != Action.DELETE)
        .map(
            e -> {
              String sha = e.packageSha256().or(e::sha256).orElseThrow();
              return new VendorFile(e.path(), sha, sha, 0, e.source(), e.entryName());
            })
        .toList();
  }

  /**
   * One file the package ships and this server keeps as it has it: the package path, the hash of
   * the package's copy, and why it stays.
   */
  public record Kept(String path, String vendorSha256, String reason) {
    public Kept {
      Objects.requireNonNull(path);
      Objects.requireNonNull(vendorSha256);
      Objects.requireNonNull(reason);
    }
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

  /**
   * One file: where it lands, what happens, and where its bytes are in the package. {@code sha256}
   * is the hash of what lands. It is the payload's, except for a settings file merged with this
   * server's ({@link SiteSettings}): then {@code packageSha256} holds the payload's hash and {@code
   * sha256} the merged file's.
   */
  public record Entry(
      String path,
      Action action,
      Optional<String> sha256,
      Optional<String> source,
      String entryName,
      Optional<String> packageSha256) {
    public Entry {
      Objects.requireNonNull(path);
      Objects.requireNonNull(action);
      Objects.requireNonNull(sha256);
      Objects.requireNonNull(source);
      Objects.requireNonNull(entryName);
      Objects.requireNonNull(packageSha256);
    }

    /** An entry whose payload lands as it is. */
    public Entry(
        String path,
        Action action,
        Optional<String> sha256,
        Optional<String> source,
        String entryName) {
      this(path, action, sha256, source, entryName, Optional.empty());
    }

    /** True when what lands is the payload merged with this server's file. */
    public boolean merged() {
      return packageSha256.isPresent();
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

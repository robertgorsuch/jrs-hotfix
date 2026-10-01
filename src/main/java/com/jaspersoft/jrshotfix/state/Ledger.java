package com.jaspersoft.jrshotfix.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of hotfixes on this installation, {@code ledger.json} under the home. Invariants:
 * one entry per id; entries stay in install order; every write replaces the file atomically after
 * an fsync; reads parse the whole file, which stays small (hundreds of paths per hotfix).
 */
public final class Ledger {
  private static final TypeReference<List<LedgerEntry>> LIST = new TypeReference<>() {};
  private final Path file;

  public Ledger(Home home) {
    this.file = home.ledgerFile();
  }

  public synchronized void recordInstalled(LedgerEntry entry) {
    List<LedgerEntry> all = new ArrayList<>(all());
    if (all.stream().anyMatch(e -> e.id().equals(entry.id()))) {
      throw new IllegalStateException(entry.id() + " is already in the ledger");
    }
    all.add(entry);
    write(all);
  }

  public synchronized void updateState(String id, HotfixState state) {
    List<LedgerEntry> all = new ArrayList<>();
    for (LedgerEntry e : all()) {
      all.add(e.id().equals(id) ? e.withState(state) : e);
    }
    write(all);
  }

  public synchronized boolean delete(String id) {
    List<LedgerEntry> kept = all().stream().filter(e -> !e.id().equals(id)).toList();
    boolean removed = kept.size() != all().size();
    if (removed) {
      write(kept);
    }
    return removed;
  }

  public Optional<LedgerEntry> find(String id) {
    return all().stream().filter(e -> e.id().equals(id)).findFirst();
  }

  public List<LedgerEntry> installed() {
    return all().stream().filter(e -> e.state() == HotfixState.INSTALLED).toList();
  }

  public List<OwnedFile> files(String id) {
    return find(id).map(LedgerEntry::files).orElse(List.of());
  }

  public List<Map.Entry<String, OwnedFile>> filesOwnedBy(Collection<Path> paths) {
    Set<Path> wanted = new HashSet<>();
    for (Path p : paths) {
      wanted.add(p.toAbsolutePath().normalize());
    }
    List<Map.Entry<String, OwnedFile>> out = new ArrayList<>();
    for (LedgerEntry e : installed()) {
      for (OwnedFile f : e.files()) {
        if (wanted.contains(f.path().toAbsolutePath().normalize())) {
          out.add(new AbstractMap.SimpleImmutableEntry<>(e.id(), f));
        }
      }
    }
    return List.copyOf(out);
  }

  public List<LedgerEntry> all() {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try {
      return List.copyOf(
          Json.mapper().readValue(Files.readString(file, StandardCharsets.UTF_8), LIST));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private void write(List<LedgerEntry> all) {
    try {
      Durability.writeAtomically(file, Json.writePretty(all));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }
}

package com.jaspersoft.jrshotfix.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges a properties file of this server ("mine") into the one a hotfix ships ("theirs"), by key.
 * The result is always theirs, comments, order and layout included, with this server's values put
 * in where the rules say so.
 *
 * <p>Without a common ancestor ({@link #merge(List, List)}) the server's value stands wherever the
 * server has the key, and the keys only the server has are carried over under a heading at the end.
 * It is meant for the files an installer fills in for one site, where the server's value is the one
 * that works there.
 *
 * <p>With the vendor's earlier file as the ancestor ({@link #merge3}) a key only the vendor changed
 * takes the vendor's value, a key only the site changed keeps the site's, and a key both changed is
 * a conflict settled as the {@link Style} says (0.2 design, 4.2).
 *
 * <p>Invariants: nothing is read or written, lines go in and lines come out; a value is compared as
 * one logical line, so a continuation broken differently is the same value; of a key defined twice
 * the last definition counts, as {@code java.util.Properties} has it; under {@link
 * Style#MINE_SILENT}, merging the result with the same theirs gives the result again, line for
 * line, so a plan rebuilt after the swap sees the file it planned; the server's comments are not
 * carried (the file as it was is in the snapshot).
 */
public final class PropertiesMerge {

  /** The comment above the keys only the server has. It states no run, so it never changes. */
  public static final String CARRIED_HEADING =
      "# set on this server and not in the hotfix's file; kept by jrs-hotfix";

  private PropertiesMerge() {}

  /**
   * The two-way merge: {@code kept} are the keys both have with different values, where the
   * server's stayed; {@code carried} are the keys only the server has; nothing is removed.
   */
  public static Merged merge(List<String> mine, List<String> theirs) {
    return merge3(List.of(), mine, theirs, Style.MINE_SILENT);
  }

  /** How a key both the site and the vendor changed is settled. */
  public enum Style {
    /** The site's value stands and nothing is said in the file: the installer-written files. */
    MINE_SILENT,
    /** The site's value stands; the vendor's is written above it as a comment. */
    MINE,
    /** The vendor's value stands; the site's is written above it as a comment. */
    THEIRS,
    /** Neither stands: both are written between conflict markers for the operator. */
    MARKERS
  }

  /**
   * A three-way merge and what was done, by key name only (a value may be a secret): {@code kept}
   * are the keys where the site's value stands against another of the vendor's; {@code carried} the
   * keys only the site has; {@code removed} the keys the site removed and that stay removed; {@code
   * conflicts} the keys both changed, in the order met, whatever the style did with them.
   */
  public record Merged(
      List<String> lines,
      List<String> kept,
      List<String> carried,
      List<String> removed,
      List<String> conflicts) {
    public Merged {
      lines = List.copyOf(lines);
      kept = List.copyOf(kept);
      carried = List.copyOf(carried);
      removed = List.copyOf(removed);
      conflicts = List.copyOf(conflicts);
    }
  }

  /**
   * Merges by key with {@code base} as the common ancestor; an empty {@code base} means the vendor
   * had no such file, so every key the two sides disagree on is a conflict.
   */
  public static Merged merge3(
      List<String> base, List<String> mine, List<String> theirs, Style style) {
    Map<String, Entry> was = entries(base);
    Map<String, Entry> site = entries(mine);
    List<String> lines = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> conflicts = new ArrayList<>();
    Map<String, Entry> remaining = new LinkedHashMap<>(site);
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (Item item : parse(theirs)) {
      if (item.entry().isEmpty()) {
        lines.addAll(item.lines());
        continue;
      }
      Entry vendor = item.entry().get();
      Entry here = site.get(vendor.key());
      Entry before = was.get(vendor.key());
      remaining.remove(vendor.key());
      boolean first = seen.add(vendor.key());
      if (here == null) {
        if (before == null) {
          lines.addAll(item.lines());
        } else if (before.value().equals(vendor.value())) {
          // the site removed it and the vendor did not touch it: it stays removed
          add(removed, vendor.key(), first);
        } else {
          add(conflicts, vendor.key(), first);
          switch (style) {
            case MINE_SILENT -> add(removed, vendor.key(), first);
            case MINE -> {
              lines.add("# jrs-hotfix: removed on this server; the hotfix ships:");
              item.lines().forEach(l -> lines.add("# " + l));
              add(removed, vendor.key(), first);
            }
            case THEIRS -> {
              lines.add("# jrs-hotfix: this server had removed this key; the hotfix's value:");
              lines.addAll(item.lines());
            }
            case MARKERS -> lines.addAll(Conflict.block(List.of(), before.lines(), item.lines()));
          }
        }
        continue;
      }
      if (here.value().equals(vendor.value())
          || (before != null && before.value().equals(here.value()))) {
        // the same on both sides, or only the vendor changed it
        lines.addAll(item.lines());
      } else if (before != null && before.value().equals(vendor.value())) {
        // only the site changed it
        lines.addAll(inPlace(vendor, here));
        add(kept, here.key(), first);
      } else {
        add(conflicts, here.key(), first);
        switch (style) {
          case MINE_SILENT -> {
            lines.addAll(inPlace(vendor, here));
            add(kept, here.key(), first);
          }
          case MINE -> {
            lines.add("# jrs-hotfix: the hotfix's value, not used on this server:");
            item.lines().forEach(l -> lines.add("# " + l));
            lines.addAll(inPlace(vendor, here));
            add(kept, here.key(), first);
          }
          case THEIRS -> {
            lines.add("# jrs-hotfix: this server's value, replaced by the hotfix's:");
            here.lines().forEach(l -> lines.add("# " + l));
            lines.addAll(item.lines());
          }
          case MARKERS ->
              lines.addAll(
                  Conflict.block(
                      here.lines(), before == null ? List.of() : before.lines(), item.lines()));
        }
      }
    }
    List<String> carried = new ArrayList<>();
    List<String> tail = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (Entry e : remaining.values()) {
      Entry before = was.get(e.key());
      if (before == null) {
        carried.add(e.key());
        tail.addAll(e.lines());
      } else if (!before.value().equals(e.value())) {
        // (a key the vendor removed and the site had not touched is simply gone)
        conflicts.add(e.key());
        switch (style) {
          case MINE_SILENT -> {
            carried.add(e.key());
            tail.addAll(e.lines());
          }
          case MINE -> {
            carried.add(e.key());
            tail.add("# jrs-hotfix: the hotfix removes this key; kept as this server has it:");
            tail.addAll(e.lines());
          }
          case THEIRS -> {
            notes.add("# jrs-hotfix: removed by the hotfix; this server had:");
            e.lines().forEach(l -> notes.add("# " + l));
          }
          case MARKERS -> notes.addAll(Conflict.block(e.lines(), before.lines(), List.of()));
        }
      }
    }
    if (!tail.isEmpty()) {
      if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
        lines.add("");
      }
      lines.add(CARRIED_HEADING);
      lines.addAll(tail);
    }
    if (!notes.isEmpty()) {
      if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
        lines.add("");
      }
      lines.addAll(notes);
    }
    return new Merged(lines, kept, carried, removed, conflicts);
  }

  /** The site's value where the vendor's key stands: the vendor's key and separator kept. */
  private static List<String> inPlace(Entry vendor, Entry here) {
    List<String> out = new ArrayList<>();
    out.add(vendor.head() + here.firstValueLine());
    out.addAll(here.continuation());
    return out;
  }

  private static void add(List<String> keys, String key, boolean first) {
    if (first || !keys.contains(key)) {
      keys.add(key);
    }
  }

  private static Map<String, Entry> entries(List<String> lines) {
    Map<String, Entry> out = new LinkedHashMap<>();
    for (Item item : parse(lines)) {
      item.entry().ifPresent(e -> out.put(e.key(), e));
    }
    return out;
  }

  /** One natural line that is blank or a comment, or one key with every line of its value. */
  private record Item(List<String> lines, Optional<Entry> entry) {}

  /**
   * A key and its value as written: {@code head} is the first line up to where the value starts,
   * {@code firstValueLine} the rest of that line, {@code continuation} the lines after it, and
   * {@code value} the logical value they spell, escapes left as they are.
   */
  private record Entry(
      String key,
      String head,
      String firstValueLine,
      List<String> continuation,
      String value,
      List<String> lines) {}

  private static List<Item> parse(List<String> lines) {
    List<Item> out = new ArrayList<>();
    int i = 0;
    while (i < lines.size()) {
      String line = lines.get(i);
      String text = line.stripLeading();
      if (text.isEmpty() || text.startsWith("#") || text.startsWith("!")) {
        out.add(new Item(List.of(line), Optional.empty()));
        i++;
        continue;
      }
      int end = i;
      while (continues(lines.get(end)) && end + 1 < lines.size()) {
        end++;
      }
      List<String> physical = List.copyOf(lines.subList(i, end + 1));
      out.add(new Item(physical, Optional.of(entry(physical))));
      i = end + 1;
    }
    return out;
  }

  /** True when the line ends in an odd number of backslashes: the next line goes on with it. */
  private static boolean continues(String line) {
    int slashes = 0;
    for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
      slashes++;
    }
    return slashes % 2 == 1;
  }

  private static Entry entry(List<String> physical) {
    String first = physical.get(0);
    int at = 0;
    while (at < first.length() && isSpace(first.charAt(at))) {
      at++;
    }
    int keyStart = at;
    while (at < first.length()) {
      char c = first.charAt(at);
      if (c == '\\' && at + 1 < first.length()) {
        at += 2;
        continue;
      }
      if (c == '=' || c == ':' || isSpace(c)) {
        break;
      }
      at++;
    }
    String key = first.substring(keyStart, at);
    while (at < first.length() && isSpace(first.charAt(at))) {
      at++;
    }
    if (at < first.length() && (first.charAt(at) == '=' || first.charAt(at) == ':')) {
      at++;
      while (at < first.length() && isSpace(first.charAt(at))) {
        at++;
      }
    }
    String firstValueLine = first.substring(at);
    List<String> continuation = physical.subList(1, physical.size());
    StringBuilder value = new StringBuilder();
    String part = firstValueLine;
    for (int i = 0; i < physical.size(); i++) {
      if (i > 0) {
        part = physical.get(i).stripLeading();
      }
      boolean more = i + 1 < physical.size();
      value.append(more ? part.substring(0, part.length() - 1) : part);
    }
    return new Entry(
        key,
        first.substring(0, at),
        firstValueLine,
        List.copyOf(continuation),
        value.toString(),
        physical);
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\f';
  }
}

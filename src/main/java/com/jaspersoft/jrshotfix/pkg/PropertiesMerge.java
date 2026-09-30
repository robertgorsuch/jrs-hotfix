package com.jaspersoft.jrshotfix.pkg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges a properties file of this server ("mine") into the one a hotfix ships ("theirs"), by key
 * and without a common ancestor: the result is theirs, comments, order and layout included, with
 * the server's value wherever the server has the key, and the keys only the server has carried over
 * under a heading at the end. It is meant for the files an installer fills in for one site, where
 * the server's value is the one that works there. Invariants: nothing is read or written, lines go
 * in and lines come out; a value is compared as one logical line, so a continuation broken
 * differently is the same value; of a key defined twice the last definition counts, as {@code
 * java.util.Properties} has it; merging the result with the same theirs gives the result again,
 * line for line, so a plan rebuilt after the swap sees the file it planned; the server's comments
 * are not carried (the file as it was is in the snapshot).
 */
public final class PropertiesMerge {

  /** The comment above the keys only the server has. It states no run, so it never changes. */
  public static final String CARRIED_HEADING =
      "# set on this server and not in the hotfix's file; kept by jrs-hotfix";

  private PropertiesMerge() {}

  /**
   * The merged file and what was done: {@code kept} are the keys both have with different values,
   * where the server's stayed; {@code carried} are the keys only the server has.
   */
  public record Result(List<String> lines, List<String> kept, List<String> carried) {
    public Result {
      lines = List.copyOf(lines);
      kept = List.copyOf(kept);
      carried = List.copyOf(carried);
    }

    /** False when the result is theirs, line for line. */
    public boolean changed() {
      return !kept.isEmpty() || !carried.isEmpty();
    }
  }

  public static Result merge(List<String> mine, List<String> theirs) {
    Map<String, Entry> site = new LinkedHashMap<>();
    for (Item item : parse(mine)) {
      item.entry().ifPresent(e -> site.put(e.key(), e));
    }
    List<String> lines = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    Map<String, Entry> remaining = new LinkedHashMap<>(site);
    for (Item item : parse(theirs)) {
      Optional<Entry> vendor = item.entry();
      Entry here = vendor.map(v -> site.get(v.key())).orElse(null);
      if (vendor.isEmpty() || here == null || here.value().equals(vendor.get().value())) {
        lines.addAll(item.lines());
      } else {
        lines.add(vendor.get().head() + here.firstValueLine());
        lines.addAll(here.continuation());
        if (!kept.contains(here.key())) {
          kept.add(here.key());
        }
      }
      vendor.ifPresent(v -> remaining.remove(v.key()));
    }
    if (!remaining.isEmpty()) {
      if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
        lines.add("");
      }
      lines.add(CARRIED_HEADING);
      for (Entry e : remaining.values()) {
        lines.addAll(e.lines());
      }
    }
    return new Result(lines, kept, List.copyOf(remaining.keySet()));
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

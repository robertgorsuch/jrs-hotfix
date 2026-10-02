package com.jaspersoft.jrshotfix.compare;

import com.jaspersoft.jrshotfix.baseline.Area;
import com.jaspersoft.jrshotfix.baseline.BaselineStore;
import com.jaspersoft.jrshotfix.baseline.FileClass;
import com.jaspersoft.jrshotfix.pkg.SiteSettings;
import com.jaspersoft.jrshotfix.platform.Sums;
import com.jaspersoft.jrshotfix.text.Diff;
import com.jaspersoft.jrshotfix.text.Diff3;
import com.jaspersoft.jrshotfix.text.PropertiesMerge;
import com.jaspersoft.jrshotfix.text.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The scan and the merge as a read-only comparison of any inputs (0.7 design, section 3): two-way,
 * what differs between two; three-way, what each of {@code mine} and {@code theirs} changed from
 * {@code base}, and where they meet, decided by the same rules as a merge workspace. Invariants:
 * nothing an input names is written; files of a text class that differ in line ends only are equal;
 * files built or written at run time are counted, never listed; an area is compared only when every
 * input holds it.
 */
public final class Compare {

  private Compare() {}

  /** What became of one file. */
  public enum Kind {
    /** Two-way: in both, different. */
    DIFFERS("differs"),
    /** Two-way: only in the first input. */
    ONLY_FIRST("only in the first"),
    /** Two-way: only in the second input. */
    ONLY_SECOND("only in the second"),
    /** Three-way: only mine changed it. */
    ONLY_MINE("changed in mine only"),
    /** Three-way: only theirs changed it. */
    ONLY_THEIRS("changed in theirs only"),
    /** Three-way: both changed it the same way. */
    BOTH_ALIKE("changed alike in both"),
    /** Three-way: both changed it, merged by its class without a conflict. */
    MERGED("merged"),
    /** Three-way: both changed it and no rule settles it. */
    CONFLICT("conflict");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * One file that is not the same everywhere. {@code note} says more where there is more to say;
   * {@code merged} is the three-way result of a text file both changed, conflict markers included.
   */
  public record Item(
      Area area,
      String path,
      FileClass fileClass,
      Kind kind,
      String note,
      Optional<List<String>> merged) {}

  /**
   * What a comparison found: the files that differ, how many are the same everywhere, how many
   * built or run-time files were left out, and the areas compared and not compared.
   */
  public record Report(
      List<Item> items, int same, int generated, List<Area> compared, List<Area> skipped) {
    public Report {
      items = List.copyOf(items);
      compared = List.copyOf(compared);
      skipped = List.copyOf(skipped);
    }

    /** True when something differs: the inputs are not the same, or a three-way has conflicts. */
    public boolean differ() {
      return !items.isEmpty();
    }

    public long count(Kind kind) {
      return items.stream().filter(i -> i.kind() == kind).count();
    }
  }

  /** What differs between {@code a} and {@code b}. */
  public static Report twoWay(Input a, Input b) throws IOException {
    List<Area> compared = new ArrayList<>();
    List<Area> skipped = new ArrayList<>();
    areas(List.of(a, b), compared, skipped);
    List<Item> items = new ArrayList<>();
    int same = 0;
    int generated = 0;
    for (Area area : compared) {
      for (String path : union(area, a, b)) {
        FileClass cls = area.fileClass(path);
        if (generated(area, path)) {
          generated++;
          continue;
        }
        Optional<Sums> x = sums(a, area, path);
        Optional<Sums> y = sums(b, area, path);
        if (equal(cls, x, y)) {
          same++;
          continue;
        }
        Kind kind = x.isEmpty() ? Kind.ONLY_SECOND : y.isEmpty() ? Kind.ONLY_FIRST : Kind.DIFFERS;
        items.add(new Item(area, path, cls, kind, note(area, path), Optional.empty()));
      }
    }
    return new Report(items, same, generated, compared, skipped);
  }

  /** What {@code mine} and {@code theirs} each changed from {@code base}, and where they meet. */
  public static Report threeWay(Input base, Input mine, Input theirs) throws IOException {
    List<Area> compared = new ArrayList<>();
    List<Area> skipped = new ArrayList<>();
    areas(List.of(base, mine, theirs), compared, skipped);
    List<Item> items = new ArrayList<>();
    int same = 0;
    int generated = 0;
    for (Area area : compared) {
      for (String path : union(area, base, mine, theirs)) {
        FileClass cls = area.fileClass(path);
        if (generated(area, path)) {
          generated++;
          continue;
        }
        Optional<Sums> b = sums(base, area, path);
        Optional<Sums> m = sums(mine, area, path);
        Optional<Sums> t = sums(theirs, area, path);
        String note = note(area, path);
        if (equal(cls, m, t)) {
          if (equal(cls, b, m)) {
            same++;
          } else {
            items.add(new Item(area, path, cls, Kind.BOTH_ALIKE, note, Optional.empty()));
          }
        } else if (equal(cls, b, m)) {
          items.add(new Item(area, path, cls, Kind.ONLY_THEIRS, note, Optional.empty()));
        } else if (equal(cls, b, t)) {
          items.add(new Item(area, path, cls, Kind.ONLY_MINE, note, Optional.empty()));
        } else {
          items.add(both(area, path, cls, note, base, mine, theirs, m, t));
        }
      }
    }
    return new Report(items, same, generated, compared, skipped);
  }

  /** A file both changed, differently: merged by its class, or a conflict. */
  private static Item both(
      Area area,
      String path,
      FileClass cls,
      String note,
      Input base,
      Input mine,
      Input theirs,
      Optional<Sums> m,
      Optional<Sums> t)
      throws IOException {
    if (m.isEmpty() || t.isEmpty()) {
      return new Item(
          area,
          path,
          cls,
          Kind.CONFLICT,
          join(
              note,
              (m.isEmpty() ? "removed in mine" : "removed in theirs") + ", changed in the other"),
          Optional.empty());
    }
    if (!cls.mergeable()
        || m.get().size() > BaselineStore.MAX_PAYLOAD_BYTES
        || t.get().size() > BaselineStore.MAX_PAYLOAD_BYTES) {
      return new Item(
          area,
          path,
          cls,
          Kind.CONFLICT,
          join(note, "scripts, stylesheets, binary and large files are not merged"),
          Optional.empty());
    }
    List<String> baseLines = lines(base, area, path);
    List<String> mineLines = lines(mine, area, path);
    List<String> theirLines = lines(theirs, area, path);
    if (cls == FileClass.P) {
      PropertiesMerge.Merged merged =
          PropertiesMerge.merge3(baseLines, mineLines, theirLines, PropertiesMerge.Style.MARKERS);
      return merged.conflicts().isEmpty()
          ? new Item(
              area, path, cls, Kind.MERGED, join(note, "by key"), Optional.of(merged.lines()))
          : new Item(
              area,
              path,
              cls,
              Kind.CONFLICT,
              join(note, "keys changed by both: " + String.join(", ", merged.conflicts())),
              Optional.of(merged.lines()));
    }
    Diff3.Result merged = Diff3.merge(baseLines, mineLines, theirLines);
    if (!merged.clean()) {
      return new Item(
          area,
          path,
          cls,
          Kind.CONFLICT,
          join(note, merged.conflicts() + " place(s) changed by both"),
          Optional.of(merged.lines()));
    }
    return new Item(
        area,
        path,
        cls,
        Kind.MERGED,
        join(note, cls == FileClass.X ? "by line; XML, review it" : "by line"),
        Optional.of(merged.lines()));
  }

  /**
   * Writes the three-way result under {@code out}, one directory per area ({@code webapp/}, {@code
   * installation/}): every file as it comes out, mine where nothing settles it, conflict markers
   * where a text file has a conflict. A file that comes out absent is not written.
   */
  public static void write(Report report, Input base, Input mine, Input theirs, Path out)
      throws IOException {
    for (Area area : report.compared()) {
      Path root = out.resolve(area.label());
      java.util.Map<String, Item> byPath = new java.util.HashMap<>();
      for (Item item : report.items()) {
        if (item.area() == area) {
          byPath.put(item.path(), item);
        }
      }
      for (String path : union(area, base, mine, theirs)) {
        Item item = byPath.get(path);
        Path target = root.resolve(path);
        if (item != null && item.merged().isPresent()) {
          byte[] eol = Files.readAllBytes(theirs.file(area, path));
          write(target, Text.of(eol).bytes(item.merged().get()));
          continue;
        }
        Input from = item != null && item.kind() == Kind.ONLY_THEIRS ? theirs : mine;
        Path source = from.file(area, path);
        if (!Files.isRegularFile(source) && item != null && item.kind() == Kind.CONFLICT) {
          source = theirs.file(area, path);
        }
        if (Files.isRegularFile(source)) {
          Files.createDirectories(target.getParent());
          Files.copy(source, target);
        }
      }
    }
  }

  private static void write(Path target, byte[] bytes) throws IOException {
    Files.createDirectories(target.getParent());
    Files.write(target, bytes);
  }

  /**
   * The differences of one file as unified diffs: from the first input to the second, or from base
   * to mine and from base to theirs. Empty when the file is the same everywhere.
   */
  public static List<String> show(String path, List<Input> inputs) throws IOException {
    Area area =
        inputs.stream()
                .anyMatch(
                    i ->
                        i.areas().contains(Area.WEBAPP)
                            && Files.isRegularFile(i.file(Area.WEBAPP, path)))
            ? Area.WEBAPP
            : Area.INSTALLATION;
    FileClass cls = area.fileClass(path);
    if (!cls.text()) {
      return List.of(path + ": a binary file; compare it by its hash in the report");
    }
    Input first = inputs.get(0);
    List<String> out = new ArrayList<>();
    for (Input other : inputs.subList(1, inputs.size())) {
      out.addAll(
          Diff.unified(
              first.label() + "/" + path,
              other.label() + "/" + path,
              lines(first, area, path),
              lines(other, area, path)));
    }
    return out;
  }

  /** The areas every input holds, and those only some hold. */
  private static void areas(List<Input> inputs, List<Area> compared, List<Area> skipped) {
    for (Area area : Area.values()) {
      long holding = inputs.stream().filter(i -> i.areas().contains(area)).count();
      if (holding == inputs.size()) {
        compared.add(area);
      } else if (holding > 0) {
        skipped.add(area);
      }
    }
  }

  private static Set<String> union(Area area, Input... inputs) throws IOException {
    TreeSet<String> all = new TreeSet<>();
    for (Input input : inputs) {
      all.addAll(input.files(area));
    }
    return all;
  }

  private static boolean generated(Area area, String path) {
    String p = path.toLowerCase(Locale.ROOT);
    return area.generated(path) || (area == Area.WEBAPP && p.startsWith("web-inf/logs/"));
  }

  private static String note(Area area, String path) {
    boolean installer =
        area == Area.WEBAPP
            ? SiteSettings.holdsSiteValuesInWebapp(path) || SiteSettings.keptAsItIsInWebapp(path)
            : area.siteFile(path);
    return installer ? "installer-written" : "";
  }

  private static String join(String a, String b) {
    return a.isEmpty() ? b : a + "; " + b;
  }

  private static Optional<Sums> sums(Input input, Area area, String path) throws IOException {
    Path file = input.file(area, path);
    return Files.isRegularFile(file) ? Optional.of(Sums.of(file)) : Optional.empty();
  }

  /** Equal: both absent, or both there with the same bytes, line ends aside for a text class. */
  private static boolean equal(FileClass cls, Optional<Sums> x, Optional<Sums> y) {
    if (x.isEmpty() || y.isEmpty()) {
      return x.isEmpty() && y.isEmpty();
    }
    return x.get().sha256().equals(y.get().sha256())
        || (cls.text() && x.get().textSha256().equals(y.get().textSha256()));
  }

  private static List<String> lines(Input input, Area area, String path) throws IOException {
    Path file = input.file(area, path);
    return Files.isRegularFile(file) ? Text.of(Files.readAllBytes(file)).lines() : List.of();
  }
}

package com.jaspersoft.jrshotfix.war;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Sums;
import com.jaspersoft.jrshotfix.platform.Trees;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A WAR as the target of a hotfix (0.2 design, section 7): its files unpacked under the home so
 * that the scan, the merge and the plan read them as they read a webapp, and a hotfixed WAR
 * assembled from the input WAR and the staged files. Invariants: the input WAR is never modified;
 * the unpacked copy is keyed by the WAR's hash and reused while it matches; every entry name is
 * checked before it is written anywhere; the output is written to a temporary file beside the
 * output path and renamed only once verified; entries are streamed, never held whole.
 */
public final class WarFile {

  /** The record beside the unpacked copy: which WAR it is. */
  public record Unpacked(String source, String sha256, int entries) {}

  private static final String MARKER = "war.json";

  private WarFile() {}

  /**
   * Unpacks {@code war} into {@code webappDir} unless the copy there is of this WAR already, and
   * returns the WAR's hash.
   */
  public static Unpacked unpack(Path war, Path webappDir, FileOps files) throws IOException {
    String sha = files.sha256(war);
    Path marker = webappDir.resolveSibling(webappDir.getFileName() + "." + MARKER);
    Optional<Unpacked> existing = read(marker);
    if (existing.isPresent()
        && existing.get().sha256().equals(sha)
        && Files.isDirectory(webappDir)) {
      return existing.get();
    }
    Files.deleteIfExists(marker);
    if (Files.exists(webappDir)) {
      Trees.deleteRecursively(webappDir);
    }
    Files.createDirectories(webappDir);
    Path root = webappDir.toAbsolutePath().normalize();
    int count = 0;
    try (InputStream in = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String path = checked(war, entry.getName());
        Path target = root.resolve(path.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root)) {
          throw notAWebapp(war, "an entry escapes the webapp: " + path);
        }
        Files.createDirectories(target.getParent());
        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
        count++;
      }
    }
    if (count == 0) {
      throw notAWebapp(war, "it is not a readable archive");
    }
    Unpacked unpacked = new Unpacked(war.toString(), sha, count);
    Files.writeString(marker, Json.writePretty(unpacked), StandardCharsets.UTF_8);
    Durability.sync(marker);
    return unpacked;
  }

  private static Optional<Unpacked> read(Path marker) {
    if (!Files.isRegularFile(marker)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          Json.mapper()
              .readValue(Files.readString(marker, StandardCharsets.UTF_8), Unpacked.class));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** What one entry of the output holds, for the check after assembly. */
  public record Expected(Map<String, String> hashes, Set<String> absent, int count) {
    public Expected {
      hashes = Map.copyOf(hashes);
      absent = Set.copyOf(absent);
    }
  }

  /**
   * Streams {@code in} to {@code out}: an entry of {@code dropped} is left out, every other entry
   * is copied, and each of {@code staged} (webapp path to file) is appended. Returns how many
   * entries the output holds.
   */
  public static int assemble(Path in, Path out, Set<String> dropped, Map<String, Path> staged)
      throws IOException {
    Objects.requireNonNull(dropped, "dropped");
    int count = 0;
    Files.createDirectories(out.toAbsolutePath().getParent());
    try (OutputStream o = Files.newOutputStream(out);
        ZipOutputStream zip = new ZipOutputStream(o);
        InputStream i = Files.newInputStream(in);
        ZipInputStream source = new ZipInputStream(i)) {
      ZipEntry entry;
      while ((entry = source.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String path = checked(in, entry.getName());
        if (dropped.contains(path) || staged.containsKey(path)) {
          continue;
        }
        ZipEntry copy = new ZipEntry(path);
        copy.setTime(entry.getTime());
        zip.putNextEntry(copy);
        source.transferTo(zip);
        zip.closeEntry();
        count++;
      }
      for (Map.Entry<String, Path> e : staged.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        try (InputStream file = Files.newInputStream(e.getValue())) {
          file.transferTo(zip);
        }
        zip.closeEntry();
        count++;
      }
    }
    Durability.sync(out);
    return count;
  }

  /** Why {@code war} is not as {@code expected}; empty when it is. Reads every entry once. */
  public static List<String> check(Path war, Expected expected) throws IOException {
    List<String> problems = new ArrayList<>();
    Map<String, String> found = new HashMap<>();
    int count = 0;
    try (InputStream i = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(i)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        count++;
        String path = entry.getName().replace('\\', '/');
        if (expected.hashes().containsKey(path) || expected.absent().contains(path)) {
          found.put(path, Sums.of(zip).sha256());
        }
      }
    }
    for (Map.Entry<String, String> e : expected.hashes().entrySet()) {
      String actual = found.get(e.getKey());
      if (actual == null) {
        problems.add(e.getKey() + " is missing from the output");
      } else if (!actual.equals(e.getValue())) {
        problems.add(
            e.getKey() + " hashes to " + actual + " in the output, expected " + e.getValue());
      }
    }
    for (String path : expected.absent()) {
      if (found.containsKey(path)) {
        problems.add(path + " should be absent from the output but is there");
      }
    }
    if (count != expected.count()) {
      problems.add("the output holds " + count + " entries, expected " + expected.count());
    }
    return problems;
  }

  /** The number of file entries of {@code war}. */
  public static int entries(Path war) throws IOException {
    int count = 0;
    try (InputStream i = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(i)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (!entry.isDirectory()) {
          count++;
        }
      }
    }
    return count;
  }

  /** The names of the file entries of {@code war}, as webapp paths. */
  public static Set<String> paths(Path war) throws IOException {
    Set<String> out = new java.util.LinkedHashSet<>();
    try (InputStream i = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(i)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (!entry.isDirectory()) {
          out.add(checked(war, entry.getName()));
        }
      }
    }
    return out;
  }

  private static String checked(Path war, String entryName) {
    String path = entryName.replace('\\', '/');
    if (!PackagePaths.pathProblems(path).isEmpty()) {
      throw notAWebapp(war, "it holds an unusable entry name");
    }
    return path;
  }

  /** The output's temporary name while it is written and checked. */
  public static Path temporary(Path out) {
    return out.resolveSibling(out.getFileName() + ".jrs-hotfix.tmp");
  }

  /** The record written beside a hotfixed WAR. */
  public static Path sidecar(Path out) {
    return out.resolveSibling(out.getFileName() + ".jrs-hotfix.json");
  }

  /** A function from a webapp path to the entry name it has in a WAR: the same string. */
  public static Function<String, String> identity() {
    return Function.identity();
  }

  public static HotfixException notAWebapp(Path war, String why) {
    return new HotfixException(
        HotfixException.UNSUPPORTED,
        war + " is not a JasperReports Server 10.x webapp: " + why,
        "point --war at the jasperserver-pro.war of a 10.x installation");
  }
}

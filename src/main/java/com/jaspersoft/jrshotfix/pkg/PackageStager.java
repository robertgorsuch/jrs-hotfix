package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts chosen files of an official package straight out of the ZIP, in one streamed pass.
 * Invariants: an entry is found by the same {@code (source, entryName)} pair {@link
 * OfficialPackage#read} recorded, the outer name normalised to {@code /} and the inner name raw; an
 * inner archive is only opened when it holds a wanted file; bytes are streamed, never held whole;
 * every file written is forced to disk before the pass goes on; nothing but the destinations is
 * written. It does not check hashes: the caller compares what landed with the plan.
 */
public final class PackageStager {

  private PackageStager() {}

  /**
   * Copies every entry of {@code contents} whose package path is in {@code paths} to {@code
   * destination.apply(path)}, creating parents and replacing what is there. A wanted path the
   * package does not hold is simply not written.
   */
  public static void stage(
      Path zip,
      PackageContents contents,
      Set<String> paths,
      Function<String, Path> destination,
      CancellationToken cancel)
      throws IOException {
    // outer name (normalised) -> inner raw entry name -> package path
    Map<String, Map<String, String>> inner = new HashMap<>();
    // raw outer entry name -> package path, for files shipped unpacked
    Map<String, String> direct = new HashMap<>();
    for (PackageContents.Entry e : contents.entries()) {
      if (e.action() == Action.DELETE || !paths.contains(e.path())) {
        continue;
      }
      if (e.source().isPresent()) {
        inner.computeIfAbsent(e.source().get(), k -> new HashMap<>()).put(e.entryName(), e.path());
      } else {
        direct.put(e.entryName(), e.path());
      }
    }
    if (inner.isEmpty() && direct.isEmpty()) {
      return;
    }
    Optional<OfficialPackage.Shape> shape = OfficialPackage.shape(zip);
    if (shape.isEmpty()) {
      throw new IOException(zip + " is no longer a readable hotfix package");
    }
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream outer = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = outer.getNextEntry()) != null) {
        cancel.checkpoint();
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName().replace('\\', '/');
        switch (shape.get().kind(name)) {
          case WEBAPP_ZIP, INSTALL_ZIP -> {
            Map<String, String> wanted = inner.get(name);
            if (wanted != null) {
              stageInner(outer, wanted, destination, cancel);
            }
          }
          case WEBAPP_FILE -> {
            String path = direct.get(entry.getName());
            if (path != null) {
              write(outer, destination.apply(path));
            }
          }
          case README, IGNORE -> {}
        }
      }
    }
  }

  /**
   * Walks one inner archive; the outer stream is wrapped, not closed, so the outer walk goes on.
   */
  private static void stageInner(
      InputStream source,
      Map<String, String> wanted,
      Function<String, Path> destination,
      CancellationToken cancel)
      throws IOException {
    ZipInputStream zip = new ZipInputStream(source);
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      cancel.checkpoint();
      if (entry.isDirectory()) {
        continue;
      }
      String path = wanted.get(entry.getName());
      if (path != null) {
        write(zip, destination.apply(path));
      }
    }
  }

  private static void write(InputStream in, Path target) throws IOException {
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    Durability.sync(target);
  }
}

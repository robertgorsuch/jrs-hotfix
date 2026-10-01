package com.jaspersoft.jrshotfix.platform;

import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The two steps every walk over a ZIP, WAR or JAR repeats. Invariants: a walk sees file entries
 * only, never a directory entry; a name is compared with {@code /} separators whatever tool wrote
 * the archive.
 */
public final class Zips {

  private Zips() {}

  /** The next file entry of {@code zip}, skipping directory entries; null at the end. */
  public static ZipEntry nextFile(ZipInputStream zip) throws IOException {
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      if (!entry.isDirectory()) {
        return entry;
      }
    }
    return null;
  }

  /** The entry's name with {@code /} separators. */
  public static String name(ZipEntry entry) {
    return entry.getName().replace('\\', '/');
  }
}

package com.jaspersoft.jrshotfix.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The one documentation page, shipped as a resource and printed by {@code --docs}. Invariants: the
 * text is read once from the jar as UTF-8 and never mutated; it is the same content as the
 * repository's root {@code README.md}, minus that file's Build and Install sections.
 */
final class EmbeddedDoc {

  private static final String TEXT = load();

  private EmbeddedDoc() {}

  /** The page's full text, exactly as shipped under {@code docs/README.md}. */
  static String text() {
    return TEXT;
  }

  private static String load() {
    try (InputStream in = EmbeddedDoc.class.getResourceAsStream("/docs/README.md")) {
      if (in == null) {
        throw new IllegalStateException("docs/README.md is missing from the jar");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

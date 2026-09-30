package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.platform.NaturalOrder;
import java.util.Locale;
import java.util.Optional;

/**
 * A library's file name split into what it is and which version: the artifact is everything before
 * the last {@code -} that is followed by a digit, the version everything after it, so {@code
 * log4j-1.2-api-2.25.4.jar} is {@code log4j-1.2-api} at {@code 2.25.4}. Invariants: only a name
 * ending in {@code .jar} with such a {@code -} splits; a name built from a version and a qualifier
 * with a number of its own ({@code x-7.0.5-JS-79557-SNAPSHOT.jar}) splits at the qualifier's
 * number, so its artifact holds the version and it is never taken for an older copy of another
 * version: the rule errs towards reporting nothing.
 */
public record JarName(String artifact, String version) {

  /** The parts of {@code fileName}; empty when it states no version. */
  public static Optional<JarName> of(String fileName) {
    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
      return Optional.empty();
    }
    String name = fileName.substring(0, fileName.length() - ".jar".length());
    for (int i = name.length() - 2; i > 0; i--) {
      char next = name.charAt(i + 1);
      if (name.charAt(i) == '-' && next >= '0' && next <= '9') {
        return Optional.of(new JarName(name.substring(0, i), name.substring(i + 1)));
      }
    }
    return Optional.empty();
  }

  /** True when this is the same artifact as {@code other} in a lower version. */
  public boolean olderThan(JarName other) {
    return artifact.equals(other.artifact)
        && NaturalOrder.compareVersions(version, other.version) < 0;
  }
}

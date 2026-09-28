package com.jaspersoft.jrshotfix;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The build's own identity, read once from {@code jrs-hotfix-version.properties} (filtered by
 * Maven). Invariants: never empty; a missing resource is a packaging bug and fails fast.
 */
public final class Version {
  private static final Version CURRENT = load();
  private final String version;
  private final String product;
  private final String vendor;

  private Version(String version, String product, String vendor) {
    this.version = version;
    this.product = product;
    this.vendor = vendor;
  }

  public static Version current() {
    return CURRENT;
  }

  public String version() {
    return version;
  }

  public String product() {
    return product;
  }

  public String vendor() {
    return vendor;
  }

  private static Version load() {
    Properties p = new Properties();
    try (InputStream in = Version.class.getResourceAsStream("/jrs-hotfix-version.properties")) {
      if (in == null) {
        throw new IllegalStateException("jrs-hotfix-version.properties is missing from the jar");
      }
      p.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new Version(
        p.getProperty("version", "0"),
        p.getProperty("product", "jrs-hotfix"),
        p.getProperty("vendor", "Jaspersoft"));
  }
}

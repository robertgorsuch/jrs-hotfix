package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.Version;

/** Process entry point. Invariant: the only place that calls {@code System.exit}. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.out.println(Version.current().product() + " " + Version.current().version());
  }
}

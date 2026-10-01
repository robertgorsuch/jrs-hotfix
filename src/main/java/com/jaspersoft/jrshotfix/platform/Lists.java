package com.jaspersoft.jrshotfix.platform;

import java.util.List;

/** How a message names a list that may be long. */
public final class Lists {

  private Lists() {}

  /** The first {@code n} of {@code items}, comma-separated, then "and K more" for the rest. */
  public static String firstAndMore(List<String> items, int n) {
    List<String> shown = items.size() > n ? items.subList(0, n) : items;
    return String.join(", ", shown)
        + (items.size() > shown.size() ? " and " + (items.size() - shown.size()) + " more" : "");
  }
}

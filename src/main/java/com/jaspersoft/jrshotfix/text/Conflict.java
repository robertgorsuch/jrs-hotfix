package com.jaspersoft.jrshotfix.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The conflict block both mergers write where the site and the vendor changed the same place: the
 * site's lines, the base's and the hotfix's, each under its own marker. Invariants: the marker text
 * never changes, since an operator's editor and {@link #hasMarkers} both look for it; nothing is
 * read or written.
 */
public final class Conflict {

  /** The first line of a conflict block; the three sides follow, each under its own marker. */
  public static final String MARK_MINE = "<<<<<<< mine (on the server)";

  public static final String MARK_BASE = "||||||| base (the vendor's file before this hotfix)";
  public static final String MARK_SEPARATOR = "=======";
  public static final String MARK_THEIRS = ">>>>>>> theirs (the hotfix)";

  private Conflict() {}

  /** The block for one place both sides changed. */
  public static List<String> block(List<String> mine, List<String> base, List<String> theirs) {
    List<String> out = new ArrayList<>(mine.size() + base.size() + theirs.size() + 4);
    out.add(MARK_MINE);
    out.addAll(mine);
    out.add(MARK_BASE);
    out.addAll(base);
    out.add(MARK_SEPARATOR);
    out.addAll(theirs);
    out.add(MARK_THEIRS);
    return out;
  }

  /** True when {@code lines} still hold a conflict marker. */
  public static boolean hasMarkers(List<String> lines) {
    return lines.stream()
        .anyMatch(
            l ->
                l.startsWith("<<<<<<< ")
                    || l.startsWith("||||||| ")
                    || l.equals(MARK_SEPARATOR)
                    || l.startsWith(">>>>>>> "));
  }
}

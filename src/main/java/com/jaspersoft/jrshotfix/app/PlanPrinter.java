package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.PlanSummary;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.hotfix.HotfixPlans;
import com.jaspersoft.jrshotfix.redact.Redactor;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link Plan} for the operator before confirmation (spec §6.2): the home it runs in,
 * header, summary block, steps grouped by phase, numbered the way the progress renderer numbers
 * them and named by the step id the journal and {@code runs show} use, the fingerprint, and the
 * reminder that nothing has changed yet. Invariants: step numbers are 1-based positions in {@code
 * plan.steps()}; every text line is redacted.
 */
final class PlanPrinter {

  private static final int MAX_LISTED_FILES = 20;

  /** Lines of one block of readme text shown in the preview. */
  private static final int MAX_QUOTED_LINES = 8;

  private PlanPrinter() {}

  /** Two-digit step number for position {@code index} (0-based). */
  static String number(int index) {
    return String.format(java.util.Locale.ROOT, "%02d", index + 1);
  }

  static void print(PrintWriter out, Home home, Plan plan, Ansi ansi, Redactor redactor) {
    PlanSummary s = plan.summary();
    List<String> lines = new ArrayList<>();
    lines.add("home: " + home.root());
    lines.add("Plan  " + s.operation() + "  " + s.target());
    lines.add("Summary");
    TextTable summary = new TextTable();
    summary.row("  files", count(s.filesTouched().size(), "file"));
    for (String f : listed(s.filesTouched())) {
      summary.row("", f);
    }
    if (!s.resourcesTouched().isEmpty()) {
      summary.row("  resources", count(s.resourcesTouched().size(), "resource"));
      for (String r : listed(s.resourcesTouched().stream().map(Object::toString).toList())) {
        summary.row("", r);
      }
    }
    summary.row("  service", s.serviceRestart() ? "restart required" : "no restart");
    summary.row("  strategy", s.strategy().isBlank() ? "-" : s.strategy());
    summary.row(
        "  backups",
        s.backupLocations().isEmpty()
            ? "none"
            : String.join(", ", s.backupLocations().stream().map(Path::toString).toList()));
    if (s.rollbackPointsByPhase().isEmpty()) {
      summary.row("  rollback", "per phase boundary");
    } else {
      boolean first = true;
      for (Map.Entry<String, String> e : s.rollbackPointsByPhase().entrySet()) {
        summary.row(first ? "  rollback" : "", e.getKey() + " -> " + e.getValue());
        first = false;
      }
    }
    lines.addAll(summary.lines());
    lines.addAll(warnings(s.warnings()));
    lines.add("Steps");
    TextTable steps = new TextTable();
    List<Step> all = plan.steps();
    List<String> headers = new ArrayList<>();
    String phase = "";
    for (int i = 0; i < all.size(); i++) {
      Step step = all.get(i);
      headers.add(step.phase().equals(phase) ? "" : "  " + step.phase());
      phase = step.phase();
      String title = step.irreversible() ? step.title() + " (irreversible)" : step.title();
      steps.row("  " + number(i), step.id(), title, ansi.dim(step.detail()));
    }
    List<String> stepLines = steps.lines();
    for (int i = 0; i < all.size(); i++) {
      if (!headers.get(i).isEmpty()) {
        lines.add(headers.get(i));
      }
      lines.add(stepLines.get(i));
    }
    lines.add("Fingerprint  " + plan.fingerprint().value());
    lines.add("nothing has changed");
    for (String line : lines) {
      out.println(redactor.redact(line.stripTrailing()));
    }
    out.flush();
  }

  /**
   * The warnings as printed. A block of the package readme's own lines is shown up to {@link
   * #MAX_QUOTED_LINES}, followed by where the rest is: the preview is read before an outage and the
   * whole text is a command away.
   */
  private static List<String> warnings(List<String> warnings) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < warnings.size()) {
      String w = warnings.get(i);
      if (!w.startsWith(HotfixPlans.QUOTE_PREFIX)) {
        out.add("  ! " + w);
        i++;
        continue;
      }
      int end = i;
      while (end < warnings.size() && warnings.get(end).startsWith(HotfixPlans.QUOTE_PREFIX)) {
        end++;
      }
      int shown = Math.min(end - i, MAX_QUOTED_LINES);
      for (String quoted : warnings.subList(i, i + shown)) {
        out.add("  !   | " + quoted.substring(HotfixPlans.QUOTE_PREFIX.length()));
      }
      if (end - i > shown) {
        out.add(
            "  !   | ... "
                + count(end - i - shown, "more line")
                + ": `jrs-hotfix verify <package.zip>` prints them all, and a run saves them to"
                + " notes.txt in its directory");
      }
      i = end;
    }
    return out;
  }

  private static String count(int n, String noun) {
    return n + " " + (n == 1 ? noun : noun + "s");
  }

  private static List<String> listed(List<?> items) {
    List<String> out = new ArrayList<>();
    int shown = Math.min(items.size(), MAX_LISTED_FILES);
    for (int i = 0; i < shown; i++) {
      out.add(items.get(i).toString());
    }
    if (items.size() > shown) {
      out.add("... and " + (items.size() - shown) + " more");
    }
    return out;
  }
}

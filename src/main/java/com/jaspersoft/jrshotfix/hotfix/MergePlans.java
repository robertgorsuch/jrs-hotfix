package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.SiteDecisions;
import com.jaspersoft.jrshotfix.platform.Lists;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A prepared merge as the apply plan uses it (0.2 design, section 5). Invariants: a merge with a
 * file still waiting for the operator is never planned with; a merge is used only for the package
 * it was prepared for and only while every file on disk is as the merge found it, or already as the
 * apply leaves it (a run in progress); nothing under the installation is written here.
 */
final class MergePlans {

  private final HotfixRuntime rt;

  MergePlans(HotfixRuntime rt) {
    this.rt = rt;
  }

  private String prefix() {
    return PackagePaths.WEBAPPS_PREFIX + rt.settings().webappName() + "/";
  }

  /** The merge of {@code id}, fit to be planned with: known, and nothing in it left to decide. */
  MergeDoc forApply(String id) {
    MergeDoc doc =
        rt.merges()
            .load(id)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        "unknown merge " + id,
                        "run `jrs-hotfix merge status` for the merges in this home"));
    if (!doc.blocking().isEmpty()) {
      throw blocked(doc);
    }
    return doc;
  }

  /** The refusal for a merge the operator still has to decide files of. */
  static HotfixException blocked(MergeDoc doc) {
    List<String> waiting =
        doc.blocking().stream().map(i -> i.path() + " (" + i.state() + ")").toList();
    return new HotfixException(
        HotfixException.PRECHECK,
        waiting.size()
            + " file(s) changed by both this site and "
            + doc.hotfixId()
            + " wait for your decision in merge "
            + doc.id()
            + ": "
            + Lists.firstAndMore(waiting, 8)
            + "; nothing was changed on the server",
        "`jrs-hotfix merge status "
            + doc.id()
            + "` lists them and `jrs-hotfix merge show "
            + doc.id()
            + " <path>` shows one; resolve each with `jrs-hotfix merge resolve "
            + doc.id()
            + " <path> --merged | --mine | --theirs`, then run `jrs-hotfix apply <package.zip>"
            + " --merge "
            + doc.id()
            + "`");
  }

  /** What {@code doc} decided, as the package reader asks for it. */
  SiteDecisions decisions(MergeDoc doc) {
    String prefix = prefix();
    Map<String, SiteDecisions.Decision> byPath = new HashMap<>();
    for (MergeDoc.Item item : doc.files()) {
      SiteDecisions.Decision d;
      if (item.state().keeps()) {
        d =
            SiteDecisions.Decision.keep(
                item.note().isEmpty() ? "kept by the operator" : item.note());
      } else if (item.state().merged()) {
        d =
            SiteDecisions.Decision.merged(
                item.merged().orElseThrow(),
                rt.merges().side(doc.id(), item.path(), MergeWorkspace.MERGED),
                item.note());
      } else {
        d = SiteDecisions.Decision.plain();
      }
      byPath.put(prefix + item.path(), d);
    }
    return new SiteDecisions() {
      @Override
      public boolean active() {
        return true;
      }

      @Override
      public Optional<Decision> of(String packagePath) {
        return Optional.ofNullable(byPath.get(packagePath));
      }
    };
  }

  /**
   * Refuses (exit 2) a merge prepared for another package, or one whose files are no longer as it
   * found them. A file that is already as the apply leaves it is fine: that is a run in progress.
   */
  void check(MergeDoc doc, PackageContents contents) {
    if (!doc.packageSha256().equals(contents.sha256())) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "merge " + doc.id() + " was prepared for another package (" + doc.hotfixId() + ")",
          "prepare a merge for this package with `jrs-hotfix merge prepare <package.zip>`");
    }
    List<String> changed = changedSince(doc);
    if (!changed.isEmpty()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "changed since the merge was prepared: " + Lists.firstAndMore(changed, 8),
          "prepare the merge again with `jrs-hotfix merge prepare <package.zip>`; what you"
              + " resolved in "
              + doc.id()
              + " is still in its directory");
    }
  }

  /** The webapp paths whose file is neither as {@code doc} found it nor as the apply leaves it. */
  List<String> changedSince(MergeDoc doc) {
    List<String> changed = new ArrayList<>();
    Path webapp = rt.settings().webappDir();
    for (MergeDoc.Item item : doc.files()) {
      Optional<String> now = FileTarget.hashOf(rt.files(), webapp.resolve(item.path()));
      if (!now.equals(item.mine()) && !now.equals(item.lands())) {
        changed.add(item.path());
      }
    }
    return changed;
  }

  /** The plan preview's lines about what the merge does, under their three headings. */
  static List<String> changes(MergeDoc doc) {
    List<String> merged = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    List<String> overwritten = new ArrayList<>();
    for (MergeDoc.Item item : doc.files()) {
      String line = "  " + item.path() + "  (" + item.fileClass() + ")";
      switch (item.state()) {
        case AUTO -> merged.add(line + "  merged automatically");
        case RESOLVED ->
            merged.add(line + "  resolved by " + item.resolvedBy().orElse("the operator"));
        case KEPT, KEPT_MINE -> kept.add(line);
        case OVERWRITTEN -> overwritten.add(line);
        case TOOK_THEIRS -> overwritten.add(line + "  the operator took the hotfix's copy");
        case PLAIN, REVIEW, CONFLICT -> {}
      }
    }
    List<String> out = new ArrayList<>();
    out.add("merge " + doc.id() + " against " + String.join(" + ", doc.baselines()) + ":");
    section(out, "Merged", merged);
    section(out, "Kept as the site has it", kept);
    section(out, "Replaced although the site changed it", overwritten);
    if (out.size() == 1) {
      out.add("  no file of the package was changed by this site");
    }
    return out;
  }

  private static void section(List<String> out, String heading, List<String> lines) {
    if (!lines.isEmpty()) {
      out.add(heading + " (" + lines.size() + ")");
      out.addAll(lines);
    }
  }

  /** Prepares a new merge of {@code contents} against {@code view}. */
  MergeDoc prepare(
      Path file, PackageContents contents, BaseView view, MergeWorkspace.OnConflict onConflict) {
    Set<String> known = HotfixPlans.vendorFiles(rt);
    Path webapp = rt.settings().webappDir();
    try {
      return rt.merges()
          .prepare(
              file,
              contents,
              view,
              new MergeWorkspace.Site(
                  rt.settings().webappName(), webapp, rt.files(), known::contains),
              onConflict);
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot prepare the merge: " + e.getMessage(),
          "check free space and permissions under " + rt.home().merges(),
          e);
    }
  }

  /**
   * The newest merge that can stand in for a new one: prepared for this package against these
   * baselines, under the same conflict rule when one was asked for, and with every file on disk
   * still as it found it. What the operator already resolved in it is then not lost.
   */
  Optional<MergeDoc> reusable(
      PackageContents contents, BaseView view, Optional<MergeWorkspace.OnConflict> asked) {
    for (MergeDoc doc : rt.merges().list()) {
      boolean sameRule = asked.isEmpty() || asked.get().name().equalsIgnoreCase(doc.onConflict());
      if (doc.packageSha256().equals(contents.sha256())
          && doc.baselines().equals(view.ids())
          && sameRule
          && unchanged(doc)) {
        return Optional.of(doc);
      }
    }
    return Optional.empty();
  }

  private boolean unchanged(MergeDoc doc) {
    Path webapp = rt.settings().webappDir();
    for (MergeDoc.Item item : doc.files()) {
      if (!FileTarget.hashOf(rt.files(), webapp.resolve(item.path())).equals(item.mine())) {
        return false;
      }
    }
    return true;
  }
}

package com.jaspersoft.jrshotfix.baseline;

import com.jaspersoft.jrshotfix.baseline.BaselineManifest.BaseFile;
import com.jaspersoft.jrshotfix.baseline.BaselineManifest.Kind;
import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.platform.FileOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The vendor's webapp at the level this installation states: the release baseline, overlaid with
 * the baseline of the hotfix whose build the webapp states (0.2 design, section 2). Invariants: a
 * path the hotfix ships is the hotfix's file; a path the hotfix's readme deletes is absent; every
 * other path is the release's; the view exists only when the baselines fit the installation (see
 * {@link #resolve}); nothing is written.
 */
public final class BaseView {

  /** The share of the base's libraries that must be on disk unchanged for a baseline to fit. */
  static final double FIT = 0.95;

  private static final String LIB = "WEB-INF/lib/";

  private final BaselineStore store;
  private final BaselineManifest release;
  private final Optional<BaselineManifest> hotfix;
  private final Map<String, BaseFile> releaseFiles = new HashMap<>();
  private final Map<String, BaseFile> hotfixFiles = new HashMap<>();

  private BaseView(
      BaselineStore store, BaselineManifest release, Optional<BaselineManifest> hotfix) {
    this.store = store;
    this.release = release;
    this.hotfix = hotfix;
    release.files().forEach(f -> releaseFiles.put(f.path(), f));
    hotfix.ifPresent(h -> h.files().forEach(f -> hotfixFiles.put(f.path(), f)));
  }

  /**
   * A view, or why there is none and the command that would give one. {@code present} says whether
   * any release baseline exists for this release: without one the tool works as it did before
   * baselines, with one and no view it must not guess.
   */
  public record Resolution(
      Optional<BaseView> view, boolean present, String problem, String remediation) {

    static Resolution of(BaseView view) {
      return new Resolution(Optional.of(view), true, "", "");
    }

    static Resolution none(boolean present, String problem, String remediation) {
      return new Resolution(Optional.empty(), present, problem, remediation);
    }
  }

  /**
   * The view for the webapp at {@code webappDir}. The build the webapp states selects the hotfix
   * baseline; the release baseline is the one whose libraries, seen through that hotfix, are on
   * disk unchanged for at least 95 in 100. A build that is neither a release baseline's nor a
   * hotfix baseline's has no view: an older base would show the vendor's own changes as the site's.
   */
  public static Resolution resolve(BaselineStore store, Path webappDir, FileOps files) {
    Optional<InstalledBuild> stated = InstalledBuild.ofWebapp(webappDir);
    List<BaselineManifest> all = store.list();
    List<BaselineManifest> releases =
        all.stream()
            .filter(b -> b.kind() == Kind.RELEASE)
            .filter(b -> stated.isEmpty() || b.release().equals(stated.get().release()))
            .toList();
    if (releases.isEmpty()) {
      return Resolution.none(
          false,
          "there is no baseline for "
              + stated.map(b -> "release " + b.release()).orElse("this webapp"),
          "run `jrs-hotfix baseline add <jasperserver-pro.war>` with the vendor's WAR of the"
              + " release that was installed");
    }
    if (stated.isEmpty()) {
      return Resolution.none(
          true,
          "the webapp states no build (WEB-INF/internal/jasperserver-pro.properties), so its"
              + " baseline cannot be chosen",
          "restore that file from the vendor's WAR or the newest hotfix package");
    }
    String build = stated.get().build();
    Optional<BaselineManifest> hotfix =
        all.stream()
            .filter(b -> b.kind() == Kind.HOTFIX)
            .filter(b -> b.release().equals(stated.get().release()) && b.build().equals(build))
            .findFirst();
    List<BaselineManifest> candidates =
        hotfix.isPresent()
            ? releases
            : releases.stream().filter(r -> r.build().equals(build)).toList();
    if (candidates.isEmpty()) {
      return Resolution.none(
          true,
          "the webapp states build " + build + " and there is no baseline for that build",
          "add the hotfix package that brought it with `jrs-hotfix baseline add <package.zip>`,"
              + " or the vendor's WAR of that build");
    }
    BaseView best = null;
    double bestFit = -1;
    for (BaselineManifest candidate : candidates) {
      BaseView view = new BaseView(store, candidate, hotfix);
      double fit = view.fit(webappDir, files);
      if (fit > bestFit) {
        best = view;
        bestFit = fit;
      }
    }
    if (bestFit < FIT) {
      return Resolution.none(
          true,
          "baseline "
              + Objects.requireNonNull(best).describe()
              + " does not fit this installation: only "
              + String.format(Locale.ROOT, "%.0f", bestFit * 100)
              + " in 100 of its libraries are in WEB-INF/lib unchanged",
          "add the WAR this server was installed from with `jrs-hotfix baseline add`, or remove"
              + " the wrong one with `jrs-hotfix baseline remove <id>`");
    }
    return Resolution.of(Objects.requireNonNull(best));
  }

  /** The share of this view's libraries that are on disk with the base's hash. */
  private double fit(Path webappDir, FileOps files) {
    int total = 0;
    int same = 0;
    for (String path : paths()) {
      if (!path.startsWith(LIB) || !path.endsWith(".jar")) {
        continue;
      }
      total++;
      Path onDisk = webappDir.resolve(path);
      BaseFile base = file(path).orElseThrow();
      try {
        if (Files.isRegularFile(onDisk)
            && Files.size(onDisk) == base.size()
            && files.sha256(onDisk).equals(base.sha256())) {
          same++;
        }
      } catch (IOException e) {
        throw new UncheckedIOException("cannot hash " + onDisk, e);
      }
    }
    return total == 0 ? 1 : (double) same / total;
  }

  /** The vendor's file at {@code path}; empty when the vendor has none at this level. */
  public Optional<BaseFile> file(String path) {
    BaseFile fromHotfix = hotfixFiles.get(path);
    if (fromHotfix != null) {
      return Optional.of(fromHotfix);
    }
    if (hotfix.isPresent() && hotfix.get().deletes(path)) {
      return Optional.empty();
    }
    return Optional.ofNullable(releaseFiles.get(path));
  }

  /** The stored content of the vendor's file at {@code path}; empty when none is kept. */
  public Optional<Path> payload(String path) {
    BaseFile fromHotfix = hotfixFiles.get(path);
    if (fromHotfix != null) {
      return stored(hotfix.orElseThrow().id(), fromHotfix);
    }
    return file(path).flatMap(f -> stored(release.id(), f));
  }

  private Optional<Path> stored(String id, BaseFile f) {
    Path p = store.payload(id, f.path());
    return f.payload() && Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
  }

  /**
   * True when the installer fills this file in for one site: the release's copy holds a
   * placeholder, so every server differs from the vendor's file there.
   */
  public boolean installer(String path) {
    BaseFile f = releaseFiles.get(path);
    return f != null && f.installer();
  }

  /** Every path the vendor has at this level, sorted. */
  public Set<String> paths() {
    Set<String> out = new TreeSet<>();
    for (String p : releaseFiles.keySet()) {
      if (hotfix.isEmpty() || hotfixFiles.containsKey(p) || !hotfix.get().deletes(p)) {
        out.add(p);
      }
    }
    out.addAll(hotfixFiles.keySet());
    return out;
  }

  public BaselineManifest release() {
    return release;
  }

  public Optional<BaselineManifest> hotfix() {
    return hotfix;
  }

  /** The ids this view is made of, for a report and for the ledger. */
  public List<String> ids() {
    List<String> ids = new ArrayList<>();
    ids.add(release.id());
    hotfix.ifPresent(h -> ids.add(h.id()));
    return ids;
  }

  public String describe() {
    return String.join(" + ", ids());
  }
}

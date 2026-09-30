package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CheckResult;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Removes the pages Tomcat compiled for the webapp, {@code <tomcatDir>/work/Catalina/localhost/
 * <webappName>}, as the vendor's readme requires after the files of a hotfix are copied and again
 * after they are put back: Tomcat recompiles a page only when the page is newer than its compiled
 * class, so a restored page, which is older, would go on being served from the hotfix's class.
 * Invariants: it runs between the change of the files and the start of the service; only that one
 * directory is removed, and only when the webapp name is a plain directory name; a cache that is
 * not there is not an error.
 */
final class JspCacheStep implements Step {

  static final String ID = "clear-jsp-cache";

  private final HotfixRuntime rt;
  private final String phase;
  private final String id;

  JspCacheStep(HotfixRuntime rt, String phase, String id) {
    this.rt = Objects.requireNonNull(rt, "rt");
    this.phase = Objects.requireNonNull(phase, "phase");
    this.id = Objects.requireNonNull(id, "id");
  }

  /** The cache of the configured webapp; empty when its name cannot be a directory of its own. */
  Optional<Path> cache() {
    String webapp = rt.settings().webappName();
    if (!PackagePaths.isPlainFileName(webapp)) {
      return Optional.empty();
    }
    Path work = rt.settings().tomcatDir().resolve("work").toAbsolutePath().normalize();
    Path cache = work.resolve("Catalina").resolve("localhost").resolve(webapp).normalize();
    return cache.startsWith(work) ? Optional.of(cache) : Optional.empty();
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String title() {
    return "remove the pages Tomcat compiled";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return cache().map(c -> c + "; Tomcat compiles them again on first use").orElse("");
  }

  /**
   * Irreversible by design: what is removed is derived from the webapp's pages, Tomcat builds it
   * again from whatever pages are in place when the service starts, and putting the old classes
   * back would serve pages that no longer match the files, whichever way the run ends.
   */
  @Override
  public boolean irreversible() {
    return true;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Optional<Path> cache = cache();
    if (cache.isEmpty()) {
      return StepResult.ok();
    }
    try {
      Trees.deleteRecursively(cache.get());
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot remove the compiled pages in " + cache.get() + ": " + e.getMessage(),
          "end the process holding the file, or remove the directory by hand while the service"
              + " is stopped, then run again",
          List.of(cache.get()),
          List.of());
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}

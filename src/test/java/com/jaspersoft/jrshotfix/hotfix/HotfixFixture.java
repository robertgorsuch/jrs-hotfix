package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.RunOptions;
import com.jaspersoft.jrshotfix.engine.RunOutcome;
import com.jaspersoft.jrshotfix.engine.Runner;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.FileJournal;
import com.jaspersoft.jrshotfix.state.Ledger;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fake installation with the standard package's files, a home beside it, and a runtime over a
 * {@link FakePlatform} backed by real files, so a plan runs end to end on disk with a recording
 * service controller and a probe that always answers "up".
 */
public final class HotfixFixture implements AutoCloseable {
  public static final String ID = "JRSHF-10.0.0-20260730-0457";
  public static final String FOO = "webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar";
  public static final String NEW = "webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar";
  public static final String BAR = "webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar";
  public static final String FOO_OLDER = "webapps/jasperserver-pro/WEB-INF/lib/foo-1.0.0.jar";
  public static final String TOOL = "buildomatic/lib/tool-2.0.jar";

  public final Path root;
  public final Home home;
  public final Settings settings;
  public final FakePlatform platform;
  public final Ledger ledger;
  public final SnapshotStore snapshots;
  public final HotfixRuntime runtime;
  public final HotfixPlans plans;
  public final PackagePaths paths;
  public final FileJournal journal;

  private HotfixFixture(Path root, PackagePaths paths) throws IOException {
    this.root = root;
    this.paths = paths;
    this.home = new Home(root.resolve("jrs/jrs-hotfix"));
    Files.createDirectories(home.root());
    this.settings =
        new Settings(
            paths.installDir(),
            paths.tomcatDir(),
            "jasperserver-pro",
            ServiceConfig.Kind.MANUAL,
            Optional.empty(),
            Optional.empty(),
            5,
            Optional.empty(),
            URI.create("http://localhost:8080/jasperserver-pro"));
    this.platform = new FakePlatform(Platform.OsFamily.LINUX, home.root());
    platform.realFiles = true;
    this.ledger = new Ledger(home);
    this.snapshots = new SnapshotStore(home, platform.files());
    this.runtime =
        new HotfixRuntime(
            home,
            settings,
            platform,
            ledger,
            snapshots,
            Clock.systemUTC(),
            Sleeper.none(),
            Optional::empty);
    this.plans = new HotfixPlans(runtime);
    this.journal = new FileJournal(home, Clock.systemUTC());
  }

  public static HotfixFixture create(Path root) throws IOException {
    return new HotfixFixture(root, Packages.install(root.resolve("jrs")));
  }

  /** The standard package: foo replaced, new added, bar and foo-1.0.0 deleted, tool replaced. */
  public Path packageFile() throws IOException {
    Path file = root.resolve("dl/hotfix.zip");
    return Files.isRegularFile(file) ? file : Packages.standard(file);
  }

  public Plan plan() throws IOException {
    return plans.planApply(new HotfixPlans.ApplyArgs(packageFile(), true));
  }

  public Context ctx(String runId) {
    return new Context(runId, home, platform, new CancellationToken(), Map.of());
  }

  public RunOutcome run(Plan plan, String runId) {
    Runner runner = new Runner(journal, EventSink.discard(), Clock.systemUTC(), Sleeper.none());
    return runner.run(plan, ctx(runId), plan.fingerprint(), RunOptions.DEFAULT);
  }

  public Path target(String packagePath) {
    return paths.resolve(packagePath);
  }

  public String sha(Path p) throws IOException {
    return platform.files().sha256(p);
  }

  public static List<String> ids(Plan plan) {
    return plan.steps().stream().map(Step::id).toList();
  }

  public static Step step(Plan plan, String id) {
    return plan.steps().stream()
        .filter(s -> s.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no step " + id + " in " + ids(plan)));
  }

  @Override
  public void close() {
    // the temp directory is JUnit's to remove
  }
}

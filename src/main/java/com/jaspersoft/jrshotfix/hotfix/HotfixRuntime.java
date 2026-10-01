package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.baseline.BaseView;
import com.jaspersoft.jrshotfix.baseline.BaselineStore;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import com.jaspersoft.jrshotfix.service.CompanionDatabase;
import com.jaspersoft.jrshotfix.service.ServerProbe;
import com.jaspersoft.jrshotfix.service.ServiceRuntime;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.UndoStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a hotfix step needs, built once per command. Invariants: no field is null; nothing
 * here does I/O at construction; the service controller is created from the settings on every call
 * and never cached.
 */
public record HotfixRuntime(
    Home home,
    Settings settings,
    Platform platform,
    UndoStore undo,
    SnapshotStore snapshots,
    Clock clock,
    Sleeper sleeper,
    ServerProbe probe)
    implements ServiceRuntime {

  public HotfixRuntime {
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(undo, "undo");
    Objects.requireNonNull(snapshots, "snapshots");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(sleeper, "sleeper");
    Objects.requireNonNull(probe, "probe");
  }

  public FileOps files() {
    return platform.files();
  }

  /** The vendor's files this installation is compared with. */
  public BaselineStore baselines() {
    return new BaselineStore(home, clock);
  }

  /** The prepared merges of this home. */
  public MergeWorkspace merges() {
    return new MergeWorkspace(home, clock);
  }

  /** The vendor's webapp at the level this installation states, or why it is not known. */
  public BaseView.Resolution baseView() {
    return BaseView.resolve(baselines(), settings.webappDir(), files());
  }

  public PackagePaths paths() {
    return new PackagePaths(settings.installDir(), settings.tomcatDir());
  }

  @Override
  public ServiceController controller() {
    return platform.services(settings.toServiceConfig());
  }

  @Override
  public Optional<ServiceController> databaseController() {
    return CompanionDatabase.controller(platform, settings.toServiceConfig());
  }

  @Override
  public Duration serviceTimeout() {
    return Duration.ofSeconds(settings.stopTimeoutSeconds());
  }
}

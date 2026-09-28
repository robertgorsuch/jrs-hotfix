package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.InstallScan;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.ProcessRunner;
import com.jaspersoft.jrshotfix.platform.RunningTomcats;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import com.jaspersoft.jrshotfix.platform.TomcatLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A {@link Platform} that forwards everything, so a test can override one method. */
abstract class DelegatingPlatform implements Platform {

  private final Platform delegate;

  DelegatingPlatform(Platform delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public OsFamily os() {
    return delegate.os();
  }

  @Override
  public Arch arch() {
    return delegate.arch();
  }

  @Override
  public ServiceController services(ServiceConfig cfg) {
    return delegate.services(cfg);
  }

  @Override
  public FileOps files() {
    return delegate.files();
  }

  @Override
  public ProcessRunner processes() {
    return delegate.processes();
  }

  @Override
  public Path defaultHome() {
    return delegate.defaultHome();
  }

  @Override
  public Optional<TomcatLayout> detectTomcat(Path installDir) {
    return delegate.detectTomcat(installDir);
  }

  @Override
  public List<Path> candidateInstallDirs() {
    return delegate.candidateInstallDirs();
  }

  @Override
  public InstallScan scanInstallDirs() {
    return delegate.scanInstallDirs();
  }

  @Override
  public RunningTomcats runningTomcats(Path dir) {
    return delegate.runningTomcats(dir);
  }
}

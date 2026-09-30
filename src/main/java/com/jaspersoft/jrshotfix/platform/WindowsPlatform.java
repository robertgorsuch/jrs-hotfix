package com.jaspersoft.jrshotfix.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link Platform} for Windows. Invariants: the default home is {@code %ProgramData%\jrs-hotfix}
 * when that tree can be written, otherwise {@code ~\.jrs-hotfix}; install candidates are, in order,
 * the install dirs of running Tomcats and the well-known Jaspersoft directories under {@code
 * C:\Jaspersoft} and {@code %ProgramFiles%}. The Uninstall registry keys were queried too until
 * 0.4.0: the bundled installer's directory is always one of the well-known ones, and the operator
 * can type any other.
 */
public final class WindowsPlatform extends AbstractPlatform {

  public WindowsPlatform(Arch arch, ProcessRunner runner, FileOps files, OperatorPrompt prompt) {
    this(arch, runner, files, prompt, new WindowsTomcatProcesses(runner));
  }

  /** With the process finder shared by this platform and its file operations (issue #38). */
  WindowsPlatform(
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      TomcatProcessFinder tomcats) {
    this(arch, runner, files, prompt, Optional.empty(), tomcats);
  }

  private WindowsPlatform(
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      Optional<Path> installDir,
      TomcatProcessFinder tomcats) {
    super(arch, runner, files, prompt, installDir, tomcats);
  }

  @Override
  public Platform withInstallDir(Path installDir) {
    return new WindowsPlatform(
        arch(), processes(), files(), prompt(), Optional.of(installDir), tomcats());
  }

  @Override
  public OsFamily os() {
    return OsFamily.WINDOWS;
  }

  @Override
  public Path defaultHome() {
    Path programData =
        Path.of(Optional.ofNullable(System.getenv("ProgramData")).orElse("C:\\ProgramData"));
    return homeOrFallback(programData);
  }

  @Override
  List<Path> wellKnownInstallDirs() {
    List<Path> candidates = new ArrayList<>();
    candidates.addAll(glob(Path.of("C:\\Jaspersoft"), "*"));
    Path programFiles =
        Path.of(Optional.ofNullable(System.getenv("ProgramFiles")).orElse("C:\\Program Files"));
    candidates.addAll(glob(programFiles, "jasperreports-server*"));
    candidates.addAll(glob(programFiles.resolve("Jaspersoft"), "*"));
    return candidates;
  }
}

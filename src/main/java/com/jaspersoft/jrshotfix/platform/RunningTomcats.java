package com.jaspersoft.jrshotfix.platform;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Tomcat JVMs running under one directory, as the process scan saw them, whatever {@code
 * service.kind} says (issue #147). Invariants: {@link Scanned#pids()} holds only processes whose
 * command line or {@code catalina.home}/{@code catalina.base} places them under the directory;
 * {@link Scanned#unreadable()} holds processes whose command line this account cannot read (a JVM,
 * or a {@code tomcatN.exe} service wrapper run by another account) and which might be that Tomcat,
 * because they listen on one of its {@code server.xml} ports or those ports are not known
 * (ADR-0014); both lists are in ascending order; a scan that could not run is {@link Unavailable}
 * with the reason, never an empty {@link Scanned}.
 */
public sealed interface RunningTomcats {

  /** The scan ran. */
  record Scanned(List<Long> pids, List<Long> unreadable) implements RunningTomcats {
    public Scanned {
      pids = pids.stream().sorted().toList();
      unreadable = unreadable.stream().sorted().toList();
    }
  }

  /** The scan could not run, or this platform does not scan processes. */
  record Unavailable(String reason) implements RunningTomcats {
    public Unavailable {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Classifies one listing from {@code finder}, service wrappers included, against {@code dir}. */
  static RunningTomcats scan(TomcatProcessFinder finder, Path dir) {
    try {
      return classify(
          finder.findWithServiceWrappers(), Optional.of(dir), ServerXml.portsUnder(dir));
    } catch (TomcatScanException e) {
      return new Unavailable("the process scan failed: " + e.getMessage());
    }
  }

  /**
   * A {@link ServiceController.State} from one listing from {@code finder}, service wrappers left
   * out. Invariants: {@code RUNNING} when a readable process belongs to the install directory (any
   * Tomcat when none is given); otherwise {@code UNKNOWN} when the scan could not run, or when an
   * {@link TomcatProcessFinder.TomcatProcess#opaque() opaque} JVM listens on one of {@code
   * watchedPorts} (or none are given), because it may be that Tomcat run by another account; {@code
   * STOPPED} only when the scan ran and nothing could be it; never {@code STARTING} or {@code
   * STOPPING}, because a process listing cannot distinguish a starting JVM from a serving one.
   * {@code UNKNOWN} rather than {@code STOPPED} on a blind scan is what makes the service steps
   * refuse instead of skipping a stop (issue #38, ADR-0014).
   */
  static ServiceController.State state(
      TomcatProcessFinder finder, Optional<Path> installDir, Set<Integer> watchedPorts) {
    Scanned scanned;
    try {
      scanned = classify(finder.find(), installDir, watchedPorts);
    } catch (TomcatScanException e) {
      Diag.debug("process scan failed: {}", e.getMessage());
      return ServiceController.State.UNKNOWN;
    }
    if (!scanned.pids().isEmpty()) {
      return ServiceController.State.RUNNING;
    }
    return scanned.unreadable().isEmpty()
        ? ServiceController.State.STOPPED
        : ServiceController.State.UNKNOWN;
  }

  /**
   * Readable processes by install-dir match (any, with no dir), unreadable ones by an overlap with
   * {@code ports} (any, with no ports known).
   */
  private static Scanned classify(
      List<TomcatProcessFinder.TomcatProcess> found, Optional<Path> dir, Set<Integer> ports) {
    List<Long> pids =
        found.stream()
            .filter(p -> !p.opaque() && dir.map(p::belongsTo).orElse(true))
            .map(TomcatProcessFinder.TomcatProcess::pid)
            .toList();
    List<Long> unreadable =
        found.stream()
            .filter(TomcatProcessFinder.TomcatProcess::opaque)
            .filter(p -> ports.isEmpty() || !Collections.disjoint(p.listeningPorts(), ports))
            .map(TomcatProcessFinder.TomcatProcess::pid)
            .toList();
    return new Scanned(pids, unreadable);
  }
}

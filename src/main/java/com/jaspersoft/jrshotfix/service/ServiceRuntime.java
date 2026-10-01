package com.jaspersoft.jrshotfix.service;

import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * What the shared service steps need from an operation's runtime. Invariants: {@link #controller()}
 * is created from the configuration on every call and never cached, so a stopped-then-started
 * service is always re-queried; {@link #probe()} contacts the server rather than returning a
 * memoised answer, which is what makes wait-for-server a real probe.
 */
public interface ServiceRuntime {

  ServiceController controller();

  /**
   * The bundled database service that must be up before Tomcat starts, when this host registers one
   * beside the Tomcat service ({@link CompanionDatabase}, installation guide p.51); empty by
   * default and for every kind without a service manager.
   */
  default Optional<ServiceController> databaseController() {
    return Optional.empty();
  }

  /** The configured stop timeout ({@code service.stopTimeoutSeconds}). */
  Duration serviceTimeout();

  Clock clock();

  Sleeper sleeper();

  /** Asks the server whether it is up; never cached. */
  ServerProbe probe();
}

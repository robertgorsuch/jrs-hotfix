package com.jaspersoft.jrshotfix.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServerProbeTest {
  @Test
  void should_answer_empty_when_server_info_returns_200() throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    s.createContext(
        "/jasperserver-pro/rest_v2/serverInfo",
        ex -> {
          byte[] b = "{\"version\":\"10.0.0\"}".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    s.start();
    try {
      ServerProbe p = ServerProbe.http(URI.create("http://" + hostPort(s) + "/jasperserver-pro"));
      assertThat(p.problem()).isEmpty();
    } finally {
      s.stop(0);
    }
  }

  @Test
  void should_name_the_status_when_the_server_answers_503() throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    s.createContext(
        "/jasperserver-pro/rest_v2/serverInfo",
        ex -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });
    s.start();
    try {
      Optional<String> problem =
          ServerProbe.http(URI.create("http://" + hostPort(s) + "/jasperserver-pro")).problem();
      // AssertJ's OptionalAssert#contains is exact-value equality, not substring containment, so
      // the reason (which also names the URL) is unwrapped before checking it names the status.
      assertThat(problem).isPresent();
      assertThat(problem.orElseThrow()).contains("503");
    } finally {
      s.stop(0);
    }
  }

  @Test
  void should_name_the_connection_failure_when_nothing_listens() {
    assertThat(ServerProbe.http(URI.create("http://127.0.0.1:1/jasperserver-pro")).problem())
        .isPresent();
  }

  /**
   * A server that holds every request for {@code millis} before it answers 200, as Tomcat holds the
   * requests that arrive while the webapp is still being deployed; counts what it was asked.
   */
  private static HttpServer slow(long millis, AtomicInteger asked) throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.createContext(
        "/jasperserver-pro/rest_v2/serverInfo",
        ex -> {
          asked.incrementAndGet();
          try {
            Thread.sleep(millis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    s.start();
    return s;
  }

  @Test
  void should_give_up_on_a_held_request_when_asked_without_patience() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    HttpServer s = slow(1500, asked);
    try {
      ServerProbe p =
          ServerProbe.http(
              URI.create("http://" + hostPort(s) + "/jasperserver-pro"), Duration.ofMillis(300));
      assertThat(p.problem()).isPresent();
    } finally {
      s.stop(0);
    }
  }

  @Test
  void should_wait_for_the_answer_to_its_one_request_when_given_patience() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    HttpServer s = slow(1500, asked);
    try {
      ServerProbe p =
          ServerProbe.http(
              URI.create("http://" + hostPort(s) + "/jasperserver-pro"), Duration.ofMillis(300));
      assertThat(p.problem(Duration.ofSeconds(30))).isEmpty();
      assertThat(asked).hasValue(1);
    } finally {
      s.stop(0);
    }
  }

  /** {@code host:port} of a just-started loopback server, without a hostname lookup. */
  private static String hostPort(HttpServer s) {
    InetSocketAddress addr = s.getAddress();
    return addr.getHostString() + ":" + addr.getPort();
  }
}

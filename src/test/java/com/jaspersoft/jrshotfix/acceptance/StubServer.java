package com.jaspersoft.jrshotfix.acceptance;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for the JasperReports Server the wait step probes. Invariants: it listens on the
 * loopback address on an ephemeral port chosen by the OS, so parallel forks never collide; it
 * answers 200 with a small JSON body on {@code /jasperserver-pro/rest_v2/serverInfo} and 404
 * elsewhere.
 */
final class StubServer implements AutoCloseable {

  static final String CONTEXT = "/jasperserver-pro";

  private final HttpServer server;

  StubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        CONTEXT + "/rest_v2/serverInfo",
        exchange -> {
          byte[] body = "{\"version\":\"10.0.0\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  /** The base URL the settings point the wait probe at. */
  String baseUrl() {
    return "http://127.0.0.1:" + port() + CONTEXT;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}

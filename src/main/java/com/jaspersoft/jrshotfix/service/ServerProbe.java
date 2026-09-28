package com.jaspersoft.jrshotfix.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks the server whether it is up: an unauthenticated GET of {@code /rest_v2/serverInfo}.
 * Invariants: no credentials, no redirects, five-second timeouts; the answer is empty for 200 and a
 * one-line reason otherwise; nothing is cached between calls.
 */
@FunctionalInterface
public interface ServerProbe {
  Optional<String> problem();

  static ServerProbe http(URI baseUrl) {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    URI target = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/rest_v2/serverInfo");
    return () -> {
      try {
        HttpResponse<Void> r =
            client.send(
                HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return r.statusCode() == 200
            ? Optional.empty()
            : Optional.of("HTTP " + r.statusCode() + " from " + target);
      } catch (IOException e) {
        return Optional.of(target + ": " + e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.of("interrupted");
      }
    };
  }
}

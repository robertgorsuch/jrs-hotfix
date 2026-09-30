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
 * Invariants: no credentials, no redirects, a five-second connect timeout; the answer is empty for
 * 200 and a one-line reason otherwise; nothing is cached between calls. {@link #problem()} gives up
 * on its request after five seconds and is for a server that should be answering; {@link
 * #problem(Duration)} lets its request wait as long as it is told, and is for a server that is
 * starting. Tomcat holds the requests that reach it while the webapp is being deployed and answers
 * them all at once when it is up, given up on or not, so a caller that gives up and asks again
 * sends a starting server a burst of simultaneous first requests.
 */
@FunctionalInterface
public interface ServerProbe {
  Optional<String> problem();

  /** As {@link #problem()}, letting the request wait up to {@code patience} for its answer. */
  default Optional<String> problem(Duration patience) {
    return problem();
  }

  static ServerProbe http(URI baseUrl) {
    return http(baseUrl, Duration.ofSeconds(5));
  }

  /** A probe whose {@link #problem()} gives up on its request after {@code quick}. */
  static ServerProbe http(URI baseUrl, Duration quick) {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    URI target = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/rest_v2/serverInfo");
    return new ServerProbe() {
      @Override
      public Optional<String> problem() {
        return problem(quick);
      }

      @Override
      public Optional<String> problem(Duration patience) {
        try {
          HttpResponse<Void> r =
              client.send(
                  HttpRequest.newBuilder(target).timeout(patience).GET().build(),
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
      }
    };
  }
}

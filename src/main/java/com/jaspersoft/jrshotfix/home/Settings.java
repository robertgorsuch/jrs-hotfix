package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the tool knows about one installation. Invariants: paths are absolute and normalised;
 * the base URL is used by the wait probe only; there are no credentials anywhere in this record.
 */
public record Settings(
    Path installDir,
    Path tomcatDir,
    String webappName,
    ServiceConfig.Kind serviceKind,
    Optional<String> serviceName,
    Optional<Path> serviceScriptPath,
    int stopTimeoutSeconds,
    Optional<Integer> forceStopAfterSeconds,
    URI baseUrl) {

  public static final List<String> KEYS =
      List.of(
          "installDir",
          "tomcatDir",
          "webappName",
          "service.kind",
          "service.name",
          "service.scriptPath",
          "service.stopTimeoutSeconds",
          "service.forceStopAfterSeconds",
          "baseUrl");

  public Settings {
    installDir = installDir.toAbsolutePath().normalize();
    tomcatDir = tomcatDir.toAbsolutePath().normalize();
    Objects.requireNonNull(webappName, "webappName");
    Objects.requireNonNull(serviceKind, "serviceKind");
    Objects.requireNonNull(serviceName, "serviceName");
    serviceScriptPath = serviceScriptPath.map(p -> p.toAbsolutePath().normalize());
    Objects.requireNonNull(forceStopAfterSeconds, "forceStopAfterSeconds");
    Objects.requireNonNull(baseUrl, "baseUrl");
  }

  public Path webappDir() {
    return tomcatDir.resolve("webapps").resolve(webappName);
  }

  public ServiceConfig toServiceConfig() {
    return new ServiceConfig(
        serviceKind,
        serviceName,
        serviceScriptPath,
        Duration.ofSeconds(stopTimeoutSeconds),
        forceStopAfterSeconds.map(Duration::ofSeconds));
  }

  public String fingerprintInput() {
    return installDir + "|" + tomcatDir + "|" + webappName;
  }

  public Map<String, String> keys() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("installDir", installDir.toString());
    m.put("tomcatDir", tomcatDir.toString());
    m.put("webappName", webappName);
    m.put("service.kind", serviceKind.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    m.put("service.name", serviceName.orElse(""));
    m.put("service.scriptPath", serviceScriptPath.map(Path::toString).orElse(""));
    m.put("service.stopTimeoutSeconds", Integer.toString(stopTimeoutSeconds));
    m.put("service.forceStopAfterSeconds", forceStopAfterSeconds.map(String::valueOf).orElse(""));
    m.put("baseUrl", baseUrl.toString());
    return m;
  }

  public Settings withKey(String key, String value) {
    Optional<String> text = value.isBlank() ? Optional.empty() : Optional.of(value);
    return switch (key) {
      case "installDir" ->
          new Settings(
              Path.of(value),
              tomcatDir,
              webappName,
              serviceKind,
              serviceName,
              serviceScriptPath,
              stopTimeoutSeconds,
              forceStopAfterSeconds,
              baseUrl);
      case "tomcatDir" ->
          new Settings(
              installDir,
              Path.of(value),
              webappName,
              serviceKind,
              serviceName,
              serviceScriptPath,
              stopTimeoutSeconds,
              forceStopAfterSeconds,
              baseUrl);
      case "webappName" ->
          new Settings(
              installDir,
              tomcatDir,
              value,
              serviceKind,
              serviceName,
              serviceScriptPath,
              stopTimeoutSeconds,
              forceStopAfterSeconds,
              baseUrl);
      case "service.kind" ->
          new Settings(
              installDir,
              tomcatDir,
              webappName,
              ServiceConfig.Kind.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')),
              serviceName,
              serviceScriptPath,
              stopTimeoutSeconds,
              forceStopAfterSeconds,
              baseUrl);
      case "service.name" ->
          new Settings(
              installDir,
              tomcatDir,
              webappName,
              serviceKind,
              text,
              serviceScriptPath,
              stopTimeoutSeconds,
              forceStopAfterSeconds,
              baseUrl);
      case "service.scriptPath" ->
          new Settings(
              installDir,
              tomcatDir,
              webappName,
              serviceKind,
              serviceName,
              text.map(Path::of),
              stopTimeoutSeconds,
              forceStopAfterSeconds,
              baseUrl);
      case "service.stopTimeoutSeconds" ->
          new Settings(
              installDir,
              tomcatDir,
              webappName,
              serviceKind,
              serviceName,
              serviceScriptPath,
              Integer.parseInt(value),
              forceStopAfterSeconds,
              baseUrl);
      case "service.forceStopAfterSeconds" ->
          new Settings(
              installDir,
              tomcatDir,
              webappName,
              serviceKind,
              serviceName,
              serviceScriptPath,
              stopTimeoutSeconds,
              text.map(Integer::parseInt),
              baseUrl);
      case "baseUrl" ->
          new Settings(
              installDir,
              tomcatDir,
              webappName,
              serviceKind,
              serviceName,
              serviceScriptPath,
              stopTimeoutSeconds,
              forceStopAfterSeconds,
              URI.create(value));
      default ->
          throw new IllegalArgumentException(
              "unknown setting " + key + "; the keys are " + String.join(", ", KEYS));
    };
  }
}

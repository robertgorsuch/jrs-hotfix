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
 * the base URL is used by the wait probe only; there are no credentials anywhere in this record;
 * the two merge settings are absent until the operator sets them, and settings written before they
 * existed read back without them.
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
    URI baseUrl,
    Optional<String> mergeOnConflict) {

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
          "baseUrl",
          "merge.onConflict");

  /** What {@code merge.onConflict} may be. */
  public static final List<String> ON_CONFLICT = List.of("ask", "mine", "theirs", "fail");

  public Settings {
    installDir = installDir.toAbsolutePath().normalize();
    tomcatDir = tomcatDir.toAbsolutePath().normalize();
    Objects.requireNonNull(webappName, "webappName");
    Objects.requireNonNull(serviceKind, "serviceKind");
    Objects.requireNonNull(serviceName, "serviceName");
    serviceScriptPath = serviceScriptPath.map(p -> p.toAbsolutePath().normalize());
    Objects.requireNonNull(forceStopAfterSeconds, "forceStopAfterSeconds");
    Objects.requireNonNull(baseUrl, "baseUrl");
    mergeOnConflict = mergeOnConflict == null ? Optional.empty() : mergeOnConflict;
  }

  /** Settings without the merge setting. */
  public Settings(
      Path installDir,
      Path tomcatDir,
      String webappName,
      ServiceConfig.Kind serviceKind,
      Optional<String> serviceName,
      Optional<Path> serviceScriptPath,
      int stopTimeoutSeconds,
      Optional<Integer> forceStopAfterSeconds,
      URI baseUrl) {
    this(
        installDir,
        tomcatDir,
        webappName,
        serviceKind,
        serviceName,
        serviceScriptPath,
        stopTimeoutSeconds,
        forceStopAfterSeconds,
        baseUrl,
        Optional.empty());
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
    m.put("merge.onConflict", mergeOnConflict.orElse(""));
    return m;
  }

  public Settings withKey(String key, String value) {
    Optional<String> text = value.isBlank() ? Optional.empty() : Optional.of(value);
    Path install = installDir;
    Path tomcat = tomcatDir;
    String webapp = webappName;
    ServiceConfig.Kind kind = serviceKind;
    Optional<String> name = serviceName;
    Optional<Path> script = serviceScriptPath;
    int stopTimeout = stopTimeoutSeconds;
    Optional<Integer> forceStop = forceStopAfterSeconds;
    URI base = baseUrl;
    Optional<String> onConflict = mergeOnConflict;
    switch (key) {
      case "installDir" -> install = Path.of(value);
      case "tomcatDir" -> tomcat = Path.of(value);
      case "webappName" -> webapp = value;
      case "service.kind" ->
          kind = ServiceConfig.Kind.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
      case "service.name" -> name = text;
      case "service.scriptPath" -> script = text.map(Path::of);
      case "service.stopTimeoutSeconds" -> stopTimeout = Integer.parseInt(value);
      case "service.forceStopAfterSeconds" -> forceStop = text.map(Integer::parseInt);
      case "baseUrl" -> base = URI.create(value);
      case "merge.onConflict" -> {
        onConflict = text.map(v -> v.strip().toLowerCase(Locale.ROOT));
        if (onConflict.isPresent() && !ON_CONFLICT.contains(onConflict.get())) {
          throw new IllegalArgumentException(
              "merge.onConflict must be one of " + String.join(", ", ON_CONFLICT));
        }
      }
      default ->
          throw new IllegalArgumentException(
              "unknown setting " + key + "; the keys are " + String.join(", ", KEYS));
    }
    return new Settings(
        install, tomcat, webapp, kind, name, script, stopTimeout, forceStop, base, onConflict);
  }
}

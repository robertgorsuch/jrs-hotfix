package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.platform.InstallScan;
import com.jaspersoft.jrshotfix.platform.LinuxInit;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.ProcessRunner;
import com.jaspersoft.jrshotfix.platform.ServerXml;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import com.jaspersoft.jrshotfix.platform.TomcatLayout;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default settings for an installation, read from disk and the host's service manager. Invariants:
 * read-only; a failing service listing means "no service found", never an error; the operator
 * confirms every value in the wizard.
 */
public final class Detection {
  private static final Pattern SC_SERVICE_NAME =
      Pattern.compile("^\\s*SERVICE_NAME:\\s*(\\S.*?)\\s*$");
  private static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);

  /**
   * {@code sc.exe} arguments used to list every Windows service (mirrors {@code
   * CompanionDatabase.windowsServices} in jrsctl, jrs module: {@code sc.exe query state= all}; the
   * jrs-hotfix design brief's draft list added {@code type= service}, which this copy drops to stay
   * byte-for-byte with the tested jrsctl command).
   */
  public static final List<String> SC_QUERY = List.of("sc.exe", "query", "state=", "all");

  private Detection() {}

  public static InstallScan candidates(Platform platform) {
    return platform.scanInstallDirs();
  }

  public static Optional<Settings> defaults(Platform platform, Path installDir) {
    Optional<TomcatLayout> layout = platform.detectTomcat(installDir);
    if (layout.isEmpty()) {
      return Optional.empty();
    }
    Path tomcat = layout.get().tomcatDir();
    String webapp = layout.get().webappName();
    int port = ServerXml.httpPort(tomcat.resolve("conf").resolve("server.xml")).orElse(8080);
    URI base = URI.create("http://localhost:" + port + "/" + webapp);
    Service service = service(platform, installDir, tomcat);
    return Optional.of(
        new Settings(
            installDir,
            tomcat,
            webapp,
            service.kind(),
            service.name(),
            service.script(),
            180,
            Optional.empty(),
            base));
  }

  /** How Tomcat is controlled: a service name, a script, or neither for a manual install. */
  private record Service(ServiceConfig.Kind kind, Optional<String> name, Optional<Path> script) {}

  /**
   * The first that applies: a Windows service or systemd unit named for JasperReports Server or
   * Tomcat, the install's {@code ctlscript}, Tomcat's {@code catalina} script, else manual.
   */
  private static Service service(Platform platform, Path installDir, Path tomcat) {
    if (platform.os() == Platform.OsFamily.WINDOWS) {
      Optional<String> svc =
          windowsServices(platform.processes()).stream()
              .filter(n -> lower(n).contains("jasper") && lower(n).contains("tomcat"))
              .findFirst();
      if (svc.isPresent()) {
        return new Service(ServiceConfig.Kind.WINDOWS_SERVICE, svc, Optional.empty());
      }
    } else {
      Optional<String> unit =
          LinuxInit.systemdUnits(platform.processes()).stream()
              .filter(n -> lower(n).contains("jasper") || lower(n).contains("tomcat"))
              .findFirst();
      if (unit.isPresent()) {
        return new Service(ServiceConfig.Kind.SYSTEMD, unit, Optional.empty());
      }
    }
    for (String s : List.of("ctlscript.sh", "ctlscript.bat")) {
      Path script = installDir.resolve(s);
      if (Files.isRegularFile(script)) {
        return new Service(ServiceConfig.Kind.CTLSCRIPT, Optional.empty(), Optional.of(script));
      }
    }
    for (String s : List.of("catalina.sh", "catalina.bat")) {
      Path script = tomcat.resolve("bin").resolve(s);
      if (Files.isRegularFile(script)) {
        return new Service(ServiceConfig.Kind.CATALINA, Optional.empty(), Optional.of(script));
      }
    }
    return new Service(ServiceConfig.Kind.MANUAL, Optional.empty(), Optional.empty());
  }

  /**
   * The Windows service names {@code sc.exe query} lists, each once, in listing order. Mirrors
   * {@code CompanionDatabase.windowsServices} exactly (same command, same {@code SERVICE_NAME:}
   * pattern); never throws, an unreadable listing yields an empty list.
   */
  static List<String> windowsServices(ProcessRunner runner) {
    List<String> names = new ArrayList<>();
    try {
      runner.run(
          new ProcessRunner.Request(SC_QUERY, Optional.empty(), Map.of(), LIST_TIMEOUT),
          line -> {
            if (line.stream() != ProcessRunner.OutputLine.Stream.STDOUT) {
              return;
            }
            Matcher m = SC_SERVICE_NAME.matcher(line.text());
            if (m.matches()) {
              names.add(m.group(1));
            }
          });
    } catch (RuntimeException e) {
      // no listing, no service found: detection continues without a Windows-service match
    }
    return names;
  }

  private static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }
}

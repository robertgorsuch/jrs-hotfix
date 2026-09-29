package com.jaspersoft.jrshotfix.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.home.SettingsStore;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * One test's world: a fake installation with a stand-in Tomcat, a jrs-hotfix home whose settings
 * name it (service kind {@code catalina}, base URL at this fixture's own {@link StubServer}), the
 * standard package and a later one. The fake {@code bin/catalina} script starts and stops a process
 * the tool's own Tomcat scan recognises as this installation's Tomcat (a renamed {@code PING.EXE}
 * at {@code bin/tomcat9.exe} on Windows, a {@code sleep} marked {@code -Dcatalina.base=<tomcat>}
 * elsewhere), as jrsctl's Phase 8 fixture did. Invariants: everything lives under the directory
 * given to {@link #create}; the stand-in is running when {@link #create} returns and is stopped by
 * {@link #close}; the unit test helpers only build inputs (packages, the install tree,
 * settings.json), and the tool itself is only ever driven through {@link #cli}.
 */
final class Fixture implements AutoCloseable {

  static final String STANDARD_ID = "JRSHF-10.0.0-20260730-0457";
  static final String LIB = "webapps/jasperserver-pro/WEB-INF/lib/";

  final Path home;
  final Cli cli;
  private final StubServer server;
  private final Path installDir;
  private final Path tomcatDir;
  private final Path pkg;
  private final Path laterPkg;

  private Fixture(Path tmp, StubServer server, PackagePaths install) throws IOException {
    this.server = server;
    this.installDir = install.installDir();
    this.tomcatDir = install.tomcatDir();
    this.home = tmp.resolve("home");
    this.pkg = Packages.standard(tmp.resolve("packages/hotfix-standard.zip"));
    this.laterPkg = Packages.later(tmp.resolve("packages/hotfix-later.zip"));
    // the connector is the stub's port: with no server.xml, any Java service this account
    // cannot read would make the Tomcat's state UNKNOWN
    write(
        tomcatDir.resolve("conf/server.xml"),
        "<Server><Service><Connector port=\""
            + server.port()
            + "\" protocol=\"HTTP/1.1\"/></Service></Server>");
    writeCatalina();
    Settings settings =
        new Settings(
            installDir,
            tomcatDir,
            "jasperserver-pro",
            ServiceConfig.Kind.CATALINA,
            Optional.empty(),
            Optional.of(catalina()),
            60,
            Optional.empty(),
            URI.create(server.baseUrl()));
    SettingsStore.save(new Home(home), settings);
    this.cli = new Cli(home, Map.of());
  }

  static Fixture create(Path tmp) throws Exception {
    StubServer server = new StubServer();
    Fixture f;
    try {
      f = new Fixture(tmp, server, Packages.install(tmp.resolve("install")));
    } catch (IOException | RuntimeException e) {
      server.close();
      throw e;
    }
    try {
      f.startTomcat();
    } catch (Exception | AssertionError e) {
      f.close();
      throw e;
    }
    return f;
  }

  Path pkg() {
    return pkg;
  }

  Path laterPkg() {
    return laterPkg;
  }

  /** A file of the installation: {@code webapps/...} under Tomcat, anything else under install. */
  Path target(String relative) {
    return relative.startsWith("webapps/")
        ? tomcatDir.resolve(relative)
        : installDir.resolve(relative);
  }

  /** The installation's root, for a walk over every file. */
  Path installDir() {
    return installDir;
  }

  /** Whether the stand-in Tomcat runs, seen the way the tool's own process scan sees it. */
  boolean tomcatRunning() {
    if (Cli.windows()) {
      String exe = tomcatDir.resolve("bin").resolve("tomcat9.exe").toString();
      return ProcessHandle.allProcesses()
          .map(ProcessHandle::info)
          .map(ProcessHandle.Info::command)
          .anyMatch(c -> c.map(exe::equalsIgnoreCase).orElse(false));
    }
    String marker = "-Dcatalina.base=" + tomcatDir.toAbsolutePath().normalize();
    return ProcessHandle.allProcesses()
        .map(ProcessHandle::info)
        .map(ProcessHandle.Info::commandLine)
        .anyMatch(c -> c.map(line -> line.contains(marker)).orElse(false));
  }

  /** The ids of the runs whose {@code run.json} has no {@code endedAt}. */
  List<String> pendingRunIds() throws IOException {
    List<String> ids = new ArrayList<>();
    Path runs = home.resolve("runs");
    if (!Files.isDirectory(runs)) {
      return ids;
    }
    ObjectMapper mapper = new ObjectMapper();
    try (Stream<Path> dirs = Files.list(runs)) {
      for (Path dir : dirs.sorted().toList()) {
        Path runJson = dir.resolve("run.json");
        if (!Files.isRegularFile(runJson)) {
          continue;
        }
        JsonNode node = mapper.readTree(Files.readString(runJson));
        if (!node.hasNonNull("endedAt")) {
          ids.add(dir.getFileName().toString());
        }
      }
    }
    return ids;
  }

  /** The one pending run; fails the test when there is not exactly one. */
  String pendingRunId() throws IOException {
    List<String> ids = pendingRunIds();
    assertThat(ids).as("pending runs under %s", home.resolve("runs")).hasSize(1);
    return ids.get(0);
  }

  /** The row of {@code list} output for {@code id}; fails the test when there is none. */
  String listRow(String id) throws IOException, InterruptedException {
    Cli.Result list = cli.run("list").assertExit(0);
    return list.stdout()
        .lines()
        .filter(l -> l.startsWith(id + " "))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + id + " in:\n" + list.stdout()));
  }

  /** Stops the stand-in Tomcat (so nothing outlives the test) and the stub server. */
  @Override
  public void close() {
    try {
      script("stop");
      if (Cli.windows()) {
        Path exe = tomcatDir.resolve("bin").resolve("tomcat9.exe");
        for (int i = 0; i < 50 && Files.exists(exe); i++) {
          try {
            Files.delete(exe);
          } catch (IOException e) {
            Thread.sleep(200);
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot stop the stand-in Tomcat under " + tomcatDir, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      server.close();
    }
  }

  private void startTomcat() throws Exception {
    script("start");
    for (int i = 0; i < 300 && !tomcatRunning(); i++) {
      Thread.sleep(200);
    }
    assertThat(tomcatRunning()).as("stand-in Tomcat under %s", tomcatDir).isTrue();
  }

  private void script(String verb) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(List.of(catalina().toString(), verb)).start();
    p.getOutputStream().close();
    p.getInputStream().readAllBytes();
    p.getErrorStream().readAllBytes();
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("catalina " + verb + " did not finish in 60 s");
    }
  }

  private Path catalina() {
    return tomcatDir.resolve("bin").resolve(Cli.windows() ? "catalina.bat" : "catalina.sh");
  }

  private void writeCatalina() throws IOException {
    Path bin = Files.createDirectories(tomcatDir.resolve("bin"));
    if (Cli.windows()) {
      Files.copy(
          Path.of(
              System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "PING.EXE"),
          bin.resolve("tomcat9.exe"),
          StandardCopyOption.REPLACE_EXISTING);
      write(
          bin.resolve("catalina.bat"),
          "@echo off\r\n"
              + "set \"EXE=%~dp0tomcat9.exe\"\r\n"
              + "if \"%1\"==\"start\" (\r\n"
              + "  powershell -NoProfile -Command \"Start-Process -FilePath '%EXE%' -ArgumentList"
              + " '-n','900','127.0.0.1' -WindowStyle Hidden\"\r\n"
              + "  echo started\r\n"
              + "  exit /b 0\r\n"
              + ")\r\n"
              + "if \"%1\"==\"stop\" (\r\n"
              + "  powershell -NoProfile -Command \"Get-CimInstance Win32_Process -Filter"
              + " \\\"Name='tomcat9.exe'\\\" | Where-Object { $_.ExecutablePath -eq '%EXE%' } |"
              + " ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\"\r\n"
              + "  echo stopped\r\n"
              + "  exit /b 0\r\n"
              + ")\r\n"
              + "exit /b 0\r\n");
    } else {
      write(
          bin.resolve("catalina.sh"),
          "#!/bin/sh\n"
              + "TC=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n"
              + "MARKER=\"-Dcatalina.base=$TC\"\n"
              + "case \"$1\" in\n"
              + "  start) nohup sh -c \"sleep 900 # $MARKER\" >/dev/null 2>&1 </dev/null & ;;\n"
              + "  stop) pkill -f -- \"$MARKER\" || true ;;\n"
              + "esac\n"
              + "exit 0\n");
    }
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    if (!Cli.windows() && file.toString().endsWith(".sh")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }
}

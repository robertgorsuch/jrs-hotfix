package com.jaspersoft.jrshotfix.baseline;

import com.jaspersoft.jrshotfix.pkg.Packages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small vendor webapp for tests: the files of a release as a WAR and as an installation, with the
 * jars {@link Packages#install} lays down, so the standard package applies to it.
 */
public final class Wars {
  public static final String BUILD = "20260121_2317";
  public static final String RELEASE_ID = "release-10.0.0-PRO-" + BUILD;

  public static final String STAMPS = "WEB-INF/internal/jasperserver-pro.properties";
  public static final String WEB_XML = "WEB-INF/web.xml";
  public static final String CONTEXT = "WEB-INF/applicationContext.xml";
  public static final String QUARTZ = "WEB-INF/js.quartz.properties";
  public static final String SECURITY = "WEB-INF/classes/esapi/security.properties";
  public static final String LOGIN = "WEB-INF/jsp/login.jsp";
  public static final String SCRIPT = "scripts/app.js";
  public static final String CONTAINER = "META-INF/context.xml";
  public static final String LOGO = "images/logo.png";

  private Wars() {}

  /** The stamps file of {@code date} and {@code time}, indented as the vendor's build writes it. */
  public static String stamps(String date, String time) {
    return "PRO_VERSION=10.0.0\n  BUILD_DATE_STAMP=" + date + "\n  BUILD_TIME_STAMP=" + time + "\n";
  }

  /** The vendor's files, webapp path to content. */
  public static Map<String, String> vendor() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(STAMPS, stamps("20260121", "2317"));
    files.put(Packages.LIB + "jasperserver-api-10.0.0.jar", "api");
    files.put(Packages.LIB + "foo-1.2.3.jar", "old foo");
    files.put(Packages.LIB + "foo-1.0.0.jar", "older foo left by an earlier hotfix");
    files.put(Packages.LIB + "bar-0.9.jar", "bar");
    files.put(
        WEB_XML,
        "<web-app>\n"
            + "  <filter>\n"
            + "    <filter-name>security</filter-name>\n"
            + "  </filter>\n"
            + "  <servlet>\n"
            + "    <servlet-name>main</servlet-name>\n"
            + "  </servlet>\n"
            + "  <session-config>\n"
            + "    <session-timeout>20</session-timeout>\n"
            + "  </session-config>\n"
            + "</web-app>\n");
    files.put(
        CONTEXT,
        "<beans>\n"
            + "  <bean id=\"engine\" class=\"com.example.Engine\">\n"
            + "    <property name=\"threads\" value=\"4\"/>\n"
            + "  </bean>\n"
            + "  <bean id=\"cache\" class=\"com.example.Cache\"/>\n"
            + "</beans>\n");
    files.put(
        QUARTZ, "# scheduler\nreport.scheduler.web.deployment.uri=http://@@BITROCK_HOST@@/x\n");
    files.put(SECURITY, "# security\nmax.upload=10\nallow.list=a,b\nstrict=true\n");
    files.put(LOGIN, "<html>\n<body>\n<h1>Login</h1>\n<p>Welcome</p>\n</body>\n</html>\n");
    files.put(SCRIPT, "console.log('vendor');\n");
    files.put(CONTAINER, "<Context><Resource username=\"@@BITROCK_DB_USER@@\"/></Context>\n");
    files.put(LOGO, "PNG");
    return files;
  }

  /** Writes {@code files} as a WAR. */
  public static Path war(Path file, Map<String, String> files) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    files.forEach((path, text) -> entries.put(path, text.getBytes(StandardCharsets.ISO_8859_1)));
    return Packages.zip(file, entries);
  }

  /** Writes {@code files} under {@code webappDir}, replacing what is there. */
  public static void install(Path webappDir, Map<String, String> files) throws IOException {
    for (Map.Entry<String, String> e : files.entrySet()) {
      write(webappDir, e.getKey(), e.getValue());
    }
  }

  /**
   * The vendor's files as an installer leaves them: the placeholders filled in with this server's
   * values.
   */
  public static void installAsTheInstallerDoes(Path webappDir) throws IOException {
    install(webappDir, vendor());
    write(
        webappDir,
        QUARTZ,
        "# scheduler\nreport.scheduler.web.deployment.uri=http://reports:8081/x\n");
    write(webappDir, CONTAINER, "<Context><Resource username=\"jasperdb\"/></Context>\n");
  }

  public static void write(Path webappDir, String path, String text) throws IOException {
    Path file = webappDir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.ISO_8859_1);
  }
}

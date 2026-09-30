package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.merge.MergeDoc;
import com.jaspersoft.jrshotfix.merge.MergeWorkspace;
import com.jaspersoft.jrshotfix.pkg.Packages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A customized server for tests: the {@link HotfixFixture} installation holding the vendor's webapp
 * of {@link Wars} as an installer leaves it, the release baseline added, a cumulative hotfix that
 * changes settings, pages, a script and a library, and the edits a site makes to the same files.
 */
public final class SiteFixture {
  public static final String HOTFIX_ID = "JRSHF-10.0.0-20260730-0457";

  /** What the hotfix ships for the settings file both it and the site change. */
  public static final String HOTFIX_SECURITY =
      "# security\nmax.upload=20\nallow.list=a,b\nstrict=true\nfresh=1\n";

  public final HotfixFixture f;
  public final Path webapp;
  private final Path root;

  private SiteFixture(HotfixFixture f, Path root) {
    this.f = f;
    this.root = root;
    this.webapp = f.settings.webappDir();
  }

  public static SiteFixture create(Path root) throws IOException {
    return create(HotfixFixture.create(root), root);
  }

  /** Over an installation that already exists, such as a command test's. */
  public static SiteFixture create(HotfixFixture f, Path root) throws IOException {
    SiteFixture s = new SiteFixture(f, root);
    Wars.installAsTheInstallerDoes(s.webapp);
    f.runtime
        .baselines()
        .addRelease(Wars.war(root.resolve("dl/jasperserver-pro.war"), Wars.vendor()));
    return s;
  }

  /** The vendor's copy of {@code path} in the release. */
  public static String vendor(String path) {
    return Wars.vendor().get(path);
  }

  /** What the cumulative hotfix ships under the webapp. */
  public static Map<String, String> hotfixPayload() {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(Packages.LIB + "foo-1.2.3.jar", "patched foo");
    payload.put(Wars.WEB_XML, vendor(Wars.WEB_XML).replace(">main<", ">main2<"));
    payload.put(Wars.SECURITY, HOTFIX_SECURITY);
    payload.put(Wars.CONTEXT, vendor(Wars.CONTEXT));
    payload.put(Wars.LOGIN, vendor(Wars.LOGIN).replace("<h1>Login</h1>", "<h1>Sign in</h1>"));
    payload.put(Wars.SCRIPT, "console.log('hotfix');\n");
    payload.put(
        Wars.QUARTZ,
        "# scheduler\nreport.scheduler.web.deployment.uri=http://localhost:8080/x\nnew.key=1\n");
    payload.put(
        Wars.CONTAINER,
        "<Context><Resource username=\"@@BITROCK_DB_USER@@\" maxTotal=\"50\"/></Context>\n");
    payload.put(Wars.STAMPS, Wars.stamps("20260730", "0457"));
    return payload;
  }

  /** The cumulative hotfix as a package. */
  public Path hotfix() throws IOException {
    return packageOf("hotfix-site.zip", "[20260730_0457]", hotfixPayload(), null);
  }

  /** A package of {@code build} that ships {@code payload} under the webapp. */
  public Path packageOf(String name, String build, Map<String, String> payload, String readme)
      throws IOException {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put(
        "readme.txt",
        Packages.OUTER_README.replace("[20260730_0457]", build).getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(payload, readme));
    return Packages.zip(root.resolve("dl/" + name), outer);
  }

  /** Writes one file of the webapp as the site has it. */
  public void site(String path, String content) throws IOException {
    Wars.write(webapp, path, content);
  }

  public String read(String path) throws IOException {
    return Files.readString(webapp.resolve(path), StandardCharsets.ISO_8859_1);
  }

  /** Edits that touch other places than the hotfix does: every merge comes out clean. */
  public void customizeWithoutCollisions() throws IOException {
    site(Wars.WEB_XML, vendor(Wars.WEB_XML).replace(">20<", ">60<"));
    site(Wars.SECURITY, vendor(Wars.SECURITY).replace("allow.list=a,b", "allow.list=a,b,c"));
    site(Wars.CONTEXT, vendor(Wars.CONTEXT).replace("\"4\"", "\"16\""));
    site(Wars.LOGIN, vendor(Wars.LOGIN).replace("<p>Welcome</p>", "<p>Welcome to ACME</p>"));
    site(Wars.SCRIPT, "console.log('site');\n");
  }

  /** Edits in the very places the hotfix changes. */
  public void customizeWithCollisions() throws IOException {
    site(Wars.WEB_XML, vendor(Wars.WEB_XML).replace(">main<", ">site-main<"));
    site(Wars.SECURITY, vendor(Wars.SECURITY).replace("max.upload=10", "max.upload=50"));
  }

  /** A new merge of {@code zip} under the {@code ask} rule. */
  public MergeDoc prepare(Path zip) {
    return prepare(zip, MergeWorkspace.OnConflict.ASK);
  }

  public MergeDoc prepare(Path zip, MergeWorkspace.OnConflict rule) {
    return f.plans.prepareMerge(zip, Optional.of(rule), rule, false);
  }

  /** Resolves {@code path} of {@code doc} and returns the merge as it then is. */
  public MergeDoc resolve(MergeDoc doc, String path, MergeWorkspace.Choice choice)
      throws IOException {
    return f.runtime.merges().resolve(doc.id(), path, choice, Optional.empty(), "tester");
  }

  public MergeDoc.Item item(MergeDoc doc, String path) {
    return doc.file(path).orElseThrow(() -> new AssertionError("no " + path + " in " + doc.id()));
  }

  /** The workspace's copy of one side of {@code path}. */
  public Path side(MergeDoc doc, String path, String side) {
    return f.runtime.merges().side(doc.id(), path, side);
  }
}

package com.jaspersoft.jrshotfix.baseline;

import com.jaspersoft.jrshotfix.baseline.BaselineManifest.BaseFile;
import com.jaspersoft.jrshotfix.baseline.BaselineManifest.Kind;
import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.InstalledBuild;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.pkg.PackageStager;
import com.jaspersoft.jrshotfix.platform.Durability;
import com.jaspersoft.jrshotfix.platform.Sums;
import com.jaspersoft.jrshotfix.platform.Trees;
import com.jaspersoft.jrshotfix.platform.Zips;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The vendor's files this installation is compared with, {@code baselines/} under the home (0.2
 * design, section 2): one directory per baseline, holding {@code manifest.json} with a hash for
 * every file of the source and {@code payload/} with the content of the mergeable ones. Invariants:
 * a baseline is visible only once its manifest exists, and it is built in a directory of its own
 * and renamed into place, so a crash leaves nothing half-written that a reader would take for a
 * baseline; a WAR is read as a stream and never unpacked whole; a file larger than {@link
 * #MAX_PAYLOAD_BYTES} has a hash and no payload; nothing under the installation is written or
 * changed.
 */
public final class BaselineStore {

  /** A mergeable file larger than this is compared by hash and never merged. */
  public static final int MAX_PAYLOAD_BYTES = 1 << 20;

  /** What the installer leaves in a file it fills in for one site. */
  private static final byte[] PLACEHOLDER = "@@BITROCK_".getBytes(StandardCharsets.ISO_8859_1);

  private static final String MANIFEST = "manifest.json";
  private static final String PAYLOAD = "payload";
  private static final String EDITION = "PRO";

  private final Home home;
  private final Clock clock;

  public BaselineStore(Home home, Clock clock) {
    this.home = Objects.requireNonNull(home, "home");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Every baseline, release baselines first, each kind by id. */
  public List<BaselineManifest> list() {
    Path root = home.baselines();
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<BaselineManifest> out = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path dir : dirs.filter(Files::isDirectory).toList()) {
        read(dir.resolve(MANIFEST)).ifPresent(out::add);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + root, e);
    }
    out.sort(Comparator.comparing(BaselineManifest::kind).thenComparing(BaselineManifest::id));
    return List.copyOf(out);
  }

  public Optional<BaselineManifest> find(String id) {
    if (!PackagePaths.isPlainFileName(id)) {
      return Optional.empty();
    }
    return read(home.baselines().resolve(id).resolve(MANIFEST));
  }

  /** Where the content of {@code path} is stored in baseline {@code id}, whether or not it is. */
  public Path payload(String id, String path) {
    return home.baselines().resolve(id).resolve(PAYLOAD).resolve(path);
  }

  /** Removes one baseline; false when there is none of that id. */
  public boolean remove(String id) throws IOException {
    if (find(id).isEmpty()) {
      return false;
    }
    Trees.deleteRecursively(home.baselines().resolve(id));
    return true;
  }

  /**
   * Fills a release baseline from the vendor's webapp: a WAR, an unpacked webapp (a directory with
   * {@code WEB-INF}), or a directory that holds {@code jasperserver-pro.war}. The release and the
   * build are read from the webapp's own {@code WEB-INF/internal/jasperserver-pro.properties}; a
   * baseline of the same id is replaced.
   */
  public BaselineManifest addRelease(Path source) throws IOException {
    Path from = source.toAbsolutePath().normalize();
    if (Files.isDirectory(from) && !Files.isDirectory(from.resolve("WEB-INF"))) {
      Path war = from.resolve("jasperserver-pro.war");
      if (!Files.isRegularFile(war)) {
        throw notAWebapp(from, "it holds neither WEB-INF nor jasperserver-pro.war");
      }
      from = war;
    }
    Path building = building();
    try {
      List<BaseFile> files =
          Files.isDirectory(from) ? readTree(from, building) : readWar(from, building);
      Optional<InstalledBuild> stated = InstalledBuild.ofWebapp(building.resolve(PAYLOAD));
      if (stated.isEmpty()) {
        throw notAWebapp(
            from, "it states no release and build in WEB-INF/internal/jasperserver-pro.properties");
      }
      if (!stated.get().release().startsWith("10.")) {
        throw notAWebapp(from, "it is release " + stated.get().release());
      }
      String release = stated.get().release();
      String build = stated.get().build();
      BaselineManifest manifest =
          new BaselineManifest(
              BaselineManifest.releaseId(release, EDITION, build),
              Kind.RELEASE,
              release,
              EDITION,
              build,
              clock.instant(),
              from.toString(),
              files,
              List.of());
      publish(building, manifest);
      return manifest;
    } finally {
      Trees.deleteRecursively(building);
    }
  }

  /**
   * Fills the hotfix baseline of an official package from the files it ships under the webapp,
   * whatever happens to them on this server. A baseline of the same id is left as it is when it was
   * made from the same package, and replaced otherwise.
   */
  public BaselineManifest addHotfix(Path zip, PackageContents contents, String webappName)
      throws IOException {
    Optional<BaselineManifest> existing = find(contents.id());
    if (existing.isPresent() && existing.get().source().equals(contents.sha256())) {
      return existing.get();
    }
    String prefix = PackagePaths.WEBAPPS_PREFIX + webappName + "/";
    Map<String, String> wanted = new LinkedHashMap<>();
    List<BaseFile> files = new ArrayList<>();
    for (PackageContents.VendorFile f : contents.vendorFiles()) {
      if (!f.path().startsWith(prefix)) {
        continue;
      }
      String path = f.path().substring(prefix.length());
      FileClass cls = FileClass.of(path);
      boolean payload = cls.mergeable() && f.size() <= MAX_PAYLOAD_BYTES;
      if (payload) {
        wanted.put(f.path(), path);
      }
      files.add(
          new BaseFile(
              path,
              f.sha256(),
              f.size(),
              cls.text() ? Optional.of(f.textSha256()) : Optional.empty(),
              payload,
              false));
    }
    // what the readmes delete, and what the package superseded here: seen through this hotfix,
    // neither is the vendor's any more
    List<String> deleted =
        java.util.stream.Stream.concat(
                contents.deletions().stream(), contents.superseded().stream())
            .filter(d -> d.startsWith(prefix))
            .map(d -> d.substring(prefix.length()))
            .distinct()
            .toList();
    Path building = building();
    try {
      Path payloadDir = building.resolve(PAYLOAD);
      Files.createDirectories(payloadDir);
      PackageStager.stage(
          zip,
          contents,
          wanted.keySet(),
          payloadDir,
          packagePath -> payloadDir.resolve(wanted.get(packagePath)),
          new CancellationToken());
      for (String path : wanted.values()) {
        if (!Files.isRegularFile(payloadDir.resolve(path))) {
          throw new IOException(zip + " no longer holds " + path);
        }
      }
      BaselineManifest manifest =
          new BaselineManifest(
              contents.id(),
              Kind.HOTFIX,
              contents.release(),
              contents.edition(),
              contents.build(),
              clock.instant(),
              contents.sha256(),
              files,
              deleted);
      publish(building, manifest);
      return manifest;
    } finally {
      Trees.deleteRecursively(building);
    }
  }

  private Path building() throws IOException {
    Files.createDirectories(home.baselines());
    return Files.createTempDirectory(home.baselines(), ".adding-");
  }

  /** Writes the manifest into {@code building} and puts the directory in the baseline's place. */
  private void publish(Path building, BaselineManifest manifest) throws IOException {
    Path file = building.resolve(MANIFEST);
    Files.writeString(file, Json.write(manifest), StandardCharsets.UTF_8);
    Durability.sync(file);
    Path target = home.baselines().resolve(manifest.id());
    if (Files.exists(target)) {
      Trees.deleteRecursively(target);
    }
    Durability.move(building, target, StandardCopyOption.ATOMIC_MOVE);
    Durability.syncDirectory(home.baselines());
  }

  private static Optional<BaselineManifest> read(Path manifest) {
    if (!Files.isRegularFile(manifest)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          Json.mapper()
              .readValue(
                  Files.readString(manifest, StandardCharsets.UTF_8), BaselineManifest.class));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + manifest, e);
    }
  }

  private static List<BaseFile> readWar(Path war, Path building) throws IOException {
    List<BaseFile> files = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    try (InputStream in = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = Zips.nextFile(zip)) != null) {
        String path = Zips.name(entry);
        if (!PackagePaths.pathProblems(path).isEmpty()) {
          throw notAWebapp(war, "it holds an unusable path");
        }
        if (seen.add(path)) {
          files.add(baseFile(path, zip, building));
        }
      }
    }
    if (files.isEmpty()) {
      throw notAWebapp(war, "it is not a readable archive");
    }
    return files;
  }

  private static List<BaseFile> readTree(Path webapp, Path building) throws IOException {
    List<BaseFile> files = new ArrayList<>();
    List<Path> all;
    try (Stream<Path> walk = Files.walk(webapp)) {
      all = walk.filter(Files::isRegularFile).sorted().toList();
    }
    for (Path file : all) {
      String path = webapp.relativize(file).toString().replace('\\', '/');
      try (InputStream in = Files.newInputStream(file)) {
        files.add(baseFile(path, in, building));
      }
    }
    return files;
  }

  /**
   * Sums one vendor file from {@code in}, which is left open, and keeps its content under {@code
   * building} when it is mergeable and small enough.
   */
  private static BaseFile baseFile(String path, InputStream in, Path building) throws IOException {
    FileClass cls = FileClass.of(path);
    if (!cls.mergeable()) {
      Sums sums = Sums.of(in);
      return new BaseFile(
          path,
          sums.sha256(),
          sums.size(),
          cls.text() ? Optional.of(sums.textSha256()) : Optional.empty(),
          false,
          false);
    }
    Path copy = building.resolve(PAYLOAD).resolve(path);
    Files.createDirectories(copy.getParent());
    Sums sums;
    try (OutputStream out = Files.newOutputStream(copy)) {
      Sums.Sink sink = new Sums.Sink(out);
      in.transferTo(sink);
      sums = sink.sums();
    }
    if (sums.size() > MAX_PAYLOAD_BYTES) {
      Files.delete(copy);
      return new BaseFile(
          path, sums.sha256(), sums.size(), Optional.of(sums.textSha256()), false, false);
    }
    Durability.sync(copy);
    boolean installer = contains(Files.readAllBytes(copy), PLACEHOLDER);
    return new BaseFile(
        path, sums.sha256(), sums.size(), Optional.of(sums.textSha256()), true, installer);
  }

  private static boolean contains(byte[] bytes, byte[] needle) {
    outer:
    for (int i = 0; i <= bytes.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (bytes[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static HotfixException notAWebapp(Path source, String why) {
    return new HotfixException(
        HotfixException.UNSUPPORTED,
        source + " is not a JasperReports Server 10.x Pro webapp: " + why,
        "point `jrs-hotfix baseline add` at the vendor's jasperserver-pro.war, at the directory"
            + " that holds it, at an unpacked copy of it, or at an official hotfix ZIP");
  }
}

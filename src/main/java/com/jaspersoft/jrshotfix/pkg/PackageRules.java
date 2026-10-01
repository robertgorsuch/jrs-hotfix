package com.jaspersoft.jrshotfix.pkg;

import com.jaspersoft.jrshotfix.pkg.PackageReadme.Readme;
import com.jaspersoft.jrshotfix.platform.Zips;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The rules a package's change list is completed by once its payload is read: the readme's
 * deletions, the libraries the package supersedes, and the warning about settings files it
 * overwrites. Invariants: a deletion never covers a file the package itself lays down; nothing on
 * the server is touched here, it is only listed.
 */
final class PackageRules {

  /** Webapp settings files named in the overwrite warning; the rest is a count. */
  private static final int MAX_NAMED_SETTINGS = 20;

  private static final Pattern FRAGMENT_NAME = Pattern.compile("<name>\\s*([^<]+?)\\s*</name>");

  private PackageRules() {}

  /**
   * The readme's deletions as entries: the listed files, and the "Important" globs expanded against
   * this installation. Only files that are here now are listed, and never one the package itself
   * lays down.
   */
  static List<PackageContents.Entry> deletions(
      Readme readme,
      Set<String> added,
      PackagePaths paths,
      List<String> notes,
      SiteDecisions decisions) {
    List<PackageContents.Entry> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    // compared as resolved paths too: WindowsPath equality ignores case, as the file system does,
    // so a readme deletion spelt differently from a payload path never removes that payload
    Set<Path> laidDown = new HashSet<>();
    for (String a : added) {
      laidDown.add(paths.resolve(a).toAbsolutePath().normalize());
    }
    Set<Path> seenTargets = new HashSet<>();
    for (String path : readme.deleted()) {
      List<String> problems = PackagePaths.pathProblems(path);
      if (!problems.isEmpty()) {
        notes.add(skipped(path, problems));
        continue;
      }
      Path target = paths.resolve(path);
      if (!added.contains(path)
          && !laidDown.contains(target)
          && seen.add(path)
          && seenTargets.add(target)
          && Files.isRegularFile(target)) {
        out.add(deletion(path));
      }
    }
    int fromGlobs = 0;
    for (String glob : readme.globs()) {
      List<String> problems = PackagePaths.pathProblems(glob.replace('*', '_'));
      if (!problems.isEmpty()) {
        notes.add(skipped(glob, problems));
        continue;
      }
      for (String path : expand(glob, paths)) {
        Path target = paths.resolve(path);
        if (!added.contains(path)
            && !laidDown.contains(target)
            && seen.add(path)
            && seenTargets.add(target)) {
          if (decisions.of(path).filter(d -> d.kind() == SiteDecisions.Kind.KEEP).isPresent()) {
            notes.add(
                path
                    + " matches a pattern the package readme deletes, but it is this site's own"
                    + " file, not the vendor's leftover: it is not deleted");
            continue;
          }
          out.add(deletion(path));
          fromGlobs++;
        }
      }
    }
    if (fromGlobs > 0) {
      notes.add(
          fromGlobs
              + " file(s) left by an earlier hotfix are deleted, as the readme's Important section"
              + " requires; they are in the snapshot and a rollback puts them back");
    }
    return out;
  }

  /** The note for a readme deletion jrs-hotfix will not act on. */
  private static String skipped(String path, List<String> problems) {
    return "the package readme lists "
        + OfficialPackage.printable(path)
        + " for deletion; skipped, it is not a usable path ("
        + String.join("; ", problems)
        + "): delete it by hand if it applies";
  }

  private static PackageContents.Entry deletion(String path) {
    return new PackageContents.Entry(path, Action.DELETE, Optional.empty(), Optional.empty(), path);
  }

  /** Files of {@code glob}'s directory whose names match it, as package paths. */
  private static List<String> expand(String glob, PackagePaths paths) {
    int slash = glob.lastIndexOf('/');
    if (slash < 0) {
      return List.of();
    }
    String dir = glob.substring(0, slash);
    String name = glob.substring(slash + 1);
    Path directory = paths.resolve(dir + "/" + name.replace('*', '_')).getParent();
    if (directory == null || !Files.isDirectory(directory)) {
      return List.of();
    }
    Pattern pattern =
        Pattern.compile(
            Stream.of(name.split("\\*", -1))
                .map(Pattern::quote)
                .reduce((a, b) -> a + ".*" + b)
                .orElse(""));
    List<String> out = new ArrayList<>();
    try (Stream<Path> list = Files.list(directory)) {
      list.filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .filter(f -> pattern.matcher(f).matches())
          .filter(f -> PackagePaths.pathProblems(dir + "/" + f).isEmpty())
          .sorted()
          .forEach(f -> out.add(dir + "/" + f));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + directory, e);
    }
    return out;
  }

  /**
   * The libraries under {@code WEB-INF/lib} that are an older version of one the package lays down
   * and that neither the package nor the readme's lists touch. One known to be the vendor's is
   * deleted (the entry is added to {@code entries} and its path to {@code deleted}); any other is
   * reported and left. Seen for real on 2026-09-30: a package brought log4j 2.25.4 and deleted the
   * release's 2.24.3, while the 2.25.3 of the hotfix before it stayed, unnamed by any list.
   */
  static List<String> superseded(
      List<PackageContents.Entry> entries,
      PackagePaths paths,
      String webappPrefix,
      OfficialPackage.Superseded policy,
      List<String> deleted,
      List<String> conflicts) {
    String lib = webappPrefix + "WEB-INF/lib/";
    List<String> brought = new ArrayList<>();
    Set<String> touched = new HashSet<>();
    for (PackageContents.Entry e : entries) {
      if (e.path().startsWith(lib) && e.path().indexOf('/', lib.length()) < 0) {
        String name = e.path().substring(lib.length());
        touched.add(name.toLowerCase(Locale.ROOT));
        if (e.action() != Action.DELETE) {
          brought.add(name);
        }
      }
    }
    if (brought.isEmpty()) {
      return List.of();
    }
    Path directory = paths.resolve(lib + "_").getParent();
    if (directory == null || !Files.isDirectory(directory)) {
      return List.of();
    }
    List<String> onDisk;
    try (Stream<Path> list = Files.list(directory)) {
      onDisk =
          list.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + directory, e);
    }
    List<String> known = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (String name : onDisk) {
      Optional<JarName> here = JarName.of(name);
      if (here.isEmpty() || touched.contains(name.toLowerCase(Locale.ROOT))) {
        continue;
      }
      Optional<String> newer =
          brought.stream()
              .filter(b -> JarName.of(b).filter(n -> here.get().olderThan(n)).isPresent())
              .findFirst();
      if (newer.isEmpty()) {
        continue;
      }
      String said = name + " (the package brings " + newer.get() + ")";
      if (policy.delete() && policy.vendors().test("WEB-INF/lib/" + name)) {
        known.add(said);
        entries.add(deletion(lib + name));
        deleted.add(lib + name);
      } else {
        unknown.add(said);
        // two web fragments of one name make Tomcat refuse the whole webapp (Servlet 8.2.2 2c;
        // seen 2026-09-30 with log4j-jakarta-web 2.25.3 beside 2.25.4): a jar that would be
        // left beside its newer self and is a fragment is a refusal, not a warning
        fragmentName(directory.resolve(name))
            .ifPresent(
                fragment ->
                    conflicts.add(
                        "WEB-INF/lib/"
                            + name
                            + " is a web fragment named "
                            + fragment
                            + " and the package brings "
                            + newer.get()
                            + ": Tomcat refuses to deploy a webapp with two fragments of one"
                            + " name, so the older one must go first. "
                            + (policy.delete()
                                ? "Neither the latest apply nor a baseline knows it as the"
                                    + " vendor's: if an earlier hotfix brought it, `jrs-hotfix"
                                    + " baseline add` that hotfix's package, and the jar is"
                                    + " deleted as superseded; if it is this site's own, remove it"
                                    + " by hand"
                                : "--keep-superseded would leave it; run without, or remove it"
                                    + " by hand")));
      }
    }
    List<String> notes = new ArrayList<>();
    if (!known.isEmpty()) {
      notes.add(
          (known.size() == 1 ? "a library" : known.size() + " libraries")
              + " under WEB-INF/lib in an older version than the package brings, and named by"
              + " none of the readme's lists, "
              + (known.size() == 1 ? "is" : "are")
              + " deleted as superseded: "
              + String.join(", ", known)
              + "; the latest apply or a baseline knows "
              + (known.size() == 1 ? "it" : "them")
              + " as the vendor's, and a rollback puts "
              + (known.size() == 1 ? "it" : "them")
              + " back from the snapshot (--keep-superseded leaves them)");
    }
    if (!unknown.isEmpty()) {
      notes.add(
          "WEB-INF/lib holds "
              + (unknown.size() == 1 ? "a library" : unknown.size() + " libraries")
              + " in an older version than the package brings, outside the readme's lists: "
              + String.join(", ", unknown)
              + "; "
              + (policy.delete()
                  ? "neither the latest apply nor a baseline knows "
                      + (unknown.size() == 1 ? "it" : "them")
                      + " as the vendor's, so nothing is deleted"
                  : "left as --keep-superseded asks")
              + "; check by hand whether it is a leftover and remove it while the server is"
              + " stopped");
    }
    return notes;
  }

  /** The name of the web fragment {@code jar} declares, when it is one; empty otherwise. */
  private static Optional<String> fragmentName(Path jar) {
    try (InputStream in = Files.newInputStream(jar);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = Zips.nextFile(zip)) != null) {
        if (entry.getName().equals("META-INF/web-fragment.xml")) {
          String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
          Matcher m = FRAGMENT_NAME.matcher(xml);
          return Optional.of(m.find() ? m.group(1).strip() : "(unnamed)");
        }
      }
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * The warning about files that usually hold site settings and are about to be replaced: the
   * webapp's files by name, because the running server reads them, and the installation's templates
   * as a count.
   */
  static List<String> configNotes(List<PackageContents.Entry> entries) {
    List<String> paths =
        entries.stream()
            // a merged file keeps this server's settings and has a note of its own
            .filter(e -> e.action() == Action.REPLACE && !e.merged())
            .map(PackageContents.Entry::path)
            .filter(PackageRules::isSettingsFile)
            .sorted()
            .toList();
    if (paths.isEmpty()) {
      return List.of();
    }
    List<String> webapp =
        paths.stream().filter(p -> p.startsWith(PackagePaths.WEBAPPS_PREFIX)).toList();
    List<String> templates =
        paths.stream().filter(p -> !p.startsWith(PackagePaths.WEBAPPS_PREFIX)).toList();
    String counted =
        templates.size()
            + (templates.size() == 1 ? " configuration template" : " configuration templates")
            + " under "
            + String.join(
                ", ",
                templates.stream().map(p -> p.substring(0, p.indexOf('/'))).distinct().toList())
            + " (read by the installer's scripts, not by the running server)";
    if (webapp.isEmpty()) {
      return List.of(
          counted + " are overwritten; settings you changed in them must be applied again");
    }
    List<String> shown =
        webapp.size() > MAX_NAMED_SETTINGS ? webapp.subList(0, MAX_NAMED_SETTINGS) : webapp;
    String more =
        webapp.size() > shown.size()
            ? " and " + (webapp.size() - shown.size()) + " more in the webapp"
            : "";
    return List.of(
        "settings you changed in these files are overwritten and must be applied again: "
            + String.join(", ", shown)
            + more
            + (templates.isEmpty() ? "" : "; so are " + counted)
            + ". With the vendor's WAR as a baseline (`jrs-hotfix baseline add`), the webapp's"
            + " files you changed are kept or merged instead");
  }

  /** True for the configuration files a site edits, as opposed to code the hotfix ships. */
  private static boolean isSettingsFile(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return (lower.endsWith(".xml") || lower.endsWith(".properties"))
        && !lower.contains("/web-inf/lib/");
  }
}

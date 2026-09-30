package com.jaspersoft.jrshotfix.war;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.baseline.Wars;
import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.pkg.Packages;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.PlatformDetectionTest;
import com.jaspersoft.jrshotfix.platform.Sums;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WarFileTest {

  @TempDir Path tmp;

  final FileOps files = PlatformDetectionTest.files();

  /** Every entry of {@code war}, name to content. */
  public static Map<String, String> entries(Path war) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    try (InputStream in = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (!e.isDirectory()) {
          out.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
      }
    }
    return out;
  }

  @Test
  void should_unpack_once_and_reuse_the_copy_while_the_war_is_the_same() throws Exception {
    Path war = Wars.war(tmp.resolve("in.war"), Wars.vendor());
    Path webapp = tmp.resolve("wars/webapps/jasperserver-pro");
    WarFile.Unpacked first = WarFile.unpack(war, webapp, files);
    assertThat(first.entries()).isEqualTo(Wars.vendor().size());
    assertThat(first.sha256()).isEqualTo(files.sha256(war));
    assertThat(Files.readString(webapp.resolve(Wars.WEB_XML)))
        .isEqualTo(Wars.vendor().get(Wars.WEB_XML));
    // a file written into the copy survives a second unpack of the same WAR
    Files.writeString(webapp.resolve("marker.txt"), "x");
    assertThat(WarFile.unpack(war, webapp, files)).isEqualTo(first);
    assertThat(webapp.resolve("marker.txt")).exists();
    // another WAR replaces the copy whole
    Map<String, String> other = new LinkedHashMap<>(Wars.vendor());
    other.put(Wars.LOGO, "GIF");
    Path war2 = Wars.war(tmp.resolve("in2.war"), other);
    WarFile.Unpacked second = WarFile.unpack(war2, webapp, files);
    assertThat(second.sha256()).isNotEqualTo(first.sha256());
    assertThat(webapp.resolve("marker.txt")).doesNotExist();
    assertThat(Files.readString(webapp.resolve(Wars.LOGO))).isEqualTo("GIF");
  }

  @Test
  void should_refuse_an_entry_that_climbs_out_or_an_archive_with_nothing_in_it() throws Exception {
    Path bad = Packages.zip(tmp.resolve("bad.war"), Map.of("../escape.txt", new byte[] {1}));
    assertThatThrownBy(() -> WarFile.unpack(bad, tmp.resolve("w/webapps/x"), files))
        .isInstanceOfSatisfying(
            HotfixException.class,
            e -> assertThat(e.kind()).isEqualTo(HotfixException.UNSUPPORTED));
    Files.writeString(tmp.resolve("empty.war"), "");
    assertThatThrownBy(
            () -> WarFile.unpack(tmp.resolve("empty.war"), tmp.resolve("w2/webapps/x"), files))
        .hasMessageContaining("not a readable archive");
  }

  @Test
  void should_assemble_the_output_from_the_input_less_the_dropped_plus_the_staged_and_check_it()
      throws Exception {
    Path in = Wars.war(tmp.resolve("in.war"), Wars.vendor());
    Path stagedWeb = tmp.resolve("staging/web.xml");
    Files.createDirectories(stagedWeb.getParent());
    Files.writeString(stagedWeb, "<web-app>new</web-app>");
    Path stagedNew = tmp.resolve("staging/new.jar");
    Files.writeString(stagedNew, "brand new");
    Map<String, Path> staged = new LinkedHashMap<>();
    staged.put(Wars.WEB_XML, stagedWeb);
    staged.put(Packages.LIB + "new-1.0.jar", stagedNew);
    Path out = tmp.resolve("out/fixed.war");
    int count = WarFile.assemble(in, out, Set.of(Wars.LOGO), staged);

    Map<String, String> result = entries(out);
    assertThat(count).isEqualTo(Wars.vendor().size() - 1 + 1);
    assertThat(result).hasSize(count);
    assertThat(result.get(Wars.WEB_XML)).isEqualTo("<web-app>new</web-app>");
    assertThat(result.get(Packages.LIB + "new-1.0.jar")).isEqualTo("brand new");
    assertThat(result).doesNotContainKey(Wars.LOGO);
    assertThat(result.get(Wars.CONTEXT)).isEqualTo(Wars.vendor().get(Wars.CONTEXT));
    // the input is as it was
    assertThat(entries(in)).isEqualTo(Wars.vendor());

    Map<String, String> hashes = new LinkedHashMap<>();
    hashes.put(Wars.WEB_XML, Sums.of(Files.readAllBytes(stagedWeb)).sha256());
    hashes.put(Packages.LIB + "new-1.0.jar", Sums.of(Files.readAllBytes(stagedNew)).sha256());
    assertThat(WarFile.check(out, new WarFile.Expected(hashes, Set.of(Wars.LOGO), count)))
        .isEmpty();
    hashes.put(Wars.WEB_XML, "0000");
    assertThat(WarFile.check(out, new WarFile.Expected(hashes, Set.of(Wars.CONTEXT), count + 1)))
        .satisfiesExactly(
            p -> assertThat(p).contains(Wars.WEB_XML + " hashes to"),
            p -> assertThat(p).contains(Wars.CONTEXT + " should be absent"),
            p -> assertThat(p).contains("entries, expected " + (count + 1)));
    assertThat(WarFile.paths(out)).contains(Wars.WEB_XML).doesNotContain(Wars.LOGO);
    assertThat(WarFile.entries(out)).isEqualTo(count);
    assertThat(WarFile.temporary(out).getFileName().toString())
        .isEqualTo("fixed.war.jrs-hotfix.tmp");
    assertThat(WarFile.sidecar(out).getFileName().toString())
        .isEqualTo("fixed.war.jrs-hotfix.json");
    assertThat(List.of(WarFile.identity().apply("a/b"))).containsExactly("a/b");
  }
}

package com.jaspersoft.jrshotfix.platform;

import static com.jaspersoft.jrshotfix.platform.ScriptServiceController.findPidFile;
import static com.jaspersoft.jrshotfix.platform.ScriptServiceController.removeIfStale;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Installation guide p.237 (issue #113): a {@code temp/catalina.pid} left by a JVM that died makes
 * {@code catalina.sh start} refuse; one naming a live process is the JVM itself and is left alone.
 */
class ScriptServiceControllerPidFileTest {

  @TempDir Path tmp;

  private Path pidFile(Path tomcat, String text) throws Exception {
    Path file = Files.createDirectories(tomcat.resolve("temp")).resolve("catalina.pid");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void should_report_no_file_when_the_tomcat_has_none() throws Exception {
    Path tomcat = Files.createDirectories(tmp.resolve("apache-tomcat"));
    assertThat(findPidFile(List.of(tomcat))).isEmpty();
    assertThat(removeIfStale(List.of(tomcat), pid -> false)).isEmpty();
  }

  @Test
  void should_call_the_file_stale_when_its_pid_is_not_alive_or_not_a_number() throws Exception {
    Path tomcat = tmp.resolve("t1");
    Path file = pidFile(tomcat, "4242\n");
    assertThat(findPidFile(List.of(tomcat)).orElseThrow().pid()).contains(4242L);
    assertThat(removeIfStale(List.of(tomcat), pid -> pid == 4242L)).isEmpty();
    assertThat(file).exists();
    assertThat(removeIfStale(List.of(tomcat), pid -> false)).isPresent();
    assertThat(file).doesNotExist();

    Path garbage = tmp.resolve("t2");
    Path garbageFile = pidFile(garbage, "not a pid");
    assertThat(findPidFile(List.of(garbage)).orElseThrow().pid()).isEmpty();
    assertThat(removeIfStale(List.of(garbage), pid -> true)).isPresent();
    assertThat(garbageFile).doesNotExist();
  }

  @Test
  void should_remove_only_a_stale_file_and_search_the_given_directories_in_order()
      throws Exception {
    Path live = tmp.resolve("live");
    Path liveFile = pidFile(live, "77");
    assertThat(removeIfStale(List.of(live), pid -> pid == 77L)).isEmpty();
    assertThat(liveFile).exists();

    Path first = tmp.resolve("install").resolve("apache-tomcat");
    Path second = tmp.resolve("install").resolve("tomcat");
    Path staleFile = pidFile(second, "78");
    Optional<ScriptServiceController.PidFile> removed =
        removeIfStale(List.of(first, second), pid -> false);
    assertThat(removed).isPresent();
    assertThat(removed.get().file()).isEqualTo(staleFile);
    assertThat(removed.get().pid()).contains(78L);
    assertThat(staleFile).doesNotExist();
  }
}

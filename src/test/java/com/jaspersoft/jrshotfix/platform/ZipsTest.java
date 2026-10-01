package com.jaspersoft.jrshotfix.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ZipsTest {

  @Test
  void should_skip_directory_entries_and_use_forward_slashes_when_walking() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("WEB-INF/"));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("WEB-INF\\web.xml"));
      zip.write("<web-app/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("index.jsp"));
      zip.closeEntry();
    }
    List<String> names = new ArrayList<>();
    String content = null;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      ZipEntry entry;
      while ((entry = Zips.nextFile(zip)) != null) {
        names.add(Zips.name(entry));
        if (content == null) {
          content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }

    assertThat(names).containsExactly("WEB-INF/web.xml", "index.jsp");
    assertThat(content).isEqualTo("<web-app/>");
  }
}

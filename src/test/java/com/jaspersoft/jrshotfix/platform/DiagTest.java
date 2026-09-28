package com.jaspersoft.jrshotfix.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiagTest {

  @AfterEach
  void resetSink() {
    Diag.reset();
  }

  @Test
  void should_fill_placeholders_in_order_when_emitting() {
    List<String> messages = new ArrayList<>();
    Diag.install((level, message) -> messages.add(level + ":" + message));

    Diag.debug("{} to {}", "a", "b");

    assertThat(messages).containsExactly("DEBUG:a to b");
  }

  @Test
  void should_append_a_trailing_throwable_when_given() {
    List<String> messages = new ArrayList<>();
    Diag.install((level, message) -> messages.add(message));

    Diag.warn("failed {}", "x", new IOException("boom"));

    assertThat(messages).hasSize(1);
    assertThat(messages.get(0)).endsWith("boom");
  }
}

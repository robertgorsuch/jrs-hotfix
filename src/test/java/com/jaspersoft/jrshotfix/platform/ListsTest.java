package com.jaspersoft.jrshotfix.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ListsTest {

  @Test
  void should_name_every_item_when_the_list_is_short() {
    assertThat(Lists.firstAndMore(List.of("a", "b"), 2)).isEqualTo("a, b");
    assertThat(Lists.firstAndMore(List.of(), 2)).isEmpty();
  }

  @Test
  void should_count_the_rest_when_the_list_is_long() {
    assertThat(Lists.firstAndMore(List.of("a", "b", "c", "d"), 2)).isEqualTo("a, b and 2 more");
  }
}

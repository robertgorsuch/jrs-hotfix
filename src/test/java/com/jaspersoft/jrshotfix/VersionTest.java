package com.jaspersoft.jrshotfix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {
  @Test
  void should_read_product_and_vendor_when_resource_is_filtered() {
    assertThat(Version.current().product()).isEqualTo("jrs-hotfix");
    assertThat(Version.current().vendor()).isEqualTo("Jaspersoft");
    assertThat(Version.current().version()).doesNotContain("${");
  }
}

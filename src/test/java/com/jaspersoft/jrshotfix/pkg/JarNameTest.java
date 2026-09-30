package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JarNameTest {

  private static JarName of(String fileName) {
    return JarName.of(fileName).orElseThrow();
  }

  @Test
  void should_split_at_the_last_dash_before_a_digit_when_the_artifact_holds_a_number() {
    assertThat(of("log4j-1.2-api-2.25.4.jar")).isEqualTo(new JarName("log4j-1.2-api", "2.25.4"));
    assertThat(of("jersey-spring6-4.0.2.jar")).isEqualTo(new JarName("jersey-spring6", "4.0.2"));
  }

  @Test
  void should_keep_the_version_in_the_artifact_when_a_qualifier_has_a_number_of_its_own() {
    // errs towards reporting nothing: 7.0.5 and 7.0.6 of this jar are two artifacts to the rule
    assertThat(of("jasperreports-spring-hotfix-7.0.5-JS-79557-SNAPSHOT.jar"))
        .isEqualTo(new JarName("jasperreports-spring-hotfix-7.0.5-JS", "79557-SNAPSHOT"));
  }

  @Test
  void should_be_empty_when_the_name_states_no_version_or_is_no_jar() {
    assertThat(JarName.of("iijdbc.jar")).isEmpty();
    assertThat(JarName.of("actian-chart-customizers.jar")).isEmpty();
    assertThat(JarName.of("foo-1.2.3.zip")).isEmpty();
    assertThat(JarName.of("-1.jar")).isEmpty();
  }

  @Test
  void should_be_older_only_when_it_is_the_same_artifact_in_a_lower_version() {
    assertThat(of("foo-1.9.0.jar").olderThan(of("foo-1.10.0.jar"))).isTrue();
    assertThat(of("foo-1.10.0.jar").olderThan(of("foo-1.9.0.jar"))).isFalse();
    assertThat(of("foo-1.2.3.jar").olderThan(of("foo-1.2.3.jar"))).isFalse();
    assertThat(of("foo-api-1.0.jar").olderThan(of("foo-2.0.jar"))).isFalse();
  }
}

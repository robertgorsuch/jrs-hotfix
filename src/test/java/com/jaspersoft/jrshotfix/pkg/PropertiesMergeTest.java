package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Merging a properties file of this server into the one a hotfix ships, by key. */
class PropertiesMergeTest {

  private static PropertiesMerge.Result merge(List<String> mine, List<String> theirs) {
    return PropertiesMerge.merge(mine, theirs);
  }

  @Test
  void should_keep_the_servers_value_in_the_packages_layout_when_both_have_the_key() {
    PropertiesMerge.Result r =
        merge(
            List.of("a=1", "uri=http://reports:8081/jasperserver-pro"),
            List.of(
                "# scheduler",
                "a=1",
                "uri=http://localhost:8080/jasperserver-pro",
                "",
                "# new in this hotfix",
                "deletion.enabled=true"));
    assertThat(r.lines())
        .containsExactly(
            "# scheduler",
            "a=1",
            "uri=http://reports:8081/jasperserver-pro",
            "",
            "# new in this hotfix",
            "deletion.enabled=true");
    assertThat(r.kept()).containsExactly("uri");
    assertThat(r.carried()).isEmpty();
    assertThat(r.changed()).isTrue();
  }

  @Test
  void should_carry_a_key_over_under_a_heading_when_only_the_server_has_it() {
    PropertiesMerge.Result r =
        merge(List.of("# mail", "mail.host=smtp.example.org", "a=1"), List.of("a=1"));
    assertThat(r.lines())
        .containsExactly("a=1", "", PropertiesMerge.CARRIED_HEADING, "mail.host=smtp.example.org");
    assertThat(r.kept()).isEmpty();
    assertThat(r.carried()).containsExactly("mail.host");
  }

  @Test
  void should_change_nothing_when_the_server_has_the_packages_values() {
    List<String> theirs = List.of("# c", "a = 1", "b:2", "c 3", "");
    PropertiesMerge.Result r = merge(List.of("c=3", "b=2", "a=1"), theirs);
    assertThat(r.lines()).isEqualTo(theirs);
    assertThat(r.changed()).isFalse();
  }

  @Test
  void should_give_the_same_file_when_the_result_is_merged_again() {
    List<String> theirs =
        List.of("# scheduler", "a=1", "uri=http://localhost:8080/x", "", "fresh=true", "");
    List<String> mine =
        List.of("a=2", "uri=http://reports:8081/x", "mail.host=smtp", "mail.port=25", "a=3");
    PropertiesMerge.Result once = merge(mine, theirs);
    PropertiesMerge.Result twice = merge(once.lines(), theirs);
    assertThat(twice.lines()).isEqualTo(once.lines());
    assertThat(twice.kept()).isEqualTo(once.kept());
    assertThat(twice.carried()).isEqualTo(once.carried());
    // the last definition is the one java.util.Properties would use
    assertThat(once.lines()).contains("a=3").doesNotContain("a=2");
  }

  @Test
  void should_keep_every_line_of_a_value_when_the_servers_value_continues() {
    PropertiesMerge.Result r =
        merge(
            List.of("hosts=one,\\", "    two,\\", "    three", "b=1"), List.of("hosts = x", "b=1"));
    assertThat(r.lines()).containsExactly("hosts = one,\\", "    two,\\", "    three", "b=1");
    assertThat(r.kept()).containsExactly("hosts");
  }

  @Test
  void should_replace_every_line_of_a_value_when_the_packages_value_continues() {
    PropertiesMerge.Result r =
        merge(List.of("hosts=mine"), List.of("hosts=one,\\", "    two", "after=1"));
    assertThat(r.lines()).containsExactly("hosts=mine", "after=1");
  }

  @Test
  void should_see_the_same_value_when_only_the_line_breaks_of_a_continuation_differ() {
    List<String> theirs = List.of("hosts=one,\\", "    two");
    PropertiesMerge.Result r = merge(List.of("hosts=one,two"), theirs);
    assertThat(r.lines()).isEqualTo(theirs);
    assertThat(r.changed()).isFalse();
  }

  @Test
  void should_not_take_a_comment_or_an_escaped_separator_for_a_key() {
    PropertiesMerge.Result r =
        merge(
            List.of("! old=comment", "#x=1", "a\\=b=mine", "path=c:\\\\dir\\\\"),
            List.of("#x=2", "a\\=b=theirs", "path=d:\\\\dir\\\\", "x=2"));
    assertThat(r.lines()).containsExactly("#x=2", "a\\=b=mine", "path=c:\\\\dir\\\\", "x=2");
    assertThat(r.kept()).containsExactly("a\\=b", "path");
  }

  @Test
  void should_keep_an_empty_value_when_the_server_emptied_it() {
    PropertiesMerge.Result r = merge(List.of("proxy="), List.of("proxy=http://vendor"));
    assertThat(r.lines()).containsExactly("proxy=");
    assertThat(r.kept()).containsExactly("proxy");
  }
}

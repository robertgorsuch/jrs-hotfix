package com.jaspersoft.jrshotfix.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.text.PropertiesMerge.Merged;
import com.jaspersoft.jrshotfix.text.PropertiesMerge.Style;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three-way merge of a properties file by key: the table of the 0.2 design, 4.2, by row. */
class PropertiesMerge3Test {

  private static Merged merge(List<String> base, List<String> mine, List<String> theirs) {
    return PropertiesMerge.merge3(base, mine, theirs, Style.MARKERS);
  }

  @Test
  void should_take_the_vendors_value_when_the_site_did_not_change_the_key() {
    Merged m = merge(List.of("a=1", "b=2"), List.of("a=1", "b=2"), List.of("# new", "a=9", "b=2"));
    assertThat(m.lines()).containsExactly("# new", "a=9", "b=2");
    assertThat(m.kept()).isEmpty();
    assertThat(m.conflicts()).isEmpty();
  }

  @Test
  void should_keep_the_sites_value_in_place_when_only_the_site_changed_the_key() {
    Merged m = merge(List.of("a=1", "b=2"), List.of("a=5", "b=2"), List.of("# c", "a = 1", "b=3"));
    // the vendor's layout, key and separator; the site's value
    assertThat(m.lines()).containsExactly("# c", "a = 5", "b=3");
    assertThat(m.kept()).containsExactly("a");
    assertThat(m.conflicts()).isEmpty();
  }

  @Test
  void should_append_under_the_heading_a_key_only_the_site_has() {
    Merged m = merge(List.of("a=1"), List.of("a=1", "site.key=x"), List.of("a=1"));
    assertThat(m.lines()).containsExactly("a=1", "", PropertiesMerge.CARRIED_HEADING, "site.key=x");
    assertThat(m.carried()).containsExactly("site.key");
  }

  @Test
  void should_leave_a_key_removed_when_the_site_removed_it_and_the_vendor_kept_it_as_it_was() {
    Merged m = merge(List.of("a=1", "gone=1"), List.of("a=1"), List.of("a=1", "gone=1", "c=3"));
    assertThat(m.lines()).containsExactly("a=1", "c=3");
    assertThat(m.removed()).containsExactly("gone");
    assertThat(m.conflicts()).isEmpty();
  }

  @Test
  void should_drop_a_key_the_vendor_removed_when_the_site_had_not_changed_it() {
    Merged m = merge(List.of("a=1", "old=1"), List.of("a=1", "old=1"), List.of("a=1"));
    assertThat(m.lines()).containsExactly("a=1");
    assertThat(m.carried()).isEmpty();
    assertThat(m.conflicts()).isEmpty();
  }

  @Test
  void should_take_a_value_once_when_both_changed_the_key_alike() {
    Merged m = merge(List.of("a=1"), List.of("a=2"), List.of("a=2"));
    assertThat(m.lines()).containsExactly("a=2");
    assertThat(m.conflicts()).isEmpty();
    assertThat(m.kept()).isEmpty();
  }

  @Test
  void should_mark_a_conflict_when_both_changed_the_key_differently() {
    Merged m = merge(List.of("x=0", "a=1"), List.of("x=0", "a=2"), List.of("x=0", "a=3"));
    assertThat(m.conflicts()).containsExactly("a");
    assertThat(m.lines())
        .containsExactly(
            "x=0",
            Conflict.MARK_MINE,
            "a=2",
            Conflict.MARK_BASE,
            "a=1",
            Conflict.MARK_SEPARATOR,
            "a=3",
            Conflict.MARK_THEIRS);
    assertThat(Conflict.hasMarkers(m.lines())).isTrue();
  }

  @Test
  void should_mark_a_conflict_when_the_site_removed_a_key_the_vendor_changed() {
    Merged m = merge(List.of("a=1"), List.of(), List.of("a=3"));
    assertThat(m.conflicts()).containsExactly("a");
    assertThat(m.lines())
        .containsExactly(
            Conflict.MARK_MINE,
            Conflict.MARK_BASE,
            "a=1",
            Conflict.MARK_SEPARATOR,
            "a=3",
            Conflict.MARK_THEIRS);
  }

  @Test
  void should_mark_a_conflict_when_both_added_the_key_with_different_values() {
    Merged m = merge(List.of(), List.of("a=2"), List.of("a=3"));
    assertThat(m.conflicts()).containsExactly("a");
    assertThat(m.lines()).contains("a=2", "a=3").doesNotContain("a=1");
  }

  @Test
  void should_mark_a_conflict_when_the_vendor_removed_a_key_the_site_changed() {
    Merged m = merge(List.of("a=1", "b=1"), List.of("a=2", "b=1"), List.of("b=1"));
    assertThat(m.conflicts()).containsExactly("a");
    assertThat(m.lines())
        .containsExactly(
            "b=1",
            "",
            Conflict.MARK_MINE,
            "a=2",
            Conflict.MARK_BASE,
            "a=1",
            Conflict.MARK_SEPARATOR,
            Conflict.MARK_THEIRS);
  }

  @Test
  void should_let_the_site_win_with_the_vendors_value_as_a_comment_under_the_mine_style() {
    Merged m =
        PropertiesMerge.merge3(
            List.of("a=1", "gone=1", "kept=1"),
            List.of("a=2", "kept=2"),
            List.of("a=3", "gone=2"),
            Style.MINE);
    assertThat(m.conflicts()).containsExactly("a", "gone", "kept");
    assertThat(m.lines())
        .containsExactly(
            "# jrs-hotfix: the hotfix's value, not used on this server:",
            "# a=3",
            "a=2",
            "# jrs-hotfix: removed on this server; the hotfix ships:",
            "# gone=2",
            "",
            PropertiesMerge.CARRIED_HEADING,
            "# jrs-hotfix: the hotfix removes this key; kept as this server has it:",
            "kept=2");
    assertThat(Conflict.hasMarkers(m.lines())).isFalse();
    assertThat(m.kept()).containsExactly("a");
    assertThat(m.removed()).containsExactly("gone");
    assertThat(m.carried()).containsExactly("kept");
  }

  @Test
  void should_let_the_vendor_win_with_the_sites_value_as_a_comment_under_the_theirs_style() {
    Merged m =
        PropertiesMerge.merge3(
            List.of("a=1", "gone=1", "kept=1"),
            List.of("a=2", "kept=2"),
            List.of("a=3", "gone=2"),
            Style.THEIRS);
    assertThat(m.lines())
        .containsExactly(
            "# jrs-hotfix: this server's value, replaced by the hotfix's:",
            "# a=2",
            "a=3",
            "# jrs-hotfix: this server had removed this key; the hotfix's value:",
            "gone=2",
            "",
            "# jrs-hotfix: removed by the hotfix; this server had:",
            "# kept=2");
    assertThat(m.conflicts()).containsExactly("a", "gone", "kept");
    assertThat(m.kept()).isEmpty();
  }

  @Test
  void should_compare_a_continued_value_as_one_value_and_carry_every_line_of_it() {
    List<String> base = List.of("list=a,\\", "  b", "x=1");
    // broken differently, the same value: not a change of the site's
    Merged same = merge(base, List.of("list=a,b", "x=1"), List.of("list=a,\\", "  b,\\", "  c"));
    assertThat(same.lines()).containsExactly("list=a,\\", "  b,\\", "  c");
    assertThat(same.conflicts()).isEmpty();
    // the site's continued value goes in whole
    Merged kept = merge(base, List.of("list=z,\\", "  y", "x=1"), List.of("list = a,\\", "  b"));
    assertThat(kept.lines()).containsExactly("list = z,\\", "  y");
    assertThat(kept.kept()).containsExactly("list");
  }

  @Test
  void should_read_escaped_separators_and_keep_bytes_above_ascii_as_they_are() {
    Merged m =
        merge(
            List.of("a\\=b=1", "c\\:d:1", "name=Müller"),
            List.of("a\\=b=2", "c\\:d:1", "name=Müller"),
            List.of("a\\=b=1", "c\\:d:5", "name=Möller"));
    assertThat(m.lines()).containsExactly("a\\=b=2", "c\\:d:5", "name=Möller");
    assertThat(m.kept()).containsExactly("a\\=b");
    assertThat(m.conflicts()).isEmpty();
  }

  @Test
  void should_give_the_same_file_again_when_the_silent_result_is_merged_with_the_same_sides() {
    List<String> base = List.of("a=1", "b=1", "c=1", "gone=1");
    List<String> theirs = List.of("# v2", "a=1", "b=2", "c=9", "gone=1", "fresh=1");
    Merged once =
        PropertiesMerge.merge3(
            base, List.of("a=7", "b=1", "c=8", "own=1"), theirs, Style.MINE_SILENT);
    assertThat(once.lines())
        .containsExactly(
            "# v2", "a=7", "b=2", "c=8", "fresh=1", "", PropertiesMerge.CARRIED_HEADING, "own=1");
    Merged twice = PropertiesMerge.merge3(base, once.lines(), theirs, Style.MINE_SILENT);
    assertThat(twice.lines()).isEqualTo(once.lines());
  }
}

package com.jaspersoft.jrshotfix.merge;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.merge.MergeDoc.Item;
import com.jaspersoft.jrshotfix.merge.MergeDoc.State;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MergeDocTest {

  @Test
  void aProposedItemIsTheItemSpeltOut() throws Exception {
    Item named =
        Item.proposed(
            "WEB-INF/a.xml",
            "X",
            "collision",
            State.CONFLICT,
            Optional.of("b"),
            Optional.of("m"),
            "t",
            Optional.empty(),
            List.of("a check"),
            "a note");
    Item spelt =
        new Item(
            "WEB-INF/a.xml",
            "X",
            "collision",
            State.CONFLICT,
            Optional.of("b"),
            Optional.of("m"),
            Optional.of("t"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of("a check"),
            "a note");
    assertThat(named).isEqualTo(spelt);
    assertThat(Json.writePretty(named)).isEqualTo(Json.writePretty(spelt));
  }

  @Test
  void aKeptItemIsTheItemSpeltOut() throws Exception {
    Item named = Item.kept("WEB-INF/own.jar", "B", "the site's file", "m", "kept");
    Item spelt =
        new Item(
            "WEB-INF/own.jar",
            "B",
            "the site's file",
            State.KEPT,
            Optional.empty(),
            Optional.of("m"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            "kept");
    assertThat(named).isEqualTo(spelt);
    assertThat(Json.writePretty(named)).isEqualTo(Json.writePretty(spelt));
  }
}

package com.jaspersoft.jrshotfix.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class XmlChecksTest {

  private static Optional<byte[]> xml(String text) {
    return Optional.of(text.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> problems(String base, String mine, String merged) {
    return XmlChecks.problems(
        xml(base), xml(mine), xml(base), merged.getBytes(StandardCharsets.UTF_8));
  }

  private static final String BASE =
      "<beans><bean id=\"engine\"/><bean id=\"cache\" name=\"store,memo\"/></beans>";

  @Test
  void should_pass_a_well_formed_context_whose_beans_are_the_sites() {
    assertThat(problems(BASE, BASE, BASE)).isEmpty();
  }

  @Test
  void should_refuse_a_file_that_is_not_well_formed() {
    assertThat(problems(BASE, BASE, "<beans><bean id=\"a\"></beans>"))
        .containsExactly("it is not well-formed XML");
    // a conflict marker left in the file makes it not well-formed too
    assertThat(problems(BASE, BASE, "<beans>\n<<<<<<< mine\n</beans>")).hasSize(1);
  }

  @Test
  void should_refuse_a_bean_id_or_name_that_stands_twice() {
    assertThat(problems(BASE, BASE, "<beans><bean id=\"a\"/><bean id=\"a\"/></beans>"))
        .containsExactly("the bean a is defined twice");
    assertThat(problems(BASE, BASE, "<beans><bean id=\"a\"/><bean name=\"b, a\"/></beans>"))
        .containsExactly("the bean a is defined twice");
    // an inner bean is no bean of the context
    assertThat(
            problems(
                BASE,
                BASE,
                "<beans><bean id=\"a\"><property name=\"p\"><bean id=\"a\"/></property></bean>"
                    + "</beans>"))
        .isEmpty();
  }

  @Test
  void should_refuse_a_merge_that_brings_back_a_bean_the_site_removed() {
    String mine = "<beans><bean id=\"engine\"/></beans>";
    assertThat(problems(BASE, mine, BASE))
        .containsExactly(
            "the bean cache, which this site removed, is back",
            "the bean store, which this site removed, is back",
            "the bean memo, which this site removed, is back");
    assertThat(problems(BASE, mine, "<beans><bean id=\"engine\"/><bean id=\"new\"/></beans>"))
        .isEmpty();
  }

  @Test
  void should_refuse_a_merge_that_drops_a_bean_the_site_added() {
    String mine =
        "<beans><bean id=\"engine\"/><bean id=\"cache\" name=\"store,memo\"/>"
            + "<bean id=\"ldap\"/></beans>";
    assertThat(problems(BASE, mine, BASE))
        .containsExactly("the bean ldap, which this site added, is gone");
  }

  @Test
  void should_refuse_a_filter_servlet_or_listener_defined_twice_in_web_xml() {
    String web =
        "<web-app><filter><filter-name>f</filter-name></filter>"
            + "<filter-mapping><filter-name>f</filter-name></filter-mapping>"
            + "<filter-mapping><filter-name>f</filter-name></filter-mapping>"
            + "<servlet><servlet-name>s</servlet-name></servlet>"
            + "<listener><listener-class>a.B</listener-class></listener></web-app>";
    // a name used by two mappings is no duplicate
    assertThat(problems(web, web, web)).isEmpty();
    String twice =
        web.replace(
            "</web-app>",
            "<filter><filter-name> f </filter-name></filter>"
                + "<servlet><servlet-name>s</servlet-name></servlet>"
                + "<listener><listener-class>a.B</listener-class></listener></web-app>");
    assertThat(problems(web, web, twice))
        .containsExactly(
            "the filter f is defined twice",
            "the servlet s is defined twice",
            "the listener a.B is defined twice");
  }

  @Test
  void should_let_the_vendors_own_repeats_pass_and_catch_one_the_merge_added() {
    // the 10.0.0 web.xml declares the same listener ten times, and a merge must not be refused
    // for what the vendor ships; a listener the merge doubled beyond either side is another matter
    String twice =
        "<web-app><listener><listener-class>a.B</listener-class></listener>"
            + "<listener><listener-class>a.B</listener-class></listener></web-app>";
    String thrice =
        twice.replace(
            "</web-app>", "<listener><listener-class>a.B</listener-class></listener></web-app>");
    assertThat(
            XmlChecks.problems(
                xml(twice), xml(twice), xml(thrice), thrice.getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
    assertThat(
            XmlChecks.problems(
                xml(twice), xml(twice), xml(twice), thrice.getBytes(StandardCharsets.UTF_8)))
        .containsExactly("the listener a.B is defined twice");
    String beansTwice = "<beans><bean id=\"cacheManager\"/><bean id=\"cacheManager\"/></beans>";
    assertThat(
            XmlChecks.problems(
                xml(beansTwice),
                xml(beansTwice),
                xml(beansTwice),
                beansTwice.getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
  }

  @Test
  void should_not_fetch_a_dtd_or_an_entity_the_file_names() {
    String doctype =
        "<!DOCTYPE web-app PUBLIC \"-//Sun//DTD Web Application 2.3//EN\""
            + " \"http://127.0.0.1:1/web-app_2_3.dtd\">\n<web-app/>";
    assertThat(problems(doctype, doctype, doctype)).isEmpty();
  }

  @Test
  void should_check_what_it_can_when_the_base_or_the_sites_file_does_not_parse() {
    assertThat(
            XmlChecks.problems(
                Optional.empty(),
                xml("<beans"),
                Optional.empty(),
                BASE.getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
  }
}

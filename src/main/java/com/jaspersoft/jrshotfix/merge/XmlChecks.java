package com.jaspersoft.jrshotfix.merge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * What a merged XML file of the webapp must pass before it may be installed (0.2 design, 4.3): it
 * is well-formed; no bean id or name of a Spring context, and no filter, servlet or listener of
 * {@code web.xml}, stands more often than it does in the site's file or in the hotfix's, so a merge
 * that doubled one is caught while the vendor's own repeats pass (the 10.0.0 {@code web.xml}
 * declares the same listener ten times, and several contexts define a bean twice); a bean the site
 * removed is still absent and a bean the site added is still there. Invariants: nothing is read
 * from outside the bytes given, no DTD or entity is fetched; a base, site or hotfix file that does
 * not parse only switches off the checks that need it; the findings name elements, never values.
 */
public final class XmlChecks {

  private XmlChecks() {}

  /** Why {@code merged} must not be installed; empty when it may. */
  public static List<String> problems(
      Optional<byte[]> base, Optional<byte[]> mine, Optional<byte[]> theirs, byte[] merged) {
    Optional<Shape> result = parse(merged);
    if (result.isEmpty()) {
      return List.of("it is not well-formed XML");
    }
    List<String> problems = new ArrayList<>();
    Shape r = result.get();
    Optional<Shape> was = base.flatMap(XmlChecks::parse);
    Optional<Shape> site = mine.flatMap(XmlChecks::parse);
    Optional<Shape> hotfix = theirs.flatMap(XmlChecks::parse);
    for (Map.Entry<String, Integer> e : r.beans.entrySet()) {
      if (e.getValue() > allowed(e.getKey(), site.map(s -> s.beans), hotfix.map(h -> h.beans))) {
        problems.add("the bean " + e.getKey() + " is defined twice");
      }
    }
    for (Map.Entry<String, Integer> e : r.web.entrySet()) {
      if (e.getValue() > allowed(e.getKey(), site.map(s -> s.web), hotfix.map(h -> h.web))) {
        problems.add("the " + e.getKey() + " is defined twice");
      }
    }
    if (was.isPresent() && site.isPresent() && r.root.equals("beans")) {
      for (String bean : was.get().beans.keySet()) {
        if (!site.get().beans.containsKey(bean) && r.beans.containsKey(bean)) {
          problems.add("the bean " + bean + ", which this site removed, is back");
        }
      }
      for (String bean : site.get().beans.keySet()) {
        if (!was.get().beans.containsKey(bean) && !r.beans.containsKey(bean)) {
          problems.add("the bean " + bean + ", which this site added, is gone");
        }
      }
    }
    return problems;
  }

  /**
   * How often {@code name} may stand: as often as on the fuller of the two sides, at least once.
   */
  private static int allowed(
      String name, Optional<Map<String, Integer>> site, Optional<Map<String, Integer>> hotfix) {
    return Math.max(
        1,
        Math.max(
            site.map(s -> s.getOrDefault(name, 0)).orElse(0),
            hotfix.map(h -> h.getOrDefault(name, 0)).orElse(0)));
  }

  /** What the checks need of one document: each name and how often it is defined. */
  private static final class Shape {
    String root = "";
    final Map<String, Integer> beans = new LinkedHashMap<>();
    final Map<String, Integer> web = new LinkedHashMap<>();
  }

  private static Optional<Shape> parse(byte[] xml) {
    Shape shape = new Shape();
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setValidating(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      SAXParser parser = factory.newSAXParser();
      parser.parse(new ByteArrayInputStream(xml), new Reader(shape));
      return Optional.of(shape);
    } catch (SAXException | IOException | ParserConfigurationException e) {
      return Optional.empty();
    }
  }

  private static final class Reader extends DefaultHandler {
    private final Shape shape;
    private final Deque<String> path = new ArrayDeque<>();
    private StringBuilder text = new StringBuilder();

    Reader(Shape shape) {
      this.shape = shape;
    }

    /** A DOCTYPE may name a DTD; it is never fetched. */
    @Override
    public InputSource resolveEntity(String publicId, String systemId) {
      return new InputSource(new StringReader(""));
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      String name = localName.isEmpty() ? qName : localName;
      if (path.isEmpty()) {
        shape.root = name;
      }
      // beans directly under the root: an inner bean has no name of its own in the context
      if (name.equals("bean") && path.size() == 1 && shape.root.equals("beans")) {
        bean(attributes.getValue("id"));
        String names = attributes.getValue("name");
        if (names != null) {
          for (String n : names.split("[,; ]+", -1)) {
            bean(n);
          }
        }
      }
      path.push(name);
      text = new StringBuilder();
    }

    private void bean(String name) {
      if (name != null && !name.isBlank()) {
        shape.beans.merge(name.strip(), 1, Integer::sum);
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      text.append(ch, start, length);
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      String name = path.pop();
      String parent = path.isEmpty() ? "" : path.peek();
      boolean definition =
          shape.root.equals("web-app")
              && path.size() == 2
              && ((name.equals("filter-name") && parent.equals("filter"))
                  || (name.equals("servlet-name") && parent.equals("servlet"))
                  || (name.equals("listener-class") && parent.equals("listener")));
      if (definition) {
        shape.web.merge(parent + " " + text.toString().strip(), 1, Integer::sum);
      }
      text = new StringBuilder();
    }
  }
}

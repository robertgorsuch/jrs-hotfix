package com.jaspersoft.jrshotfix.merge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * is well-formed; in a Spring context no bean id or name stands twice, a bean the site removed is
 * still absent and a bean the site added is still there; in {@code web.xml} no filter, servlet or
 * listener is defined twice. Invariants: nothing is read from outside the bytes given, no DTD or
 * entity is fetched; a base or a site file that does not parse only switches off the checks that
 * need it; the findings name elements, never values.
 */
public final class XmlChecks {

  private XmlChecks() {}

  /** Why {@code merged} must not be installed; empty when it may. */
  public static List<String> problems(Optional<byte[]> base, Optional<byte[]> mine, byte[] merged) {
    Optional<Shape> result = parse(merged);
    if (result.isEmpty()) {
      return List.of("it is not well-formed XML");
    }
    List<String> problems = new ArrayList<>();
    Shape r = result.get();
    r.duplicateBeans.forEach(b -> problems.add("the bean " + b + " is defined twice"));
    r.duplicateWeb.forEach(w -> problems.add(w + " is defined twice"));
    Optional<Shape> was = base.flatMap(XmlChecks::parse);
    Optional<Shape> site = mine.flatMap(XmlChecks::parse);
    if (was.isPresent() && site.isPresent() && r.root.equals("beans")) {
      for (String bean : was.get().beans) {
        if (!site.get().beans.contains(bean) && r.beans.contains(bean)) {
          problems.add("the bean " + bean + ", which this site removed, is back");
        }
      }
      for (String bean : site.get().beans) {
        if (!was.get().beans.contains(bean) && !r.beans.contains(bean)) {
          problems.add("the bean " + bean + ", which this site added, is gone");
        }
      }
    }
    return problems;
  }

  /** What the checks need of one document. */
  private static final class Shape {
    String root = "";
    final Set<String> beans = new LinkedHashSet<>();
    final List<String> duplicateBeans = new ArrayList<>();
    final Set<String> web = new LinkedHashSet<>();
    final List<String> duplicateWeb = new ArrayList<>();
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
      if (name != null && !name.isBlank() && !shape.beans.add(name.strip())) {
        shape.duplicateBeans.add(name.strip());
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
        String what = parent + " " + text.toString().strip();
        if (!shape.web.add(what)) {
          shape.duplicateWeb.add("the " + what);
        }
      }
      text = new StringBuilder();
    }
  }
}

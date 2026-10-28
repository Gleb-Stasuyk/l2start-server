package org.l2start.rates.floating;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** data/exact-items.xml: ids and name parts on top of the rules by item type. */
public final class ItemRules {
    public final Set<Integer> exact = new HashSet<>();
    public final List<String> exactNames = new ArrayList<>();
    public final Set<Integer> keep = new HashSet<>();
    public final Set<Integer> questX1 = new HashSet<>();

    public static ItemRules load(Path file) throws Exception {
        final ItemRules r = new ItemRules();
        if (!Files.isRegularFile(file)) {
            return r;
        }
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Element root = f.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        final NodeList nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element e)) {
                continue;
            }
            final Set<Integer> target = switch (e.getTagName()) {
                case "exact" -> r.exact;
                case "keep" -> r.keep;
                case "questX1" -> r.questX1;
                default -> throw new IllegalArgumentException("unknown element <" + e.getTagName() + ">");
            };
            for (String id : e.getAttribute("ids").split(",")) {
                if (!id.isBlank()) {
                    target.add(Integer.parseInt(id.trim()));
                }
            }
            if (!e.getAttribute("name").isBlank()) {
                if (target != r.exact) {
                    throw new IllegalArgumentException("name= only for <exact>");
                }
                r.exactNames.add(e.getAttribute("name"));
            }
        }
        return r;
    }

    public boolean exactName(String name) {
        if (name == null) {
            return false;
        }
        for (String n : exactNames) {
            if (name.contains(n)) {
                return true;
            }
        }
        return false;
    }
}

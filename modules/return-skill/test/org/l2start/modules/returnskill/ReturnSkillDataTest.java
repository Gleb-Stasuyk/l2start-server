package org.l2start.modules.returnskill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** Skill 95100: like Scroll of Escape, 60 s cast, no item, its id free in stock data. */
class ReturnSkillDataTest {
    static final Path FILE = Path.of("modules/return-skill/data/skills/95100-return.xml");

    static Element skill() throws Exception {
        final Element list = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(FILE.toFile()).getDocumentElement();
        return (Element) list.getElementsByTagName("skill").item(0);
    }

    static String text(Element e, String tag) {
        return e.getElementsByTagName(tag).getLength() == 0 ? null : e.getElementsByTagName(tag).item(0).getTextContent().trim();
    }

    @Test
    void likeScrollOfEscapeWithSixtySecondCast() throws Exception {
        final Element s = skill();
        assertEquals("95100", s.getAttribute("id"));
        assertEquals(String.valueOf(ReturnSkillModule.SKILL_ID), s.getAttribute("id"));
        assertTrue(s.getAttribute("name").matches(".*[а-яА-Я].*"), "Russian name");
        assertEquals("60000", text(s, "hitTime"));
        assertTrue(Integer.parseInt(text(s, "reuseDelay")) >= 60_000, "sensible reuse");
        assertEquals("2", text(s, "isMagic"), "static like SoE");
        assertEquals(null, text(s, "itemConsumeId"), "no item cost");
        assertEquals("TOWN", text(s, "escapeType"));
        assertEquals("true", text(s, "excludedFromCheck"));
    }

    @Test
    void idIsFreeInStockSkills() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("core/dist/game/data/stats/skills"))) {
            assertFalse(files.filter(f -> f.toString().endsWith(".xml")).anyMatch(f -> {
                try {
                    return Files.readString(f).contains("skill id=\"95100\"");
                } catch (Exception e) {
                    return false;
                }
            }), "95100 collides with a stock skill");
        }
    }
}

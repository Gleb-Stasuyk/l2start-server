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

/** Skill 2099 (client-known "Escape: 5 minutes") overridden: like Scroll of Escape, 60 s cast, no item. */
class ReturnSkillDataTest {
    static final Path FILE = Path.of("modules/return-skill/data/skills/2099-return.xml");

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
        assertEquals("2099", s.getAttribute("id"));
        assertEquals(String.valueOf(ReturnSkillModule.SKILL_ID), s.getAttribute("id"));
        
        assertEquals("60000", text(s, "hitTime"));
        assertTrue(Integer.parseInt(text(s, "reuseDelay")) >= 60_000, "sensible reuse");
        assertEquals("2", text(s, "isMagic"), "static like SoE");
        assertEquals(null, text(s, "itemConsumeId"), "no item cost");
        assertEquals("TOWN", text(s, "escapeType"));
        assertEquals("true", text(s, "excludedFromCheck"));
    }

    /** The id must be one the Interlude client has (icon, name) and that nothing else in the server gives or uses. */
    @Test
    void overridesUnusedClientKnownStockSkill() throws Exception {
        assertTrue(Files.readString(Path.of("core/dist/game/data/stats/skills/02000-02099.xml")).contains("<skill id=\"2099\" levels=\"1\" name=\"Escape: 5 minutes\">"));
        try (Stream<Path> files = Files.walk(Path.of("core/java"))) {
            assertFalse(files.filter(f -> f.toString().endsWith(".java")).anyMatch(f -> {
                try {
                    return Files.readString(f).matches("(?s).*getSkill\\(\\s*2099\\b.*");
                } catch (Exception e) {
                    return false;
                }
            }), "2099 used by core code");
        }
    }

    /** Module skills are applied after all stock files (stock loading runs in parallel: order inside it is random). */
    @Test
    void moduleSkillsOverrideAfterStockLoad() throws Exception {
        final String src = Files.readString(Path.of("core/java/org/l2jmobius/gameserver/data/xml/SkillData.java"));
        final int load = src.indexOf("loadAllSkills(temp);");
        final int hook = src.indexOf("[HOOK:P9-DATA-SKILLS]");
        assertTrue((load > 0) && (hook > load) && (src.indexOf("_skillsByHash.putAll(temp)") > hook));
    }
}

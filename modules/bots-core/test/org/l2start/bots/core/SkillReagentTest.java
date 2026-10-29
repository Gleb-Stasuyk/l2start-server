package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.SkillView;

/** Skill reagents stocked at materialize (Death Spike bones, Spirit Ore, cubics) — not olympiad. */
class SkillReagentTest {
    @Test
    void reagentItemsAreTheCombatConsumables() {
        assertTrue(Materializer.reagentItem(Materializer.CURSED_BONE));
        assertTrue(Materializer.reagentItem(Materializer.SPIRIT_ORE));
        assertTrue(Materializer.reagentItem(Materializer.SOUL_ORE));
        assertTrue(Materializer.reagentItem(Materializer.HOLY_WATER));
        assertTrue(Materializer.reagentItem(Materializer.ENERGY_STONE));
        assertTrue(Materializer.reagentItem(Materializer.PHOENIX_BLOOD));
        assertTrue(Materializer.reagentItem(1458)); // D-grade crystal (cubics)
        assertTrue(Materializer.reagentItem(1462)); // S-grade crystal
        assertFalse(Materializer.reagentItem(736)); // Scroll of Escape
        assertFalse(Materializer.reagentItem(1061)); // Healing Potion
        assertFalse(Materializer.reagentItem(0));
    }

    @Test
    void rbPartySkillsAreStockedAndSummonsAreNotDoubleCounted() {
        assertTrue(Materializer.reagentSkill(1148, Materializer.CURSED_BONE), "Death Spike (necro DD)");
        assertTrue(Materializer.reagentSkill(1401, Materializer.SPIRIT_ORE), "Major Heal (bishop)");
        assertTrue(Materializer.reagentSkill(1388, Materializer.SPIRIT_ORE), "Greater Might (prophet)");
        assertTrue(Materializer.reagentSkill(1389, Materializer.SPIRIT_ORE), "Greater Shield (prophet / tank buff)");
        assertTrue(Materializer.reagentSkill(1400, Materializer.SPIRIT_ORE), "Turn Undead (bishop)");
        assertTrue(Materializer.reagentSkill(67, 1458), "Summon Life Cubic (tanks)");
        assertTrue(Materializer.reagentSkill(10, 1458), "Summon Storm Cubic (paladin)");
        assertTrue(Materializer.reagentSkill(405, Materializer.SOUL_ORE), "Banish Undead (paladin)");
        assertTrue(Materializer.reagentSkill(1409, Materializer.HOLY_WATER), "Cleanse (cardinal)");
        assertFalse(Materializer.reagentSkill(1111, 1458), "Summon Kat: summonItems already stocks crystals");
        assertFalse(Materializer.reagentSkill(283, 1459), "Dark Panther: summonItems (olympiad)");
        assertFalse(Materializer.reagentSkill(1323, Materializer.SPIRIT_ORE), "Noblesse Blessing is not a rotation");
        assertFalse(Materializer.reagentSkill(2013, Materializer.CURSED_BONE), "item skills id>=2000");
        assertFalse(Materializer.reagentSkill(1235, 0), "Hydro Blast: no reagent");
    }

    @Test
    void stockIsTwoHundredCastsOfTheConsumeCount() {
        assertEquals(200, Materializer.reagentWant(1)); // Death Spike / Major Heal
        assertEquals(600, Materializer.reagentWant(3)); // Greater Might lv3
        assertEquals(200, Materializer.reagentWant(0));
        assertTrue(SkillView.servitorSummon(1111));
    }
}

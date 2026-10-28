package org.l2start.economy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2start.economy.market.model.MarketItem;

class CraftsTest {
    @Test
    void createItemLevelFollowsTheDwarfSkillTree() {
        assertEquals(1, Crafts.craftLevel(1));
        assertEquals(4, Crafts.craftLevel(40));
        assertEquals(5, Crafts.craftLevel(43));
        assertEquals(6, Crafts.craftLevel(52));
        assertEquals(8, Crafts.craftLevel(64));
        assertEquals(9, Crafts.craftLevel(76));
    }

    @Test
    void titleKeepsItsSuffixWithin29Signs() {
        final String t = Crafts.title(true, "Sealed Zubei's Boots of Dark Crystal", true);
        org.junit.jupiter.api.Assertions.assertTrue(t.length() <= 29, t);
        org.junit.jupiter.api.Assertions.assertTrue(t.startsWith("Крафт: ") && t.endsWith(" и др."), t);
        assertEquals("Craft: Lance", Crafts.title(false, "Lance", false));
    }

    @Test
    void gearCraftOnlyBandA() {
        assertFalse(Crafts.gearGradeOk(new MarketItem(1, "Claymore", "Weapon", MarketItem.Grade.D, 1, false, false, 1)));
        assertFalse(Crafts.gearGradeOk(new MarketItem(2, "Composite", "Armor", MarketItem.Grade.C, 1, false, false, 2)));
        assertTrue(Crafts.gearGradeOk(new MarketItem(3, "Damascus", "Weapon", MarketItem.Grade.B, 1, false, false, 3)));
        assertTrue(Crafts.gearGradeOk(new MarketItem(4, "Tallum", "Weapon", MarketItem.Grade.A, 1, false, false, 4)));
        assertFalse(Crafts.gearGradeOk(new MarketItem(5, "S", "Weapon", MarketItem.Grade.S, 1, false, false, 5)));
        assertTrue(Crafts.gearGradeOk(new MarketItem(6, "Steel", "EtcItem", MarketItem.Grade.NONE, 1, true, false, 1)));
    }
}

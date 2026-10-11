package org.l2start.economy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}

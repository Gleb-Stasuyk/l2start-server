package org.l2start.olympiad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;

class OlympiadClassMapTest {
    @Test
    void everyAllowlistClassIsACombatThirdWithOneSecond() {
        for (int third : OlympiadRules.COMBAT_THIRD) {
            final PlayerClass c = PlayerClass.getPlayerClass(third);
            assertNotNull(c, "class " + third);
            assertEquals(3, c.level(), "3rd " + third);
            assertTrue(!c.isSummoner(), third + " must not be a summoner");
            assertNotNull(c.getParent());
            assertEquals(2, c.getParent().level());
            assertEquals(0, c.getRootClass().level());
            int found = -1;
            for (PlayerClass n : c.getParent().getNextClasses()) {
                found = n.getId();
                break;
            }
            assertEquals(third, found);
        }
    }

    @Test
    void excludedAreSupportsOrSummoners() {
        for (int id : OlympiadRules.EXCLUDED_THIRD) {
            final PlayerClass c = PlayerClass.getPlayerClass(id);
            assertNotNull(c);
            assertEquals(3, c.level());
            assertTrue(c.isSummoner() || isPriestOrOverlord(id), "excluded " + id + " should be support/summoner");
        }
    }

    private static boolean isPriestOrOverlord(int id) {
        return id == 97 || id == 98 || id == 105 || id == 112 || id == 115 || id == 116;
    }
}

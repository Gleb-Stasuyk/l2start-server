package org.l2start.olympiad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Bots fought the olympiad without shots: stock turns auto-shots off before the stadium and bots never turned them on. */
class OlympiadShotsTest {
    @Test
    void stockDisablesAutoShotsAndBlessedSps() throws Exception {
        final String game = Files.readString(Path.of("core/java/org/l2jmobius/gameserver/mechanics/olympiad/OlympiadGame.java"));
        assertTrue(game.contains("player.disableAutoShotsAll();"), "the cause: stock switches auto-shots off");
        final String ini = Files.readString(Path.of("core/dist/game/config/Olympiad.ini"));
        assertTrue(ini.contains("OlympiadDisableBlessedSpiritShots = True"));
    }

    @Test
    void armsRegularShotsAfterStockDisabledThem() {
        // A/S soulshots, A/S regular and blessed spiritshots, beast shots in the bag; stock cleared the auto list
        final List<Integer> arm = OlympiadRules.shotsToArm(List.of(1466, 1467, 2513, 2514, 3951, 3952, 6645, 6646, 6647, 57), Set.of());
        assertEquals(List.of(1466, 1467, 2513, 2514, 6645, 6646), arm);
    }

    @Test
    void neverBlessedAndNothingWhenAlreadyOn() {
        for (int blessed : new int[] { 3947, 3948, 3949, 3950, 3951, 3952, 6647 }) {
            assertFalse(OlympiadRules.STADIUM_SHOTS.contains(blessed), "blessed " + blessed);
        }
        assertEquals(List.of(), OlympiadRules.shotsToArm(List.of(1467, 2514), Set.of(1467, 2514)));
        assertEquals(List.of(2514), OlympiadRules.shotsToArm(List.of(1467, 2514), Set.of(1467)));
    }
}

package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BrainConfigTest {
    @AfterEach
    void defaults() {
        BotsBrainBasicModule.apply("0.5", true, "40,60", "25", "120,400");
    }

    @Test
    void parsesTheSettings() {
        BotsBrainBasicModule.apply("0.8", false, "55, 35", "30", "150,500");
        assertEquals(0.8, BasicBrain.fightBackBase, 1e-9);
        assertFalse(BasicBrain.potions);
        assertEquals(35, BasicBrain.potionLo, 1e-9);
        assertEquals(55, BasicBrain.potionHi, 1e-9);
        assertEquals(30, BasicBrain.fleeHp, 1e-9);
        assertEquals(150, BasicBrain.kiteClose);
        assertEquals(500, BasicBrain.kiteTo);
        assertTrue(BotsBrainBasicModule.describe().contains("reach+150 -> 500"), BotsBrainBasicModule.describe());
    }

    @Test
    void zeroSwitchesKitingOffAndBadValuesKeepDefaults() {
        BotsBrainBasicModule.apply("x", true, "oops", "", "0");
        assertEquals(0.5, BasicBrain.fightBackBase, 1e-9);
        assertEquals(40, BasicBrain.potionLo, 1e-9);
        assertEquals(60, BasicBrain.potionHi, 1e-9);
        assertEquals(25, BasicBrain.fleeHp, 1e-9);
        assertEquals(0, BasicBrain.kiteClose);
        assertTrue(BotsBrainBasicModule.describe().contains("kiting off"));
    }

    @Test
    void socialNoiseQuietNormalLively() {
        assertEquals(0.5, BotsBrainBasicModule.socialNoiseOf("quiet"), 1e-9);
        assertEquals(1.0, BotsBrainBasicModule.socialNoiseOf("normal"), 1e-9);
        assertEquals(2.0, BotsBrainBasicModule.socialNoiseOf("lively"), 1e-9);
        assertEquals(1.0, BotsBrainBasicModule.socialNoiseOf("weird"), 1e-9);
        assertEquals(2.0, BotsBrainBasicModule.socialNoiseOf("LIVELY"), 1e-9);
    }

    @Test
    void pvpAggressionModes() {
        assertEquals(PvpDecision.Mode.OFF, PvpDecision.Mode.of("off"));
        assertEquals(PvpDecision.Mode.LOW, PvpDecision.Mode.of("low"));
        assertEquals(PvpDecision.Mode.NORMAL, PvpDecision.Mode.of("normal"));
        assertEquals(PvpDecision.Mode.NORMAL, PvpDecision.Mode.of("on"));
        assertEquals(PvpDecision.Mode.HIGH, PvpDecision.Mode.of("HIGH"));
        assertEquals(PvpDecision.Mode.NORMAL, PvpDecision.Mode.of(""));
    }
}

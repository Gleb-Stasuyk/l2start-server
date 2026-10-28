package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** MaxBots caps bots in the world; the database grows on demand; warm-up keeps only a starter reserve per race. */
class PoolOnDemandTest {
    @Test
    void reserveFillsTheEmptiestRaceFirst() {
        assertEquals(2, BotsCoreModule.reserveGap(new int[] { 3, 4, 0, 1, 4 }, 4));
        assertEquals(-1, BotsCoreModule.reserveGap(new int[] { 4, 4, 5, 4, 9 }, 4));
        assertEquals(-1, BotsCoreModule.reserveGap(new int[] { 0, 0, 0, 0, 0 }, 0));
        assertEquals(0, BotsCoreModule.reserveGap(new int[] { 0, 0, 0, 0, 0 }, 1));
    }

    @Test
    void capCountsBotsInTheWorldOnly() throws Exception {
        final String runner = Files.readString(Path.of("modules/bots-core/src/org/l2start/bots/core/DirectorRunner.java"));
        final String module = Files.readString(Path.of("modules/bots-core/src/org/l2start/bots/core/BotsCoreModule.java"));
        assertTrue(runner.contains("director.setCanCreate(module.inWorldActive() < ctx.config().getInt(\"MaxBots\"))"));
        assertFalse(runner.contains("active().size() >= ctx.config().getInt(\"MaxBots\")"));
        assertFalse(module.contains("active().size() < max"), "warm-up no longer fills the database up to MaxBots");
        assertTrue(module.contains("(r.status() == BotRecord.Status.ACTIVE) && r.inWorld()"), "traders never count");
    }
}

package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Phase 7e: stock clan helpers (offline create / migration rules). */
class StockClanFactoryTest {
    @Test
    void membersOfMarksSingleLeader() {
        final List<StockClanFactory.Member> m = StockClanFactory.membersOf(100, List.of(100, 200, 300));
        assertEquals(3, m.size());
        assertTrue(m.get(0).leader());
        assertEquals(100, m.get(0).charId());
        assertFalse(m.get(1).leader());
        assertFalse(m.get(2).leader());
    }

    @Test
    void needsMigrateWhenProvisionalOrMissingStock() {
        assertTrue(StockClanFactory.needsStockMaterialize(1_900_000_048, false));
        assertTrue(StockClanFactory.needsStockMaterialize(1_900_000_048, true));
        assertTrue(StockClanFactory.needsStockMaterialize(55_000, false));
        assertFalse(StockClanFactory.needsStockMaterialize(55_000, true));
    }

    @Test
    void leaderMinLevelIsForty() {
        assertEquals(40, ClanService.LEADER_MIN_LEVEL);
        assertTrue(ClanService.LEADER_MIN_LEVEL >= 10);
    }

    @Test
    void crestPayloadFitsClient() {
        ClanCrests.init(java.nio.file.Path.of("modules/bots-core/data/clans/crests"));
        assertTrue(ClanCrests.size() >= 1);
        final byte[] dds = ClanTemplates.crestBytes(1);
        assertTrue(dds.length > 0 && dds.length <= 256);
        assertEquals('D', dds[0]);
    }
}

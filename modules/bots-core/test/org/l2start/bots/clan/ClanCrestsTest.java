package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Phase 7e: BMP emblems → DDS for CrestTable. */
class ClanCrestsTest {
    @Test
    void loadsBmpCrestsAsDds() {
        ClanCrests.init(Path.of("modules/bots-core/data/clans/crests"));
        assertTrue(ClanCrests.size() >= 18, "crests " + ClanCrests.size() + " err=" + ClanCrests.lastError());
        final byte[] dds = ClanCrests.ddsForSeed(1);
        assertEquals('D', dds[0]);
        assertEquals('D', dds[1]);
        assertEquals('S', dds[2]);
        assertEquals(' ', dds[3]);
        assertTrue(dds.length <= 256, "client max 256, got " + dds.length);
        assertTrue(dds.length >= 128 + 96, "DDS header + DXT1 16x12");
        assertEquals(dds.length, ClanTemplates.crestBytes(1).length);
    }
}

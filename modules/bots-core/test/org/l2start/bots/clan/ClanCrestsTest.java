package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Phase 7e: BMP emblems → client-safe 16×16 DDS (pad from 16×12). */
class ClanCrestsTest {
    @Test
    void loadsBmpCrestsAsClientSafeDds() {
        ClanCrests.init(Path.of("modules/bots-core/data/clans/crests"));
        assertTrue(ClanCrests.size() >= 18, "crests " + ClanCrests.size() + " err=" + ClanCrests.lastError());
        final byte[] dds = ClanCrests.ddsForSeed(1);
        assertTrue(ClanCrests.isClientSafeCrest(dds), "len=" + dds.length);
        assertEquals(ClanCrests.DDS_SIZE, dds.length);
        final ByteBuffer bb = ByteBuffer.wrap(dds).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(12);
        assertEquals(ClanCrests.TEX_H, bb.getInt());
        assertEquals(ClanCrests.TEX_W, bb.getInt());
        assertEquals(dds.length, ClanTemplates.crestBytes(1).length);
    }

    @Test
    void rejectsShortSixteenByTwelvePayload() {
        final byte[] bad = new byte[224];
        bad[0] = 'D';
        bad[1] = 'D';
        bad[2] = 'S';
        bad[3] = ' ';
        assertFalse(ClanCrests.isClientSafeCrest(bad));
        assertFalse(ClanCrests.isClientSafeCrest(null));
        assertFalse(ClanCrests.isClientSafeCrest(new byte[0]));
    }

    @Test
    void syntheticCrestIsClientSafe() {
        final byte[] dds = ClanTemplates.syntheticCrestBytes(42);
        assertTrue(ClanCrests.isClientSafeCrest(dds));
        assertEquals(ClanCrests.DDS_SIZE, dds.length);
    }
}

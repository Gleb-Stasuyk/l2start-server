package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Phase 7e: BMP emblems → client-safe 16×16 DDS (stretch from 16×12). */
class ClanCrestsTest {
    @Test
    void loadsBmpCrestsAsClientSafeDds() {
        ClanCrests.init(Path.of("modules/bots-core/data/clans/crests"));
        assertTrue(ClanCrests.size() >= 18, "crests " + ClanCrests.size() + " err=" + ClanCrests.lastError());
        final byte[] dds = ClanCrests.ddsForSeed(1);
        assertTrue(ClanCrests.isClientSafeCrest(dds), "len=" + dds.length);
        assertEquals(ClanCrests.DDS_SIZE, dds.length);
        final ByteBuffer bb = ByteBuffer.wrap(dds).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(8);
        assertEquals(ClanCrests.DDS_FLAGS, bb.getInt());
        assertEquals(ClanCrests.TEX_H, bb.getInt());
        assertEquals(ClanCrests.TEX_W, bb.getInt());
        bb.position(108);
        assertEquals(ClanCrests.DDS_CAPS, bb.getInt());
        assertEquals(dds.length, ClanTemplates.crestBytes(1).length);
    }

    @Test
    void stretchFillsBottomRowsUnlikeBlackPad() {
        final BufferedImage design = new BufferedImage(ClanCrests.DESIGN_W, ClanCrests.DESIGN_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ClanCrests.DESIGN_H; y++) {
            for (int x = 0; x < ClanCrests.DESIGN_W; x++) {
                design.setRGB(x, y, 0xff203040 + (y * 0x010101));
            }
        }
        final int[] rgba = ClanCrests.stretchToTexture(design);
        assertEquals(ClanCrests.TEX_W * ClanCrests.TEX_H, rgba.length);
        // last row must come from design (not pure black pad)
        boolean anyNonBlack = false;
        for (int x = 0; x < ClanCrests.TEX_W; x++) {
            if ((rgba[(ClanCrests.TEX_H - 1) * ClanCrests.TEX_W + x] & 0xffffff) != 0) {
                anyNonBlack = true;
                break;
            }
        }
        assertTrue(anyNonBlack);
    }

    @Test
    void rejectsWrongFlagsOrShortPayload() {
        final byte[] bad = new byte[224];
        bad[0] = 'D';
        bad[1] = 'D';
        bad[2] = 'S';
        bad[3] = ' ';
        assertFalse(ClanCrests.isClientSafeCrest(bad));
        assertFalse(ClanCrests.isClientSafeCrest(null));
        assertFalse(ClanCrests.isClientSafeCrest(new byte[0]));
        final byte[] noLinear = ClanTemplates.syntheticCrestBytes(1).clone();
        final ByteBuffer bb = ByteBuffer.wrap(noLinear).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(8, 0x00001007); // missing DDSD_LINEARSIZE
        assertFalse(ClanCrests.isClientSafeCrest(noLinear));
    }

    @Test
    void syntheticCrestIsClientSafe() {
        final byte[] dds = ClanTemplates.syntheticCrestBytes(42);
        assertTrue(ClanCrests.isClientSafeCrest(dds));
        assertEquals(ClanCrests.DDS_SIZE, dds.length);
    }
}

package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Phase 7e: clan-chat rally detection. */
class ClanRallyParseTest {
    @Test
    void detectsRuAndEn() {
        assertTrue(ClanRallyParse.isRally("всем на сбор"));
        assertTrue(ClanRallyParse.isRally("Rally on spot"));
        assertTrue(ClanRallyParse.isRally("gather for RB"));
        assertTrue(ClanRallyParse.isRally("на рб сейчас"));
        assertFalse(ClanRallyParse.isRally("привет"));
        assertFalse(ClanRallyParse.isRally(""));
    }

    @Test
    void kindRbVsSpot() {
        assertEquals("rb", ClanRallyParse.kind("сбор на рб"));
        assertEquals("rb", ClanRallyParse.kind("go rb now"));
        assertEquals("spot", ClanRallyParse.kind("сбор на спот"));
    }
}

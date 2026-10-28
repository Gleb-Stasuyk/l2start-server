package org.l2start.bots.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Phase 6d: LFP/LFM and party-ask parsing (RU + EN). */
class LfpParseTest {
    @Test
    void looksLikeLfpRuAndEn() {
        assertTrue(LfpParse.looksLikeLfp("LFP 40+ heal Cruma"));
        assertTrue(LfpParse.looksLikeLfp("ищу пати 38 хил"));
        assertTrue(LfpParse.looksLikeLfp("LFM DD 40"));
        assertTrue(LfpParse.looksLikeLfp("нужен хил 35+"));
        assertTrue(LfpParse.looksLikeLfp("го пати на крумы"));
        assertFalse(LfpParse.looksLikeLfp("привет как дела"));
        assertFalse(LfpParse.looksLikeLfp("wts ssd 5k"));
        assertFalse(LfpParse.looksLikeLfp(null));
    }

    @Test
    void parseRoleAndLevel() {
        final LfpParse.Call a = LfpParse.parse("LFP 40+ heal");
        assertNotNull(a);
        assertEquals(LfpParse.Role.HEAL, a.role());
        assertEquals(40, a.level());

        final LfpParse.Call b = LfpParse.parse("ищу пати 38 хил Крумы");
        assertNotNull(b);
        assertEquals(LfpParse.Role.HEAL, b.role());
        assertEquals(38, b.level());

        final LfpParse.Call c = LfpParse.parse("LFM DD Cruma");
        assertNotNull(c);
        assertEquals(LfpParse.Role.DD, c.role());
        assertTrue(c.lookingForMore());

        final LfpParse.Call d = LfpParse.parse("looking for party");
        assertNotNull(d);
        assertEquals(LfpParse.Role.ANY, d.role());
        assertEquals(0, d.level());

        assertNull(LfpParse.parse("просто болтаю"));
    }

    @Test
    void roleOfAndFits() {
        assertEquals(LfpParse.Role.HEAL, LfpParse.roleOf(16)); // Bishop
        assertEquals(LfpParse.Role.BUFF, LfpParse.roleOf(17)); // Prophet
        assertEquals(LfpParse.Role.DD, LfpParse.roleOf(88)); // Samurai
        assertTrue(LfpParse.fits(LfpParse.Role.ANY, 88));
        assertTrue(LfpParse.fits(LfpParse.Role.HEAL, 16));
        assertFalse(LfpParse.fits(LfpParse.Role.HEAL, 88));
        assertTrue(LfpParse.levelOk(40, 0));
        assertTrue(LfpParse.levelOk(40, 45));
        assertFalse(LfpParse.levelOk(40, 55));
    }

    @Test
    void partyAskAndYes() {
        assertTrue(LfpParse.isPartyYes("+"));
        assertTrue(LfpParse.isPartyYes("я"));
        assertTrue(LfpParse.isPartyYes("инвайт"));
        assertTrue(LfpParse.isPartyYes("можно"));
        assertTrue(LfpParse.isPartyAsk("можно к вам?"));
        assertTrue(LfpParse.isPartyAsk("go farm?"));
        assertFalse(LfpParse.isPartyYes("привет"));
        assertFalse(LfpParse.isPartyAsk("сколько стоит ssd"));
    }

    @Test
    void socialBoardLimits() {
        final SocialBoard b = new SocialBoard(2);
        assertTrue(b.tryShout(1000));
        assertTrue(b.tryShout(2000));
        assertFalse(b.tryShout(3000));
        assertTrue(b.tryShout(1000 + 3_600_000L)); // next hour

        b.noteAgreed(7, 1, 5000);
        assertTrue(b.isAgreed(7, 1, 5000));
        assertTrue(b.isAgreed(7, 1, 5000 + SocialBoard.AGREED_MS - 1));
        assertFalse(b.isAgreed(7, 1, 5000 + SocialBoard.AGREED_MS + 1));
        assertFalse(b.isAgreed(7, 2, 5000));

        assertTrue(b.tryReply(7, 10_000, 2));
        assertTrue(b.tryReply(7, 10_100, 2));
        assertFalse(b.tryReply(7, 10_200, 2));
    }

    @Test
    void whisperHourLimitAndStats() {
        final SocialBoard b = new SocialBoard(4);
        b.setWhispersPerHour(2);
        assertTrue(b.tryWhisper(1, 1000));
        assertTrue(b.tryWhisper(1, 2000));
        assertFalse(b.tryWhisper(1, 3000));
        assertTrue(b.tryWhisper(2, 3000)); // another bot has its own budget
        assertTrue(b.tryWhisper(1, 1000 + 3_600_000L));

        b.noteInviteOk(5000);
        b.noteInviteNo(5100);
        b.noteEmote(5200);
        final var c = b.hourCounts(5300);
        assertTrue(c.get(SocialBoard.Stat.WHISPER) >= 3);
        assertEquals(1, c.get(SocialBoard.Stat.INVITE_OK).intValue());
        assertEquals(1, c.get(SocialBoard.Stat.INVITE_NO).intValue());
        assertEquals(1, c.get(SocialBoard.Stat.EMOTE).intValue());
        assertTrue(c.get(SocialBoard.Stat.LIMIT) >= 1);
    }
}

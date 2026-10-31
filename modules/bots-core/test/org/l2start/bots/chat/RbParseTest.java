package org.l2start.bots.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** RB party, iteration 1: which roles a player asks for in trade / shout. */
class RbParseTest {
    private static List<RbParse.Role> roles(String text) {
        return RbParse.parse(text).stream().map(RbParse.Slot::role).toList();
    }

    @Test
    void namedRolesOnlyAndOnlyForAnRb() {
        assertEquals(List.of(RbParse.Role.TANK, RbParse.Role.BISHOP), roles("ищу танка и биша на рб"));
        assertEquals(List.of(RbParse.Role.PROPHET), roles("нужен пп на РБ"));
        assertEquals(List.of(RbParse.Role.TANK), roles("LF tank for RB"));
        assertEquals(List.of(RbParse.Role.DD, RbParse.Role.DD), roles("2 дд на рб"));
        assertEquals(List.of(RbParse.Role.DD, RbParse.Role.DD, RbParse.Role.DD), roles("дд x3 рб"));
        assertTrue(roles("ищу танка на фарм").isEmpty(), "not an RB: the ordinary LFP");
        assertTrue(roles("кто на рб?").isEmpty(), "no role named");
        assertTrue(roles(null).isEmpty());
        assertTrue(RbParse.aboutRb("го рб"));
        assertFalse(RbParse.aboutRb("го пати"));
    }

    @Test
    void ddKindsNarrowTheClass() {
        final List<RbParse.Slot> sh = RbParse.parse("нужен сх на рб");
        assertEquals(1, sh.size());
        assertEquals(Set.of(RbParse.SPELLHOWLER), sh.get(0).classes());
        assertEquals(Set.of(RbParse.SPELLSINGER), RbParse.parse("сс на рб").get(0).classes());
        assertEquals(Set.of(RbParse.NECROMANCER), RbParse.parse("некр на рб").get(0).classes());
        assertEquals(RbParse.DDS, RbParse.parse("дд на рб").get(0).classes());
        assertEquals(4, RbParse.parse("танк биш пп сх рб").size());
    }

    @Test
    void neverMoreThanEightPlaces() {
        assertEquals(RbParse.MAX_SLOTS, RbParse.parse("9 дд на рб").size());
        assertEquals(RbParse.MAX_SLOTS, RbParse.parse("танк биш пп 5 дд сх на рб").size());
    }

    @Test
    void rolesOfBots() {
        assertEquals(RbParse.Role.BISHOP, RbParse.roleOf(16, 15)); // a future bishop is a cleric at 20-39
        assertEquals(RbParse.Role.PROPHET, RbParse.roleOf(17, 10));
        assertEquals(RbParse.Role.TANK, RbParse.roleOf(0, 106)); // Shillien Templar without a persona
        assertEquals(RbParse.Role.DD, RbParse.roleOf(40, 39));
        assertNull(RbParse.roleOf(9, 92));
        assertEquals(16, RbParse.futureOf(97));
        assertEquals(0, RbParse.futureOf(92));
        assertEquals(10, RbParse.startClassOf(13));
        assertEquals(38, RbParse.startClassOf(40));
        assertEquals(31, RbParse.startClassOf(33));
        assertEquals(-1, RbParse.startClassOf(9));
        assertTrue(RbParse.levelFits(38, 40));
        assertTrue(RbParse.levelFits(43, 40));
        assertFalse(RbParse.levelFits(37, 40));
        assertFalse(RbParse.levelFits(44, 40));
    }

    private static long dd(String text) {
        return RbParse.parse(text).stream().filter(x -> x.role() == RbParse.Role.DD).count();
    }

    @Test
    void countsInBothLanguagesAndWordOrders() {
        assertEquals(4, dd("lfp 4 dd rb"));
        assertEquals(4, dd("ищу 4 дд на рб"));
        assertEquals(3, dd("lf 3 dd for rb"));
        assertEquals(4, dd("need dd x4 rb"));
        assertEquals(4, dd("нужно дд х4 на рб"));
        assertEquals(2, dd("2х дд рб"));
        assertEquals(5, dd("LFP 5 DD RB"));
        assertEquals(4, RbParse.parse("lfp 4 dd rb").size(), "exactly the places asked for");
        assertEquals(RbParse.MAX_SLOTS, dd("lfp 9 dd rb"));
        assertEquals(1, dd("lv 45 dd for rb"), "a level is not a count");
    }

    @Test
    void englishRoleWords() {
        assertEquals(List.of(RbParse.Role.TANK, RbParse.Role.BISHOP, RbParse.Role.PROPHET, RbParse.Role.DD, RbParse.Role.DD, RbParse.Role.DD),
            roles("lf tank bish pp sh sps necro for rb"));
        assertEquals(List.of(RbParse.Role.BISHOP), roles("need bishop for rb"));
        assertEquals(Set.of(RbParse.SPELLHOWLER), RbParse.parse("lf sh rb").get(0).classes());
        assertEquals(Set.of(RbParse.SPELLSINGER), RbParse.parse("lf sps rb").get(0).classes());
        assertEquals(Set.of(RbParse.NECROMANCER), RbParse.parse("lf necro rb").get(0).classes());
        assertEquals(2, RbParse.parse("need 2 sh for rb").size());
        assertEquals(List.of(RbParse.Role.TANK, RbParse.Role.DD, RbParse.Role.DD), roles("rb: tank + 2 dd"));
    }

    @Test
    void fullPartyShoutIsBishopSeAndSevenMageDd() {
        assertTrue(RbParse.isFullParty("ищу фп на рб"));
        assertTrue(RbParse.isFullParty("ищу ФП на РБ"));
        assertTrue(RbParse.isFullParty("Ищу фп на rb"));
        assertFalse(RbParse.isFullParty("еще пати на рб"));
        assertFalse(RbParse.isFullParty("2 пати на рб"));
        assertFalse(RbParse.isFullParty("ищу фп"));
        assertFalse(RbParse.isFullParty("lfp 4 dd rb"), "lfp is not the word fp");
        final List<RbParse.Slot> fp = RbParse.parse("ищу фп на рб");
        assertEquals(RbParse.MAX_FP_SLOTS, fp.size());
        assertEquals(RbParse.Role.BISHOP, fp.get(0).role());
        assertEquals(RbParse.Role.ELDER, fp.get(1).role());
        assertEquals(Set.of(RbParse.SHILLIEN_ELDER), fp.get(1).classes());
        assertEquals(RbParse.FP_DD_COUNT, fp.stream().filter(s -> s.role() == RbParse.Role.DD).count());
        assertTrue(fp.stream().noneMatch(s -> s.role() == RbParse.Role.TANK));
        assertTrue(fp.stream().noneMatch(s -> s.role() == RbParse.Role.PROPHET));
        assertEquals(RbParse.DDS, fp.get(2).classes());
    }

    @Test
    void levelBandFromTheShout() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { 44, 48 }, RbParse.levelBand("ищу 5 дд на рб 44-48", 45));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { 52, 52 }, RbParse.levelBand("ищу 3 дд на рб 52 лвл", 45));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { 43, 48 }, RbParse.levelBand("ищу танка на рб", 45));
        assertEquals(5, roles("ищу 5 дд на рб 44-48").size());
        assertEquals(3, roles("ищу 3 дд на рб 52 лвл").size());
    }

    @Test
    void askedSeIsAnRbPlace() {
        assertEquals(java.util.List.of(RbParse.Role.ELDER), roles("ищу се на рб"));
        assertEquals(java.util.List.of(RbParse.Role.ELDER), roles("lf SE for rb"));
        assertTrue(roles("нужен шилен на рб").contains(RbParse.Role.ELDER));
        assertTrue(roles("все на рб танк").stream().noneMatch(r -> r == RbParse.Role.ELDER));
    }

    @Test
    void party1NeverAsksForSe() {
        assertTrue(roles("ищу танка и биша на рб").stream().noneMatch(r -> r == RbParse.Role.ELDER));
        assertTrue(roles("танк биш пп 5 дд сх на рб").stream().noneMatch(r -> r == RbParse.Role.ELDER));
        assertEquals(RbParse.Role.ELDER, RbParse.roleOf(43, 38));
        assertEquals(RbParse.Role.ELDER, RbParse.roleOf(0, 112));
        assertEquals(43, RbParse.futureOf(112));
        assertEquals(38, RbParse.startClassOf(43));
        assertEquals("се", RbParse.word(RbParse.Role.ELDER, true));
        assertEquals("se", RbParse.word(RbParse.Role.ELDER, false));
    }
}

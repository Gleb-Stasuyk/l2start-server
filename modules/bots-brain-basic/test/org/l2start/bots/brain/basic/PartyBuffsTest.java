package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** KT12 F3/F4: role buff sets and Wind Walk in town. */
class PartyBuffsTest {
    static final int PROPHET = 17;
    static final int BD = 34;
    static final int SWS = 21;
    static final int SORCERER = 12; // mage
    static final int GLADIATOR = 2; // melee
    static final int HAWKEYE = 9; // archer
    static final int PALADIN = 5; // tank
    static final int TREASURE_HUNTER = 8; // dagger

    @Test
    void rolesFromClassId() {
        assertTrue(PartyBuffs.roleOf(SORCERER) == PartyBuffs.Role.MAGE);
        assertTrue(PartyBuffs.roleOf(GLADIATOR) == PartyBuffs.Role.MELEE);
        assertTrue(PartyBuffs.roleOf(HAWKEYE) == PartyBuffs.Role.ARCHER);
        assertTrue(PartyBuffs.roleOf(PALADIN) == PartyBuffs.Role.TANK);
        assertTrue(PartyBuffs.roleOf(TREASURE_HUNTER) == PartyBuffs.Role.DAGGER);
    }

    @Test
    void mageDoesNotGetHasteOrMight() {
        final List<Integer> w = PartyBuffs.wanted(PROPHET, SORCERER);
        assertTrue(w.contains(PartyBuffs.BERSERKER));
        assertTrue(w.contains(1085)); // Acumen
        assertFalse(w.contains(1086)); // Haste
        assertFalse(w.contains(1068)); // Might
    }

    @Test
    void warriorDoesNotGetAcumenOrEmpower() {
        final List<Integer> w = PartyBuffs.wanted(PROPHET, GLADIATOR);
        assertTrue(w.contains(PartyBuffs.BERSERKER));
        assertTrue(w.contains(1068));
        assertTrue(w.contains(1086));
        assertFalse(w.contains(1085));
        assertFalse(w.contains(1059));
    }

    @Test
    void berserkerForEveryRole() {
        for (int target : new int[] { SORCERER, GLADIATOR, HAWKEYE, PALADIN, TREASURE_HUNTER }) {
            assertTrue(PartyBuffs.wanted(PROPHET, target).contains(PartyBuffs.BERSERKER), "target " + target);
        }
    }

    @Test
    void bdAndSwsAddDancesAndSongs() {
        final List<Integer> bd = PartyBuffs.wanted(BD, GLADIATOR);
        assertTrue(bd.contains(271)); // Dance of the Warrior
        final List<Integer> sws = PartyBuffs.wanted(SWS, GLADIATOR);
        assertTrue(sws.contains(269)); // Song of Hunter
        final List<Integer> bdMage = PartyBuffs.wanted(BD, SORCERER);
        assertTrue(bdMage.contains(273));
        assertFalse(bdMage.contains(271));
    }

    @Test
    void inPeaceOnlyWindWalk() {
        final List<Integer> w = PartyBuffs.wanted(PROPHET, GLADIATOR, true);
        assertTrue(w.equals(List.of(PartyBuffs.WIND_WALK)));
    }

    @Test
    void filterLearnableKeepsWwAndBersFirst() {
        final List<Integer> full = PartyBuffs.wanted(PROPHET, GLADIATOR);
        final var at40 = PartyBuffs.learnableIds(List.of(
            new int[] { 1204, 20 }, new int[] { 1068, 40 }, new int[] { 1040, 40 }), 40);
        final List<Integer> low = PartyBuffs.filterLearnable(full, at40);
        assertTrue(low.get(0) == PartyBuffs.WIND_WALK);
        assertFalse(low.contains(PartyBuffs.BERSERKER));
        assertTrue(low.contains(1068));
        final var at52 = PartyBuffs.learnableIds(List.of(
            new int[] { 1204, 20 }, new int[] { 1068, 40 }, new int[] { 1062, 52 }, new int[] { 1086, 52 }), 52);
        final List<Integer> mid = PartyBuffs.filterLearnable(full, at52);
        assertTrue(mid.contains(PartyBuffs.BERSERKER));
        assertTrue(mid.indexOf(PartyBuffs.WIND_WALK) < mid.indexOf(PartyBuffs.BERSERKER));
        assertTrue(mid.size() > low.size());
    }

    @Test
    void raidTankIsFullHuntSetExceptBerserker() {
        final List<Integer> w = PartyBuffs.wantedRaid(PROPHET, PALADIN);
        final List<Integer> hunt = PartyBuffs.wanted(PROPHET, PALADIN);
        assertTrue(w.contains(PartyBuffs.WIND_WALK));
        assertTrue(w.contains(PartyBuffs.SHIELD));
        assertTrue(w.contains(PartyBuffs.MENTAL_SHIELD));
        assertTrue(w.contains(PartyBuffs.BLESSED_BODY));
        assertTrue(w.contains(PartyBuffs.BLESSED_SOUL));
        assertTrue(w.contains(PartyBuffs.MAGIC_BARRIER));
        assertTrue(w.contains(PartyBuffs.REGENERATION));
        assertTrue(w.contains(PartyBuffs.MIGHT));
        assertTrue(w.contains(PartyBuffs.BLESS_SHIELD));
        assertTrue(w.contains(PartyBuffs.GREATER_SHIELD));
        assertFalse(w.contains(PartyBuffs.BERSERKER));
        assertFalse(w.contains(PartyBuffs.HASTE), "Haste is melee-only, not hunt-tank");
        assertFalse(w.contains(PartyBuffs.FOCUS), "Focus is melee-only, not hunt-tank");
        assertEquals(hunt.size() - 1, w.size());
        for (int id : hunt) {
            if (id != PartyBuffs.BERSERKER) {
                assertTrue(w.contains(id), "missing hunt buff " + id);
            }
        }
    }

    @Test
    void raidMageOmitsRegenMagicBarrierMentalShield() {
        final List<Integer> w = PartyBuffs.wantedRaid(PROPHET, SORCERER);
        assertTrue(w.contains(PartyBuffs.WIND_WALK));
        assertTrue(w.contains(PartyBuffs.ACUMEN));
        assertTrue(w.contains(PartyBuffs.BERSERKER));
        assertFalse(w.contains(PartyBuffs.REGENERATION));
        assertFalse(w.contains(PartyBuffs.MAGIC_BARRIER));
        assertFalse(w.contains(PartyBuffs.MENTAL_SHIELD));
        assertFalse(w.contains(PartyBuffs.MIGHT));
        assertTrue(w.size() < PartyBuffs.wanted(PROPHET, SORCERER).size());
    }

    @Test
    void raidSetIsShorterSoManaLasts() {
        int raid = 0;
        int hunt = 0;
        for (int t : new int[] { SORCERER, GLADIATOR, HAWKEYE, PALADIN, TREASURE_HUNTER }) {
            raid += PartyBuffs.wantedRaid(PROPHET, t).size();
            hunt += PartyBuffs.wanted(PROPHET, t).size();
        }
        assertTrue(raid < hunt, "raid " + raid + " hunt " + hunt);
        assertTrue(raid <= 40, "still too many: " + raid);
    }

    @Test
    void secondPartyRaidSetOmitsBerserker() {
        final List<Integer> mage = PartyBuffs.wantedRaid(PROPHET, SORCERER, false);
        assertTrue(mage.contains(PartyBuffs.ACUMEN));
        assertTrue(mage.contains(PartyBuffs.EMPOWER));
        assertFalse(mage.contains(PartyBuffs.BERSERKER));
        final List<Integer> party1 = PartyBuffs.wantedRaid(PROPHET, SORCERER, true);
        assertTrue(party1.contains(PartyBuffs.BERSERKER));
        final List<Integer> tank = PartyBuffs.wantedRaid(PROPHET, PALADIN, false);
        assertFalse(tank.contains(PartyBuffs.BERSERKER));
    }
}

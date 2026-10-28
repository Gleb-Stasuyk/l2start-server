package org.l2start.olympiad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OlympiadRulesTest {

    @Test
    void combatAllowlistHas22AndExcludesSupports() {
        assertEquals(22, OlympiadRules.COMBAT_THIRD.size());
        assertEquals(22, OlympiadRules.COMBAT_THIRD_SET.size());
        for (int id : new int[] { 88, 89, 90, 91, 92, 93, 94, 95, 99, 100, 101, 102, 103, 106, 107, 108, 109, 110, 113, 114, 117, 118 }) {
            assertTrue(OlympiadRules.allowedClass(id), "allowed " + id);
        }
        for (int id : new int[] { 96, 97, 98, 104, 105, 111, 112, 115, 116 }) {
            assertFalse(OlympiadRules.allowedClass(id), "excluded " + id);
            assertTrue(OlympiadRules.EXCLUDED_THIRD.contains(id));
        }
        assertFalse(OlympiadRules.allowedClass(0));
        assertFalse(OlympiadRules.allowedClass(57));
    }

    @Test
    void baseQuotasFor40SumTo40() {
        final Map<Integer, Integer> q = OlympiadRules.baseQuotas(40);
        assertEquals(22, q.size());
        int sum = 0;
        int twos = 0;
        int ones = 0;
        for (int id : OlympiadRules.COMBAT_THIRD) {
            final int n = q.get(id);
            sum += n;
            if (n == 2) {
                twos++;
            } else if (n == 1) {
                ones++;
            }
            assertTrue(n >= 1 && n <= 2, id + "=" + n);
        }
        assertEquals(40, sum);
        assertEquals(18, twos); // 40 / 22 = 1 remainder 18
        assertEquals(4, ones);
        assertEquals(2, OlympiadRules.baseQuota(88, 40));
        assertEquals(1, OlympiadRules.baseQuota(118, 40));
        assertEquals(0, OlympiadRules.baseQuota(96, 40));
    }

    @Test
    void baseQuotasEmptyWhenRosterZero() {
        assertTrue(OlympiadRules.baseQuotas(0).isEmpty());
        assertEquals(0, OlympiadRules.baseQuota(88, 0));
    }

    @Test
    void rosterTargetsAddRivalsOnlyForLiveClassed() {
        final Map<Integer, Integer> idle = OlympiadRules.rosterTargets(40, 6, Set.of());
        assertEquals(2, idle.get(90).intValue());
        final Map<Integer, Integer> live = OlympiadRules.rosterTargets(40, 6, Set.of(90, 96));
        assertEquals(8, live.get(90).intValue()); // 2 + 6
        assertFalse(live.containsKey(96)); // excluded class is ignored
        int sum = 0;
        for (int n : live.values()) {
            sum += n;
        }
        assertEquals(46, sum); // 40 + 6, one waiting class
    }

    @Test
    void rareClassedGetsAtLeastBotsNeededEvenIfBaseQuotaIsOne() {
        // Storm Screamer 110 has base 2; botsNeeded(1,5)=5. rivals 6 → 8, still enough.
        assertEquals(5, OlympiadRules.botsNeeded(1, 5));
        assertEquals(9, OlympiadRules.botsNeeded(1, 9));
        assertEquals(0, OlympiadRules.botsNeeded(0, 5));
        final Map<Integer, Integer> live = OlympiadRules.rosterTargets(40, 0, Set.of(110), Map.of(110, 1), 5);
        assertEquals(5, live.get(110).intValue(), "rivals 0: still enough bots to hit classed cap 6");
        final Map<Integer, Integer> withRivals = OlympiadRules.rosterTargets(40, 6, Set.of(110), Map.of(110, 1), 5);
        assertEquals(8, withRivals.get(110).intValue()); // max(2+6, 5)
    }

    @Test
    void classDeficitWinsOverGlobalRosterCap() {
        // leftover extras of class 90 keep total at 46; class 110 still has only 2 of 8 needed
        final Map<Integer, Integer> target = OlympiadRules.rosterTargets(40, 6, Set.of(110), Map.of(110, 1), 5);
        final Map<Integer, Integer> have = new java.util.HashMap<>(OlympiadRules.baseQuotas(40));
        have.put(90, 8); // leftover rivals from a previous live classed
        have.put(110, 2);
        int total = 0;
        for (int n : have.values()) {
            total += n;
        }
        int want = 0;
        for (int n : target.values()) {
            want += n;
        }
        assertTrue(total >= 40);
        assertEquals(110, OlympiadRules.pickCreateClass(target, have), "must create SS even if total >= want (" + total + "/" + want + ")");
        assertEquals(0, OlympiadRules.pickCreateClass(target, target));
    }

    @Test
    void poolCapEvenAndAtLeastMin() {
        assertEquals(0, OlympiadRules.poolCap(0, 5));
        assertEquals(6, OlympiadRules.poolCap(1, 5)); // max(5,2)=5 odd → 6
        assertEquals(10, OlympiadRules.poolCap(1, 9));
        assertEquals(6, OlympiadRules.poolCap(5, 5)); // max(5,6)=6 even
        assertEquals(10, OlympiadRules.poolCap(9, 9));
        assertEquals(12, OlympiadRules.poolCap(10, 9));
    }

    @Test
    void restWindow() {
        assertTrue(OlympiadRules.stillResting(1000, 2000));
        assertFalse(OlympiadRules.stillResting(2000, 2000));
        assertFalse(OlympiadRules.stillResting(2001, 2000));
    }

    @Test
    void fillClassedWhenLivePlayerWaits() {
        final OlympiadRules.Action a = OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 0, 1, true, 1, 0, 5, 9, 10, 99));
        assertEquals(OlympiadRules.Action.REGISTER_CLASSED, a);
    }

    @Test
    void fillNonClassedWhenLivePlayerWaits() {
        final OlympiadRules.Action a = OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 1, 0, true, 0, 1, 5, 9, 10, 99));
        assertEquals(OlympiadRules.Action.REGISTER_NON_CLASSED, a);
    }

    @Test
    void stopFillingWhenPoolCapReached() {
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 1, 0, true, 0, 10, 5, 9, 10, 0)));
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 0, 1, true, 6, 0, 5, 9, 10, 0)));
    }

    @Test
    void backgroundChanceIntoNonClassedWhenNoLive() {
        assertEquals(OlympiadRules.Action.REGISTER_NON_CLASSED,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 0, 0, false, 0, 0, 5, 9, 10, 0)));
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 0, 0, false, 0, 0, 5, 9, 10, 10)));
    }

    @Test
    void noBackgroundWhenALivePlayerIsWaitingElsewhere() {
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 0, 0, true, 0, 0, 5, 9, 100, 0)));
    }

    @Test
    void unregisterClassedWhenNoLiveInThatClass() {
        assertEquals(OlympiadRules.Action.UNREGISTER,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, true, true, false, 18, 0, 0, false, 3, 0, 5, 9, 10, 0)));
    }

    @Test
    void doNotUnregisterDuringMatch() {
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, true, true, true, false, 18, 0, 0, false, 3, 0, 5, 9, 10, 0)));
    }

    @Test
    void restBlocksSignup() {
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, true, 18, 1, 1, true, 1, 1, 5, 9, 100, 0)));
    }

    @Test
    void windowOffUnregistersIdle() {
        assertEquals(OlympiadRules.Action.UNREGISTER,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(false, false, true, false, false, 18, 0, 0, false, 0, 2, 5, 9, 10, 0)));
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(false, false, false, false, false, 18, 0, 0, false, 0, 0, 5, 9, 10, 0)));
    }

    @Test
    void stockMinPoints() {
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 2, 0, 1, true, 1, 0, 5, 9, 10, 0)));
        assertEquals(OlympiadRules.Action.REGISTER_CLASSED,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 3, 0, 1, true, 1, 0, 5, 9, 10, 0)));
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 4, 1, 0, true, 0, 1, 5, 9, 10, 0)));
        assertEquals(OlympiadRules.Action.REGISTER_NON_CLASSED,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 5, 1, 0, true, 0, 1, 5, 9, 10, 0)));
        // unknown / first register: stock grants start points
        assertEquals(OlympiadRules.Action.REGISTER_CLASSED,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, -1, 0, 1, true, 1, 0, 5, 9, 10, 0)));
    }

    @Test
    void alreadyRegisteredStays() {
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, true, false, false, 18, 1, 0, true, 0, 3, 5, 9, 10, 0)));
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, true, true, false, 18, 0, 2, true, 3, 0, 5, 9, 10, 0)));
    }

    @Test
    void hopsFromNonClassedToClassedWhenLivePlayerOfThisClassWaits() {
        // registered non-classed, live classed this, classed pool still short of cap 6
        assertEquals(OlympiadRules.Action.SWITCH_TO_CLASSED,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, true, false, false, 18, 0, 1, true, 2, 4, 5, 9, 10, 0)));
        // classed already at cap: stay in non-classed
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, true, false, false, 18, 0, 1, true, 6, 4, 5, 9, 10, 0)));
    }

    @Test
    void signUpChanceOnlyForBackground() {
        // live waiting (anyLive): chance 100 and roll 0 still does not background-signup this other class
        assertEquals(OlympiadRules.Action.NONE,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 0, 0, true, 0, 0, 5, 9, 100, 0)));
        // no live: chance applies
        assertEquals(OlympiadRules.Action.REGISTER_NON_CLASSED,
            OlympiadRules.shouldSignUp(new OlympiadRules.SignUpInput(true, false, false, false, false, 18, 0, 0, false, 0, 0, 5, 9, 100, 0)));
    }
}

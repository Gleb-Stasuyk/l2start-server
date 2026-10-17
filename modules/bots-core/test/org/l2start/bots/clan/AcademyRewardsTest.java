package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Phase 7f: academy eligibility and one-time payouts. */
class AcademyRewardsTest {
    @Test
    void eligibleMatchesPlan() {
        assertTrue(AcademyRewards.eligible(false, 20, 0));
        assertTrue(AcademyRewards.eligible(false, 39, 1));
        assertFalse(AcademyRewards.eligible(true, 20, 0));
        assertFalse(AcademyRewards.eligible(false, 40, 1));
        assertFalse(AcademyRewards.eligible(false, 30, 2));
    }

    @Test
    void graduateOnceAt40() {
        assertTrue(AcademyRewards.graduateEligible(true, false, 40));
        assertTrue(AcademyRewards.graduateEligible(true, false, 45));
        assertFalse(AcademyRewards.graduateEligible(true, false, 39));
        assertFalse(AcademyRewards.graduateEligible(false, false, 40));
        assertFalse(AcademyRewards.graduateEligible(true, true, 40));
    }

    @Test
    void recruiterTownsAreFour() {
        assertEquals(4, AcademyRecruiters.TOWNS.size());
        assertTrue(AcademyRecruiters.TITLE.length() <= 16);
        assertTrue(AcademyRecruiters.asksAcademy("можно в академию"));
        assertTrue(AcademyRecruiters.asksAcademy("academy pls"));
        assertTrue(AcademyRecruiters.asksAcademy("возьмите в акаду"));
        assertTrue(AcademyRecruiters.asksAcademy("take me academy"));
        assertFalse(AcademyRecruiters.asksAcademy("hello"));
    }

    @Test
    void noProactiveInvite() {
        assertFalse(AcademyRecruiters.proactiveInviteAllowed());
    }
}

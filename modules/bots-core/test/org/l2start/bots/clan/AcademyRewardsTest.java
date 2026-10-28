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
        assertTrue(AcademyRewards.graduateEligible(false, false, 40), "join reward not taken: graduation still paid");
        assertFalse(AcademyRewards.graduateEligible(true, true, 40));
    }

    @Test
    void recruiterTownsAreFour() {
        assertEquals(4, AcademyRecruiters.TOWNS.size());
        assertTrue(AcademyRecruiters.TITLE.length() <= 16);
        for (String t : AcademyRecruiters.TITLES) {
            assertTrue(t.length() <= 16, t);
        }
        assertTrue(AcademyRecruiters.isRecruiterTitle("Набор в академ"));
        assertTrue(AcademyRecruiters.isRecruiterTitle("LF academ"));
        final int[] iv = AcademyRecruiters.parseIntervalSec("30-100");
        assertEquals(30, iv[0]);
        assertEquals(100, iv[1]);
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

    @Test
    void graduateGets200kWhenJoinRewardNotTaken() {
        org.junit.jupiter.api.Assertions.assertEquals(200_000L, AcademyRewards.graduateAmount(false, 50_000L, 150_000L));
        org.junit.jupiter.api.Assertions.assertEquals(150_000L, AcademyRewards.graduateAmount(true, 50_000L, 150_000L));
    }
}

package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Clan recruiters: a clear reason instead of silence, the clan with its hall in the recruiter's town, the duty titles. */
class RecruiterClanPassTest {
    static final long NOW = 1_000_000_000L;

    @Test
    void refusalExplainsEveryGameRule() {
        assertNull(AcademyRecruiters.clanRefusal(null, 45, 0, 0, 39, 40, NOW));
        assertTrue(AcademyRecruiters.clanRefusal("Orcs", 45, 0, 0, 10, 40, NOW).contains("Orcs"));
        assertTrue(AcademyRecruiters.clanRefusal(null, 30, 0, 0, 10, 40, NOW).contains("40"));
        assertTrue(AcademyRecruiters.clanRefusal(null, 45, NOW + 3_600_000L * 5, 0, 10, 40, NOW).contains("5 ч."));
        assertTrue(AcademyRecruiters.clanRefusal(null, 45, 0, NOW + 1000, 10, 40, NOW).contains("исключили"));
        assertTrue(AcademyRecruiters.clanRefusal(null, 45, 0, 0, 40, 40, NOW).contains("полон"), "NightHawk 40/40: the case ork hit");
    }

    @Test
    void recruiterPrefersTheClanWithItsHallInItsTown() {
        final int own = AcademyRecruiters.clanFit("Goddard", "Goddard", 30, 40);
        final int none = AcademyRecruiters.clanFit("", "Goddard", 30, 40);
        final int elsewhere = AcademyRecruiters.clanFit("Gludio", "Goddard", 30, 40);
        assertTrue((own > none) && (none > elsewhere));
        assertTrue(AcademyRecruiters.clanFit("", "Goddard", 30, 40) > AcademyRecruiters.clanFit("", "Goddard", 40, 40), "room for a player counts");
    }

    @Test
    void shoutNamesTheHallTownInRussian() {
        final String s = AcademyRecruiters.clanShoutLine("NightHawk", 8, "Goddard", 39, "Goddard");
        assertTrue(s.contains("КХ в Годдарде") && s.contains("Стою у ГК Годдарда"), s);
    }

    @Test
    void dutyTitles() {
        assertEquals(java.util.List.of("Набор в клан"), AcademyRecruiters.CLAN_TITLES);
        assertEquals(java.util.List.of("Набор в академию"), AcademyRecruiters.TITLES);
        assertTrue("Набор в академию".length() <= 16);
        assertTrue(AcademyRecruiters.anyRecruiterTitle("Набор в академ"), "an old title is cleared too");
        assertTrue(AcademyRecruiters.anyRecruiterTitle("Набор в клан"));
    }
}

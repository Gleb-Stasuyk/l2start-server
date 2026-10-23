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
    void fiveTownsEachWithItsClanAndMegaClansNeverRecruit() {
        assertEquals(java.util.List.of("Gludio", "Dion", "Giran", "Aden", "Goddard"), ClanService.TOWNS);
        assertEquals(ClanService.TOWNS, AcademyRecruiters.CLAN_TOWNS.stream().map(AcademyRecruiters.Spot::town).toList(), "one clan recruiter per town clan");
        assertTrue(ClanService.TOWN_CLAN_SIZE < ClanService.BIG_TARGET, "a town clan is never a big (mega) clan");
        assertTrue(ClanService.recruits(false));
        assertTrue(!ClanService.recruits(true), "mega clans: no recruiter, no shouts, no accepting");
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

    @Test
    void clansAreGradedByTheirHallTown() {
        assertEquals(5, ClanService.townGrade("Gludio").clanLevel());
        assertEquals(5, ClanService.townGrade("Dion").clanLevel());
        assertEquals(6, ClanService.townGrade("Giran").clanLevel());
        assertEquals(7, ClanService.townGrade("Aden").clanLevel());
        assertEquals(8, ClanService.townGrade("Goddard").clanLevel());
        assertNull(ClanService.townGrade("Heine"));
        String prev = null;
        for (String t : java.util.List.of("Dion", "Giran", "Aden", "Goddard")) {
            final ClanService.TownGrade g = ClanService.townGrade(t);
            assertTrue((g.leaderLevel() <= g.maxLevel()) && (g.leaderLevel() >= (g.maxLevel() - 3)), t + ": leader near the top");
            if (prev != null) {
                final ClanService.TownGrade p = ClanService.townGrade(prev);
                assertTrue((g.minLevel() > p.minLevel()) && (g.maxLevel() > p.maxLevel()), t + " above " + prev);
            }
            prev = t;
            for (long seed = 0; seed < 200; seed++) {
                final int lv = ClanService.memberTarget(seed * 7919, g);
                assertTrue((lv >= g.minLevel()) && (lv <= g.maxLevel()), t + " " + lv);
            }
        }
        assertTrue(AcademyRecruiters.clanShoutLine("NightHawk", 8, "Goddard", 39, "Goddard").contains("8 лвл"));
    }
}

package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;

/** Recruiters say where they stand; clan recruiters (Dion, Giran, Aden, Goddard); no PK in towns. */
class RecruitersPassTest {
    @Test
    void shoutNamesTheGatekeeper() {
        assertEquals("Стою у ГК Глудио.", AcademyRecruiters.standsAt("Gludio"));
        assertEquals("Стою у ГК Гирана.", AcademyRecruiters.standsAt("Giran"));
        final String s = AcademyRecruiters.clanShoutLine("NightHawk", 8, "Гиран", 40, "Aden");
        assertTrue(s.contains("NightHawk") && s.contains("8 лвл") && s.contains("Стою у ГК Адена"), s);
        assertTrue(s.contains("40+") && !s.contains("20+"), s);
    }

    @Test
    void clanRecruitersInTheFourBigTowns() {
        assertEquals(5, AcademyRecruiters.CLAN_TOWNS.size());
        assertTrue(AcademyRecruiters.CLAN_TOWNS.stream().map(AcademyRecruiters.Spot::town).toList().containsAll(java.util.List.of("Dion", "Giran", "Aden", "Goddard")));
        assertTrue(AcademyRecruiters.clanEligible(false, 40));
        assertTrue(AcademyRecruiters.clanEligible(false, 60));
        assertFalse(AcademyRecruiters.clanEligible(false, 39));
        assertFalse(AcademyRecruiters.clanEligible(false, 20));
        assertFalse(AcademyRecruiters.clanEligible(true, 50));
        assertTrue(AcademyRecruiters.asksClan("возьмите в клан"));
        assertTrue(AcademyRecruiters.CLAN_TITLES.stream().allMatch(t -> t.length() <= 16));
    }

    @Test
    void noPkInTowns() {
        assertFalse(org.l2start.bots.core.DirectorRunnerAccess.pkMayGo(true, Assignment.Kind.TOWN));
        assertFalse(org.l2start.bots.core.DirectorRunnerAccess.pkMayGo(true, Assignment.Kind.TRANSIT));
        assertTrue(org.l2start.bots.core.DirectorRunnerAccess.pkMayGo(true, Assignment.Kind.HUNT));
        assertTrue(org.l2start.bots.core.DirectorRunnerAccess.pkMayGo(false, Assignment.Kind.TOWN));
    }
}

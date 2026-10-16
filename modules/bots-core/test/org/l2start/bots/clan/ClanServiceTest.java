package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.director.Director;

/** Phase 7d: XML templates, party mix rules, mate filter, provisional ids. */
class ClanServiceTest {
    @Test
    void loadsBotClansXml() throws Exception {
        final List<ClanTemplate> t = ClanTemplates.load(Path.of("modules/bots-core/data/clans/BotClans.xml"));
        assertTrue(t.size() >= 12, "templates " + t.size());
        for (ClanTemplate c : t) {
            assertTrue(c.level() >= 2 && c.level() <= 8, c.name() + " lvl " + c.level());
            assertFalse(c.name().isBlank());
            assertFalse(c.id().isBlank());
        }
        ClanCrests.init(Path.of("modules/bots-core/data/clans/crests"));
        final byte[] crest = ClanTemplates.crestBytes(1);
        assertTrue(crest.length <= 256 && crest.length >= 128);
        assertEquals('D', crest[0]);
        assertEquals('D', crest[1]);
        assertEquals('S', crest[2]);
    }

    @Test
    void provisionalIdsStayInHighBand() {
        assertEquals(1_900_000_007, ClanService.provisionalClanId(7));
        assertTrue(ClanService.isProvisional(1_900_000_001));
        assertFalse(ClanService.isProvisional(100_000));
    }

    @Test
    void mixedPartyChanceApproximatesConfiguredShare() {
        final Random rnd = new Random(42);
        int mixed = 0;
        final int n = 10_000;
        for (int i = 0; i < n; i++) {
            if (ClanService.useMixedParty(true, 0.25, rnd)) {
                mixed++;
            }
        }
        final double share = mixed / (double) n;
        assertTrue(share > 0.22 && share < 0.28, "share=" + share);
        assertFalse(ClanService.useMixedParty(false, 0.25, rnd));
        assertFalse(ClanService.useMixedParty(true, 0, rnd));
    }

    @Test
    void filterMatesKeepsSameClanUnlessMixed() {
        final List<Director.Candidate> mates = new ArrayList<>();
        mates.add(new Director.Candidate(1, 40, 0, 0, 1.0, 100));
        mates.add(new Director.Candidate(2, 41, 0, 0, 1.0, 100));
        mates.add(new Director.Candidate(3, 39, 0, 0, 1.0, 0));
        mates.add(new Director.Candidate(4, 40, 0, 0, 1.0, 200));
        final List<Director.Candidate> same = ClanService.filterMates(mates, 100, false);
        assertEquals(2, same.size());
        assertTrue(same.stream().allMatch(c -> c.clanId() == 100));
        assertEquals(4, ClanService.filterMates(mates, 100, true).size());
        assertEquals(4, ClanService.filterMates(mates, 0, false).size());
    }

    @Test
    void shareAndBigDefaultsMatchPlan() throws Exception {
        assertEquals(40, ClanService.wantMembersForPool(100, 0.40));
        assertEquals(80, ClanService.wantMembersForPool(200, 0.40));
        assertEquals(40, ClanService.BIG_TARGET);
        assertEquals(8, ClanService.BIG_LEVEL);
        final List<ClanTemplate> t = ClanTemplates.load(Path.of("modules/bots-core/data/clans/BotClans.xml"));
        final long big = t.stream().filter(ClanTemplate::large).count();
        assertEquals(1, big, "BigBotClans=1 → one large template");
        final ClanTemplate large = t.stream().filter(ClanTemplate::large).findFirst().orElseThrow();
        assertEquals(8, large.level());
        assertTrue(large.crestSeed() > 0);
        for (ClanTemplate c : t) {
            assertTrue(c.crestSeed() > 0, c.name() + " needs crest seed");
            if (!c.large()) {
                assertTrue(c.level() >= 2 && c.level() <= 7, c.name() + " small lvl " + c.level());
            }
        }
    }

    @Test
    void inviteCooldownWindow() {
        assertTrue(ClanService.inviteCooldownAllows(0, 100, 50));
        assertFalse(ClanService.inviteCooldownAllows(60, 100, 50));
        assertTrue(ClanService.inviteCooldownAllows(40, 100, 50));
    }
}

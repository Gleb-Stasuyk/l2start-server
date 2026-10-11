package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 4g: bot pairs and trios heal, raise and buff each other and keep together (no chat). */
class PartyBrainTest {
    static final long MIN = 60_000;

    @AfterEach
    void reset() {
        BasicBrain.afkShare = 0.15;
    }

    static PartyMember mate(Pos at, double hp, boolean dead, int targetId, boolean leader) {
        return new PartyMember(900, "Mate", 0, 6, at, 0, hp, 100, dead, targetId, leader);
    }

    private static BrainHarness onTheGround(FakeWorld w, long seed, boolean mage) {
        BasicBrain.afkShare = 0;
        w.setLevel(6);
        final BrainHarness h = BasicBrainTest.harness(w, mage, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.run(2 * MIN); // on the ground by now
        return h;
    }

    private static Pos beside(FakeWorld w, int dx) {
        return new Pos(w.pos().x() + dx, w.pos().y(), 0);
    }

    @Test
    void healerHealsAHurtPartner() {
        final FakeWorld w = BasicBrainTest.world(31, true);
        w.addSkill(1011, SkillView.Kind.OTHER, 15, 3000);
        final BrainHarness h = onTheGround(w, 31, true);
        w.setParty(mate(beside(w, 200), 30, false, 0, true));
        h.run(20_000);
        assertTrue(w.partyCasts.contains("1011>900"), "no heal on the partner: " + w.partyCasts + " " + h.notes());
    }

    @Test
    void raisesADeadPartner() {
        final FakeWorld w = BasicBrainTest.world(32, true);
        w.addSkill(FakeWorld.RESURRECTION, SkillView.Kind.OTHER, 30, 10_000);
        final BrainHarness h = onTheGround(w, 32, true);
        w.setMp(w.maxMp());
        w.setParty(mate(beside(w, 300), 0, true, 0, true));
        h.run(60_000);
        assertTrue(w.partyCasts.contains(FakeWorld.RESURRECTION + ">900"), "no resurrection: " + w.partyCasts + " " + h.notes());
        assertFalse(w.party.get(0).dead(), "still dead");
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("resurrects Mate")));
    }

    @Test
    void buffsItsPartnerOnceAndRenewsLater() {
        final FakeWorld w = BasicBrainTest.world(33, true);
        w.addSkill(1068, SkillView.Kind.OTHER, 20, 2000);
        final BrainHarness h = onTheGround(w, 33, true);
        w.setMp(w.maxMp());
        w.setParty(mate(beside(w, 150), 100, false, 0, false));
        h.run(5 * MIN);
        assertEquals(1, w.partyCasts.stream().filter(c -> c.equals("1068>900")).count(), "buffs: " + w.partyCasts);
    }

    @Test
    void followerFightsTheLeadersMonster() {
        final FakeWorld w = BasicBrainTest.world(34, false);
        final BrainHarness h = onTheGround(w, 34, false);
        h.run(MIN); // whatever it fights now ends
        final FakeWorld.Mob mob = w.addMob(2, 5, beside(w, 400));
        w.partyLeader = false;
        w.setParty(mate(beside(w, 350), 90, false, mob.objectId, true));
        h.run(30_000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals(String.valueOf(mob.objectId))), "did not assist: " + h.notes());
    }

    @Test
    void followerKeepsByTheLeader() {
        final FakeWorld w = BasicBrainTest.world(35, false);
        final BrainHarness h = onTheGround(w, 35, false);
        w.partyLeader = false;
        final Pos lead = new Pos(PresenceBrainTest.MID.x() + 600, PresenceBrainTest.MID.y() + 600, 0);
        w.setParty(mate(lead, 100, false, 0, true));
        h.run(2 * MIN);
        // between its own fights it comes back to the leader
        assertTrue(h.states().stream().anyMatch(s -> s.contains("follows Mate") || s.contains("by Mate")), h.states().toString());
    }

    @Test
    void leaderWaitsForTheOthers() {
        final FakeWorld w = BasicBrainTest.world(36, false);
        final BrainHarness h = onTheGround(w, 36, false);
        w.setParty(mate(beside(w, 2500), 100, false, 0, false));
        h.run(MIN);
        assertTrue(h.states().stream().anyMatch(s -> s.contains("waits for Mate")), h.states().toString());
    }

    @Test
    void aloneNothingChanges() {
        final FakeWorld w = BasicBrainTest.world(37, true);
        w.addSkill(1068, SkillView.Kind.OTHER, 20, 2000);
        final BrainHarness h = onTheGround(w, 37, true);
        h.run(3 * MIN);
        assertTrue(w.partyCasts.isEmpty());
        assertTrue(h.states().stream().noneMatch(s -> s.contains("Mate")));
    }
}

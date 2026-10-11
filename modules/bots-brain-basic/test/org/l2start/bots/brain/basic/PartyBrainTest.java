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

    /** A prophet (buffer / healer) of the party. */
    static PartyMember buffer(Pos at, boolean leader) {
        return new PartyMember(901, "Buffer", 17, 6, at, 0, 70, 100, false, 0, leader);
    }

    private static boolean attacked(BrainHarness h, int from, FakeWorld.Mob m) {
        return h.act().calls().subList(from, h.act().calls().size()).stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals(String.valueOf(m.objectId)));
    }

    private static boolean ran(BrainHarness h) {
        return h.notes().stream().anyMatch(n -> n.contains(": runs") || n.contains("scroll of escape") || n.contains("runs from"));
    }

    @Test
    void fighterDefendsItsBufferAndDoesNotRun() {
        final FakeWorld w = BasicBrainTest.world(41, false);
        final BrainHarness h = onTheGround(w, 41, false);
        final Pos at = beside(w, 250);
        w.setParty(buffer(at, false));
        final FakeWorld.Mob mob = w.addMob(2, 6, at);
        mob.targetId = 901; // it hits the buffer
        final int from = h.act().calls().size();
        h.run(15_000);
        assertTrue(attacked(h, from, mob), "left the buffer alone: " + h.notes());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("defends the party from")), h.notes().toString());
        assertFalse(ran(h), "ran away: " + h.notes());
    }

    @Test
    void theSupportComesFirstThenItsOwnMonster() {
        final FakeWorld w = BasicBrainTest.world(42, false);
        final BrainHarness h = onTheGround(w, 42, false);
        final FakeWorld.Mob mine = w.addMob(2, 5, beside(w, 60));
        mine.targetId = FakeWorld.SELF_ID; // one on the bot itself
        final Pos at = beside(w, -300);
        w.setParty(mate(beside(w, 200), 90, false, 0, false), buffer(at, false));
        final FakeWorld.Mob onSupport = w.addMob(2, 5, at);
        onSupport.targetId = 901;
        final int from = h.act().calls().size();
        h.run(5000);
        assertTrue(attacked(h, from, onSupport), "not the support's monster first: " + h.notes());
    }

    @Test
    void tankPullsTheMonsterOffTheSupport() {
        final FakeWorld w = BasicBrainTest.world(43, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        final BrainHarness h = onTheGround(w, 43, false);
        w.setMp(w.maxMp());
        final Pos at = beside(w, 200);
        w.setParty(buffer(at, false));
        final FakeWorld.Mob mob = w.addMob(2, 6, at);
        mob.targetId = 901;
        h.run(10_000);
        assertTrue(w.taunts >= 1, "no Aggression: " + h.notes());
    }

    @Test
    void lowHpInAPartyWithAHealerStaysInTheFight() {
        final FakeWorld w = BasicBrainTest.world(44, false);
        final BrainHarness h = onTheGround(w, 44, false);
        w.setParty(buffer(beside(w, 150), false));
        final FakeWorld.Mob mob = w.addMob(2, 7, beside(w, 50));
        mob.targetId = FakeWorld.SELF_ID;
        for (int i = 0; i < 6; i++) {
            w.setHp(w.maxHp() * 0.18); // below FleeHp 25, above half of it
            h.run(2000);
        }
        assertFalse(ran(h), "ran from its party: " + h.notes());
    }

    @Test
    void aPartyLeavingFinishesTheMonstersOnItsPartnerFirst() {
        final FakeWorld w = BasicBrainTest.world(45, false);
        final BrainHarness h = onTheGround(w, 45, false);
        final Pos at = beside(w, 200);
        w.setParty(buffer(at, false));
        final FakeWorld.Mob mob = w.addMob(2, 6, at);
        mob.targetId = 901;
        final Pos exit = new Pos(w.pos().x(), w.pos().y() + 6000, 0);
        final int from = h.act().calls().size();
        h.setAssignment(PresenceBrainTest.hunt(1).leave(2, org.l2start.bots.api.Assignment.Kind.LEAVE_RUN, exit));
        h.run(10_000);
        assertTrue(attacked(h, from, mob), "ran off from the beaten partner: " + h.notes());
        assertTrue(w.pos().distance2d(exit) > 4000, "already away");
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

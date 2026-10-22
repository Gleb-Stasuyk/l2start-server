package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.api.WorldView;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** RB party, iteration 1: a bot answers a player's RB call, follows him, buffs on "баф", fights on "го", leaves. */
class RbPartyBrainTest {
    static final long MIN = 60_000;
    static final int GLEB = 5000;

    @AfterEach
    void reset() {
        BasicBrain.afkShare = 0.15;
    }

    static PartyMember gleb(Pos at, int targetId) {
        return new PartyMember(GLEB, "Gleb", 10, 40, at, 0, 100, 100, false, targetId, true, false, false);
    }

    static PlayerView glebSeen(Pos at) {
        return new PlayerView(GLEB, "Gleb", 40, 10, at, 0, 0, false, false, 0, false);
    }

    private static BrainHarness bot(FakeWorld w, long seed, int classId, boolean mage) {
        BasicBrain.afkShare = 0;
        w.setLevel(40);
        w.classId = classId;
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, new BotInfo(1, 100, "Bot", classId, false, "RU", 0.5, 0.7, 0.5, 0.5, 0.5, 0.8, 700, BasicBrainTest.TOWN, seed), w, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.run(3000);
        return h;
    }

    private static BasicBrain brain(BrainHarness h) {
        return (BasicBrain) h.brain();
    }

    private static Pos beside(FakeWorld w, int dx) {
        return new Pos(w.pos().x() + dx, w.pos().y(), 0);
    }

    private static void call(FakeWorld w, String role, boolean summoned, Pos at) {
        w.event(new BotEvent.RbCall(GLEB, "Gleb", role, summoned, at));
    }

    /** Called, comes and joins on his invitation (also past PartyBotsMax). */
    private static BrainHarness joined(FakeWorld w, long seed, int classId, boolean mage, String role) {
        final BrainHarness h = bot(w, seed, classId, mage);
        final Pos at = beside(w, 900);
        w.addPlayer(glebSeen(at));
        call(w, role, false, at);
        h.run(20_000);
        w.invite(gleb(at, 0), 1, true);
        h.run(8000);
        assertTrue(w.chat.contains("answerParty:true"), "did not join: " + w.chat + " " + h.notes());
        h.run(3000);
        assertTrue(brain(h).rbInParty(), h.notes().toString());
        return h;
    }

    private static void moveGleb(FakeWorld w, Pos at, int targetId) {
        w.party.removeIf(m -> m.objectId() == GLEB);
        w.party.add(0, gleb(at, targetId));
    }

    @Test
    void answersInAWhisperComesAndJoins() {
        final FakeWorld w = BasicBrainTest.world(501, false);
        final BrainHarness h = bot(w, 501, 5, false);
        final Pos at = beside(w, 1200);
        w.addPlayer(glebSeen(at));
        call(w, "TANK", false, at);
        h.run(20_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:") && c.contains("танк")), "no whisper: " + w.chat);
        assertTrue(w.pos().distance2d(at) < 500, "did not come: " + w.pos().distance2d(at) + " " + h.notes());
        assertEquals(GLEB, brain(h).rbCaller());
        w.invite(gleb(at, 0), 1, true); // botLimit: an ordinary bot would say no
        h.run(8000);
        assertTrue(w.chat.contains("answerParty:true"), w.chat.toString());
    }

    @Test
    void notInvitedInThreeMinutesABotBroughtInLogsOut() {
        final FakeWorld w = BasicBrainTest.world(502, true);
        final BrainHarness h = bot(w, 502, 16, true);
        final Pos at = beside(w, 600);
        call(w, "BISHOP", true, at);
        h.run(2 * MIN);
        assertEquals(0, w.logouts);
        h.run(2 * MIN);
        assertTrue(w.logouts >= 1, "still here: " + h.notes());
    }

    @Test
    void notInvitedABotOfThisPlaceGoesBackToItsLife() {
        final FakeWorld w = BasicBrainTest.world(503, false);
        final BrainHarness h = bot(w, 503, 5, false);
        call(w, "TANK", false, beside(w, 600));
        h.run(4 * MIN);
        assertEquals(0, brain(h).rbCaller(), h.notes().toString());
        assertEquals(0, w.logouts);
    }

    @Test
    void waitsForTheGoThenFightsTheBossTheTankFirst() {
        final FakeWorld w = BasicBrainTest.world(504, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        final BrainHarness h = joined(w, 504, 5, false, "TANK");
        final Pos lead = beside(w, 150);
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 500));
        boss.bossKind = WorldView.BOSS_RAID;
        moveGleb(w, lead, boss.objectId); // he only targets it
        h.run(20_000);
        assertFalse(brain(h).rbEngaged(), "fought before the go: " + h.notes());
        assertEquals(boss.maxHp, boss.hp, 0.01);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(20_000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertTrue(w.taunts >= 1, "the tank did not take the boss: " + h.notes());
        assertTrue(boss.hp < boss.maxHp, "nobody hit it: " + h.notes());
    }

    @Test
    void theLeadersHitStartsTheFight() {
        final FakeWorld w = BasicBrainTest.world(505, true);
        final BrainHarness h = joined(w, 505, 27, true, "DD");
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 450));
        boss.bossKind = WorldView.BOSS_RAID;
        moveGleb(w, beside(w, 100), boss.objectId);
        h.run(10_000);
        assertFalse(brain(h).rbEngaged());
        boss.hp = boss.maxHp * 0.97; // he hit it
        boss.targetId = GLEB;
        h.run(20_000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertTrue(boss.hp < (boss.maxHp * 0.97), "the DD did not assist: " + h.notes());
    }

    @Test
    void neverAnEpic() {
        final FakeWorld w = BasicBrainTest.world(506, false);
        final BrainHarness h = joined(w, 506, 5, false, "TANK");
        final FakeWorld.Mob epic = w.addMob(9, 80, beside(w, 400));
        epic.bossKind = WorldView.BOSS_EPIC;
        moveGleb(w, beside(w, 100), epic.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(20_000);
        assertFalse(brain(h).rbEngaged(), h.notes().toString());
        assertEquals(epic.maxHp, epic.hp, 0.01);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("party:")), "said nothing about the epic: " + w.chat);
    }

    @Test
    void theProphetBuffsTheWholePartyOnlyWhenAsked() {
        final FakeWorld w = BasicBrainTest.world(507, true);
        w.addSkill(1068, SkillView.Kind.OTHER, 20, 2000);
        w.addSkill(1040, SkillView.Kind.OTHER, 20, 2000);
        final BrainHarness h = joined(w, 507, 17, true, "PROPHET");
        w.setMp(w.maxMp());
        moveGleb(w, beside(w, 150), 0);
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        h.run(30_000);
        assertTrue(w.partyCasts.isEmpty(), "buffed unasked out of town: " + w.partyCasts);
        w.chatFrom("PARTY", 901, "Tank", "баф пж");
        h.run(40_000);
        assertTrue(w.partyCasts.contains("1040>" + GLEB) && w.partyCasts.contains("1040>901"), "not the whole party: " + w.partyCasts + " " + h.notes());
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("party:")), "no 'buffed': " + w.chat);
    }

    @Test
    void followsTheLeaderThroughHisGatekeeper() {
        final FakeWorld w = BasicBrainTest.world(508, false);
        final BrainHarness h = joined(w, 508, 5, false, "TANK");
        final Pos gk = beside(w, 300);
        final Pos far = new Pos(60_000, 60_000, 0);
        w.gkAnchors.add(gk);
        w.gkAnchors.add(far);
        moveGleb(w, new Pos(gk.x() + 100, gk.y(), 0), 0);
        h.run(10_000);
        moveGleb(w, new Pos(far.x() + 200, far.y(), 0), 0); // he teleported
        h.run(40_000);
        assertTrue(w.gkTeleports >= 1, "did not take the gatekeeper: " + h.notes());
        assertTrue(w.pos().distance2d(far) < 3000, "not where he went: " + w.pos());
    }

    @Test
    void followsTheLeadersScroll() {
        final FakeWorld w = BasicBrainTest.world(509, false);
        w.giveItem(BasicBrain.SOE, 3);
        final BrainHarness h = joined(w, 509, 5, false, "TANK");
        moveGleb(w, beside(w, 150), 0);
        h.run(5000);
        moveGleb(w, new Pos(80_000, 80_000, 0), 0); // a scroll: no gatekeeper near
        h.run(30_000);
        assertTrue(w.soeUsed >= 1, "no scroll: " + h.notes());
    }

    @Test
    void disbandedItReadsAScrollAndABotBroughtInLogsOut() {
        final FakeWorld w = BasicBrainTest.world(510, true);
        w.giveItem(BasicBrain.SOE, 3);
        final BrainHarness h = bot(w, 510, 16, true);
        w.teleport(new Pos(40_000, 40_000, 0)); // out in the field
        final Pos at = beside(w, 500);
        w.addPlayer(glebSeen(at));
        call(w, "BISHOP", true, at);
        h.run(15_000);
        w.invite(gleb(at, 0), 1, true);
        h.run(10_000);
        assertTrue(brain(h).rbInParty(), h.notes().toString());
        w.party.clear(); // he disbanded the party
        h.run(90_000);
        assertTrue(w.soeUsed >= 1, "no scroll: " + h.notes());
        assertTrue(w.logouts >= 1, "a bot brought in for the call logs out: " + h.notes());
    }

    @Test
    void tenMinutesOfNothingAndItSaysGoodbye() {
        final FakeWorld w = BasicBrainTest.world(511, false);
        final BrainHarness h = joined(w, 511, 5, false, "TANK");
        moveGleb(w, beside(w, 150), 0);
        h.run(9 * MIN);
        assertFalse(w.chat.contains("leaveParty"), "left too early");
        h.run(3 * MIN);
        assertTrue(w.chat.contains("leaveParty"), "still waits: " + h.notes());
    }
}

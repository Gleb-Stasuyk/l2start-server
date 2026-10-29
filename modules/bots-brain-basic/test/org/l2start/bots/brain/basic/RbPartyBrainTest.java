package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
        BasicBrain.playstyles = Playstyles.EMPTY;
        BasicBrain.usePlaystyles = true;
    }

    static PartyMember gleb(Pos at, int targetId) {
        return new PartyMember(GLEB, "Gleb", 10, 40, at, 0, 100, 100, false, targetId, true, false, false);
    }

    static PlayerView glebSeen(Pos at) {
        return new PlayerView(GLEB, "Gleb", 40, 10, at, 0, 0, false, false, 0, false);
    }

    private static BrainHarness bot(FakeWorld w, long seed, int classId, boolean mage) {
        return bot(w, seed, classId, mage, "Bot");
    }

    private static BrainHarness bot(FakeWorld w, long seed, int classId, boolean mage, String name) {
        BasicBrain.afkShare = 0;
        w.setLevel(40);
        w.classId = classId;
        w.botName = name;
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, new BotInfo(1, 100, name, classId, false, "RU", 0.5, 0.7, 0.5, 0.5, 0.5, 0.8, 700, BasicBrainTest.TOWN, seed), w, seed);
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
        return joined(w, seed, classId, mage, role, "Bot");
    }

    private static BrainHarness joined(FakeWorld w, long seed, int classId, boolean mage, String role, String name) {
        final BrainHarness h = bot(w, seed, classId, mage, name);
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

    // ------------------------------------------------------------------ iteration 2: the buff session, kiting, aura, heals

    private static boolean saidInParty(FakeWorld w, String... words) {
        return w.chat.stream().filter(c -> c.startsWith("party:")).anyMatch(c -> java.util.Arrays.stream(words).anyMatch(c.toLowerCase()::contains));
    }

    @Test
    void bafGivesTheFullSetEvenInTownToEveryoneTheirSummonsAndItself() {
        final FakeWorld w = BasicBrainTest.world(520, true);
        for (int id : new int[] { 1204, 1040, 1068, 1085, 1062 }) {
            w.addSkill(id, SkillView.Kind.OTHER, 20, 0);
        }
        w.buffCastMs = 1500; // a buff takes time; moving breaks it
        final BrainHarness h = joined(w, 520, 17, true, "PROPHET");
        w.setPeace(true);
        w.setMp(w.maxMp());
        moveGleb(w, beside(w, 150), 0);
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 1100), 0, 100, 100, false, 0, false)); // far out of buff range
        w.partyPets.add(new WorldView.PartyPet(950, 901, "Kat", beside(w, 1150), 0, false));
        w.chatFrom("PARTY", 901, "Tank", "баф");
        h.run(10_000);
        moveGleb(w, beside(w, -400), 0); // he walks about meanwhile
        h.run(110_000);
        final java.util.Set<Integer> none = java.util.Set.of();
        assertTrue(w.effects.getOrDefault(GLEB, none).containsAll(java.util.List.of(1204, 1040, 1085, 1062)), "leader (a mystic): " + w.effects + " " + h.notes());
        assertTrue(w.effects.getOrDefault(901, none).containsAll(java.util.List.of(1204, 1040, 1068)), "far tank: " + w.effects);
        assertFalse(w.effects.getOrDefault(901, none).contains(1062), "tank got Berserker: " + w.effects);
        assertTrue(w.effects.getOrDefault(950, none).containsAll(java.util.List.of(1204, 1040)), "his summon: " + w.effects);
        assertTrue(w.effects.getOrDefault(FakeWorld.SELF_ID, none).containsAll(java.util.List.of(1204, 1040, 1085, 1062)), "itself: " + w.effects);
        assertEquals(-1, brain(h).rbBuffsLeft(), "the session is over");
        assertTrue(saidInParty(w, "баф", "buff", "готово", "done", "all"), "no 'buffed': " + w.chat);
    }

    @Test
    void outOfManaItSaysSoSitsAndFinishesBeforeSayingBuffed() {
        final FakeWorld w = BasicBrainTest.world(521, true);
        for (int id : new int[] { 1204, 1040, 1068, 1035, 1045, 1086 }) {
            w.addSkill(id, SkillView.Kind.OTHER, 60, 0);
        }
        final BrainHarness h = joined(w, 521, 17, true, "PROPHET");
        moveGleb(w, beside(w, 150), 0);
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        w.setMp(150); // two or three buffs
        w.chatFrom("PARTY", GLEB, "Gleb", "баф пж");
        h.run(4000);
        final int before = w.chat.size();
        h.run(4 * 60_000);
        assertTrue(saidInParty(w, "мана", "мп", "mana", "mp", "oom"), "no 'mana': " + w.chat + " " + h.notes());
        final int mana = indexOfParty(w, "мана", "мп", "mana", "mp", "oom");
        assertTrue(w.effects.getOrDefault(GLEB, java.util.Set.of()).containsAll(java.util.List.of(1204, 1040)), "not all on him: " + w.effects + " " + h.notes());
        assertFalse(w.effects.getOrDefault(GLEB, java.util.Set.of()).contains(1035), "mage got Mental Shield: " + w.effects);
        assertFalse(w.effects.getOrDefault(GLEB, java.util.Set.of()).contains(1045), "mage got Blessed Body: " + w.effects);
        assertTrue(w.effects.getOrDefault(901, java.util.Set.of()).containsAll(java.util.List.of(1204, 1040, 1045, 1068)), "not all on the tank: " + w.effects);
        assertFalse(w.effects.getOrDefault(901, java.util.Set.of()).contains(1086), "tank got Haste: " + w.effects);
        assertTrue(w.effects.getOrDefault(FakeWorld.SELF_ID, java.util.Set.of()).containsAll(java.util.List.of(1204, 1040)), "not all on itself: " + w.effects);
        assertFalse(w.effects.getOrDefault(FakeWorld.SELF_ID, java.util.Set.of()).contains(1035), "prophet mage got Mental Shield");
        assertTrue(indexOfParty(w, "баф", "готово", "buffed", "done", "buffs up", "there you go") > mana, "'buffed' before the end: " + w.chat.subList(before, w.chat.size()));
    }

    @Test
    void windWalkIsNotRecastWhileMoreThanTenMinutesRemain() {
        final FakeWorld w = BasicBrainTest.world(532, true);
        w.addSkill(PartyBuffs.WIND_WALK, SkillView.Kind.OTHER, 20, 0);
        w.addSkill(PartyBuffs.SHIELD, SkillView.Kind.OTHER, 20, 0);
        final BrainHarness h = joined(w, 532, 17, true, "PROPHET");
        w.setMp(w.maxMp());
        moveGleb(w, beside(w, 150), 0);
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        w.putEffect(GLEB, PartyBuffs.WIND_WALK, 15 * 60);
        w.putEffect(901, PartyBuffs.WIND_WALK, 15 * 60);
        w.putEffect(FakeWorld.SELF_ID, PartyBuffs.WIND_WALK, 15 * 60);
        w.chatFrom("PARTY", GLEB, "Gleb", "баф");
        h.run(40_000);
        assertTrue(w.partyCasts.stream().noneMatch(c -> c.startsWith(PartyBuffs.WIND_WALK + ">")), "recast Wind Walk: " + w.partyCasts + " " + h.notes());
        assertTrue(w.partyCasts.contains(PartyBuffs.SHIELD + ">" + GLEB) && w.partyCasts.contains(PartyBuffs.SHIELD + ">901"), "skipped the rest: " + w.partyCasts);
        assertEquals(-1, brain(h).rbBuffsLeft(), h.notes().toString());
    }

    @Test
    void afterManaRecoversEveryoneIsBuffed() {
        final FakeWorld w = BasicBrainTest.world(533, true);
        for (int id : new int[] { PartyBuffs.WIND_WALK, PartyBuffs.SHIELD, PartyBuffs.MIGHT, PartyBuffs.ACUMEN, PartyBuffs.BLESSED_BODY }) {
            w.addSkill(id, SkillView.Kind.OTHER, 80, 0);
        }
        final BrainHarness h = joined(w, 533, 17, true, "PROPHET");
        moveGleb(w, beside(w, 120), 0);
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 180), 0, 100, 100, false, 0, false));
        w.party.add(new PartyMember(902, "Glad", 2, 40, beside(w, 220), 0, 100, 100, false, 0, false));
        w.setMp(100); // one buff, then sit
        w.chatFrom("PARTY", GLEB, "Gleb", "баф");
        h.run(5 * 60_000);
        assertTrue(saidInParty(w, "мана", "мп", "mana", "mp", "oom"), "no 'mana': " + w.chat);
        final java.util.Set<Integer> none = java.util.Set.of();
        assertTrue(w.effects.getOrDefault(GLEB, none).containsAll(java.util.List.of(PartyBuffs.WIND_WALK, PartyBuffs.SHIELD, PartyBuffs.ACUMEN)), "leader skipped: " + w.effects + " " + h.notes());
        assertTrue(w.effects.getOrDefault(901, none).containsAll(java.util.List.of(PartyBuffs.WIND_WALK, PartyBuffs.SHIELD, PartyBuffs.BLESSED_BODY, PartyBuffs.MIGHT)), "tank skipped: " + w.effects);
        assertFalse(w.effects.getOrDefault(901, none).contains(PartyBuffs.BERSERKER), "tank got Berserker");
        assertTrue(w.effects.getOrDefault(902, none).containsAll(java.util.List.of(PartyBuffs.WIND_WALK, PartyBuffs.SHIELD, PartyBuffs.MIGHT)), "dd skipped: " + w.effects);
        assertTrue(w.effects.getOrDefault(FakeWorld.SELF_ID, none).containsAll(java.util.List.of(PartyBuffs.WIND_WALK, PartyBuffs.SHIELD, PartyBuffs.ACUMEN)), "prophet skipped: " + w.effects);
        assertEquals(-1, brain(h).rbBuffsLeft(), h.notes().toString());
        assertTrue(saidInParty(w, "баф", "buff", "готово", "done", "all"), "no 'buffed': " + w.chat);
    }

    private static int indexOfParty(FakeWorld w, String... words) {
        for (int i = 0; i < w.chat.size(); i++) {
            final String c = w.chat.get(i).toLowerCase();
            if (c.startsWith("party:") && java.util.Arrays.stream(words).anyMatch(c::contains)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void theTankRunsTheBossRoundTheParty() {
        final FakeWorld w = BasicBrainTest.world(522, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        final BrainHarness h = joined(w, 522, 5, false, "TANK");
        w.teleport(new Pos(40_000, 40_000, 0));
        moveGleb(w, beside(w, 150), 0);
        final FakeWorld.Mob boss = w.addMob(9, 36, beside(w, 400));
        boss.bossKind = WorldView.BOSS_RAID;
        moveGleb(w, beside(w, 150), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(20_000);
        assertTrue(w.taunts >= 1, h.notes().toString());
        assertTrue(brain(h).rbKites() >= 5, "no kiting: " + brain(h).rbKites() + " " + h.notes());
        assertTrue(boss.dead() || (boss.targetId == FakeWorld.SELF_ID), "lost the boss: " + boss.targetId + " deaths " + w.deaths + " " + h.notes());
    }

    @Test
    void helpersOfTheBossArePulledWithAuraOfHate() {
        final FakeWorld w = BasicBrainTest.world(523, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        w.addSkill(FakeWorld.HATE_AURA, SkillView.Kind.OTHER, 15, 6000);
        final BrainHarness h = joined(w, 523, 5, false, "TANK");
        w.teleport(new Pos(40_000, 40_000, 0));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 300));
        boss.bossKind = WorldView.BOSS_RAID;
        final FakeWorld.Mob a = w.addMob(10, 40, beside(w, 380));
        final FakeWorld.Mob b = w.addMob(10, 40, beside(w, 420));
        a.bossKind = WorldView.BOSS_MINION;
        b.bossKind = WorldView.BOSS_MINION;
        a.targetId = GLEB;
        b.targetId = GLEB;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(20_000);
        assertTrue(w.auraPulls >= 1, "no Aura of Hate: " + h.notes());
        assertTrue(brain(h).rbAuras() >= 1);
        assertTrue(w.casts.stream().anyMatch(c -> c.startsWith(FakeWorld.HATE_AURA + "->")), w.casts.toString());
    }

    @Test
    void theTankTakesTheBossBackOffADd() {
        final FakeWorld w = BasicBrainTest.world(524, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        final BrainHarness h = joined(w, 524, 5, false, "TANK");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(902, "Sps", 27, 40, beside(w, 250), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 300));
        boss.bossKind = WorldView.BOSS_RAID;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(10_000);
        final int t = w.taunts;
        boss.targetId = 902; // it turned to the DD
        h.run(8000);
        assertTrue(w.taunts > t, "no re-taunt: " + h.notes());
        assertEquals(FakeWorld.SELF_ID, boss.targetId);
    }

    @Test
    void aDdWithTheBossOnItRunsRoundThePartyThenFights() {
        final FakeWorld w = BasicBrainTest.world(525, true);
        final BrainHarness h = joined(w, 525, 27, true, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(6000);
        boss.hp = Math.max(boss.hp, 4000); // stay alive while we watch the burst
        boss.targetId = FakeWorld.SELF_ID; // the boss turned to it
        final int k = brain(h).rbKites();
        h.run(3500);
        final int after = brain(h).rbKites();
        assertTrue(after >= (k + 1), "did not run: " + h.notes());
        assertTrue(after <= (k + BasicBrain.RB_DD_KITE_STEPS), "kept kiting instead of fighting: " + after + " " + h.notes());
        final double hp = boss.hp;
        h.act().clear();
        h.run(6000);
        assertTrue(brain(h).rbKites() <= after, "kited after the burst: " + brain(h).rbKites() + " " + h.notes());
        assertTrue(boss.hp < hp, "did not return to the fight: " + h.notes() + " " + h.act().calls());
        assertTrue((h.act().count("attack") + h.act().count("cast")) >= 3, "no hits after kite: " + h.act().calls());
    }

    @Test
    void aDdKeepsHittingTheBossAndDoesNotStandIdle() {
        final FakeWorld w = BasicBrainTest.world(528, true);
        final BrainHarness h = joined(w, 528, 27, true, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(4000);
        boss.targetId = FakeWorld.SELF_ID;
        h.run(BasicBrain.RB_DD_KITE_BURST_MS + 500); // kite burst, then it must keep hitting
        w.setHp(w.maxHp());
        boss.targetId = 901; // the tank took it: still a raid fight, no standing around
        assertFalse(w.dead(), "died during the burst: " + h.notes());
        for (int i = 0; i < 8; i++) {
            w.setHp(w.maxHp());
            final double hp = boss.hp;
            h.act().clear();
            h.run(1500);
            assertFalse(w.dead(), "died in slice " + i + ": " + h.notes());
            final long hits = h.act().count("attack") + h.act().count("cast");
            assertTrue((hits >= 1) || (boss.hp < hp), "idle slice " + i + " hits " + hits + " hp " + hp + "→" + boss.hp + " " + h.notes() + " " + h.act().calls());
        }
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("fight ") || n.contains("assists")), "never fought: " + h.notes());
    }

    @Test
    void aDdStaysOnTheBossWhenTheLeaderClicksAMinion() {
        final FakeWorld w = BasicBrainTest.world(529, true);
        final BrainHarness h = joined(w, 529, 27, true, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 80, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 5000;
        final FakeWorld.Mob add = w.addMob(10, 40, beside(w, 520));
        add.bossKind = WorldView.BOSS_MINION;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(5000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        final double bossHp = boss.hp;
        final double addHp = add.hp;
        moveGleb(w, beside(w, 100), add.objectId); // he clicked the helper
        h.run(8000);
        assertTrue(boss.hp < bossHp, "left the boss when the leader clicked a minion: " + h.notes());
        assertEquals(addHp, add.hp, 0.01, "switched to the minion: " + add.hp + " " + h.notes());
    }

    @Test
    void theTankHoldsWithTauntAndKiteNotOnlyMelee() {
        final FakeWorld w = BasicBrainTest.world(530, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        w.addSkill(FakeWorld.HATE_AURA, SkillView.Kind.OTHER, 15, 6000);
        final BrainHarness h = joined(w, 530, 5, false, "TANK");
        w.teleport(new Pos(40_000, 40_000, 0));
        moveGleb(w, beside(w, 150), 0);
        final FakeWorld.Mob boss = w.addMob(9, 80, beside(w, 400));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        moveGleb(w, beside(w, 150), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(8000);
        assertTrue(w.taunts >= 1, h.notes().toString());
        assertEquals(FakeWorld.SELF_ID, boss.targetId);
        final int taunts = w.taunts;
        final int kites = brain(h).rbKites();
        final int turns = brain(h).rbTurns();
        final long attacks = h.act().count("attack");
        h.run(16_000);
        assertTrue(w.taunts > taunts, "no taunt while holding: " + w.taunts + " notes " + h.notes());
        assertTrue(brain(h).rbKites() > kites, "no kite while holding: " + brain(h).rbKites() + " " + h.notes());
        assertTrue(brain(h).rbTurns() > turns, "no hold-taunt: " + brain(h).rbTurns() + " " + h.notes());
        final long moreAttacks = h.act().count("attack") - attacks;
        assertTrue((brain(h).rbKites() + brain(h).rbTurns()) >= 4, "only melee: kites " + brain(h).rbKites() + " taunts " + brain(h).rbTurns() + " attacks " + moreAttacks + " " + h.notes());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("kites ") || n.contains("taunts ") || n.contains("hates ")), "notes without kite/taunt: " + h.notes());
    }

    @Test
    void theTankKitesAroundAWall() {
        final FakeWorld w = BasicBrainTest.world(531, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        final BrainHarness h = joined(w, 531, 5, false, "TANK");
        w.teleport(new Pos(40_000, 40_000, 0));
        // party centre ~40175,0 relative: the default ±40° steps land west of the tank
        w.addWall(39_950, 39_790, 40_020, 39_890);
        w.addWall(39_950, 40_110, 40_020, 40_210);
        moveGleb(w, beside(w, 150), 0);
        w.party.add(new PartyMember(901, "Sps", 27, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 400));
        boss.bossKind = WorldView.BOSS_RAID;
        moveGleb(w, beside(w, 150), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(18_000);
        assertTrue(brain(h).rbKites() >= 1, "gave up to melee at a wall: kites " + brain(h).rbKites() + " " + h.notes());
    }

    @Test
    void theBishopHealsTheOneTheBossIsOnFirst() {
        final FakeWorld w = BasicBrainTest.world(526, true);
        w.addSkill(1011, SkillView.Kind.OTHER, 20, 0);
        final BrainHarness h = joined(w, 526, 16, true, "BISHOP");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(902, "Sps", 27, 40, beside(w, 250), 0, 75, 100, false, 0, false));
        w.party.add(new PartyMember(903, "Necro", 13, 40, beside(w, 260), 0, 50, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 700));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.targetId = 902;
        moveGleb(w, beside(w, 150), boss.objectId);
        w.partyCasts.clear();
        h.run(6000);
        assertFalse(w.partyCasts.isEmpty(), "no heal: " + h.notes());
        assertEquals("1011>902", w.partyCasts.get(0), w.partyCasts.toString());
    }

    @Test
    void ddsOfAnRbPartyNeverHeal() {
        final FakeWorld w = BasicBrainTest.world(527, true);
        w.addSkill(1011, SkillView.Kind.OTHER, 20, 0);
        final BrainHarness h = joined(w, 527, 27, true, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(903, "Necro", 13, 40, beside(w, 200), 0, 20, 100, false, 0, false));
        moveGleb(w, beside(w, 150), 0);
        w.setHp(w.self().maxHp() * 0.3);
        h.run(30_000);
        assertTrue(w.casts.stream().noneMatch(c -> c.startsWith("1011->")), "healed: " + w.casts);
        assertTrue(w.partyCasts.stream().noneMatch(c -> c.startsWith("1011>")), "healed a member: " + w.partyCasts);
    }

    // ------------------------------------------------------------------ mage DD named Sniper (Spellsinger / Spellhowler / Necro — not a bow class)

    static final int HYDRO_BLAST = 1235;
    static final int DEATH_SPIKE = 1148;
    static final int VAMPIRIC_CLAW = 1234;
    static final int CURSED_BONE = 2508;

    private static BrainHarness sniperDd(FakeWorld w, long seed, int classId) throws Exception {
        BasicBrain.playstyles = Playstyles.load(PlaystyleTest.DATA);
        w.addSkill(HYDRO_BLAST, SkillView.Kind.NUKE, 20, 2000);
        return joined(w, seed, classId, true, "DD", "Sniper");
    }

    @Test
    void sniperMageDdCastsTheBossThroughPlaystyle() throws Exception {
        final FakeWorld w = BasicBrainTest.world(540, true);
        final BrainHarness h = sniperDd(w, 540, 27); // Spellsinger, character name Sniper
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(4000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertEquals("Sniper", w.self().name());
        h.act().clear();
        for (int i = 0; i < 6; i++) {
            w.setHp(w.maxHp());
            w.setMp(Math.max(w.mp(), w.maxMp() * 0.5));
            final double hp = boss.hp;
            h.run(2500);
            final long casts = h.act().countOk("cast");
            assertTrue((casts >= 1) || (boss.hp < hp), "Sniper idle slice " + i + " casts " + casts + " hp " + hp + "→" + boss.hp + " " + h.notes() + " " + h.act().calls());
        }
        assertTrue((h.act().countOk("cast") + h.act().countOk("attack")) >= 4, "no casts/hits: " + h.act().calls());
        assertTrue(boss.hp < 8000, "nobody hit it: " + boss.hp + " " + h.notes());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("fight ") || n.contains("assists") || n.contains("closes")), "never fought: " + h.notes());
        assertTrue(brain(h).details().contains("fight") || brain(h).details().contains("assists") || brain(h).details().contains("closes"),
            "detail is not combat: " + brain(h).details());
    }

    @Test
    void sniperMageDdClosesIntoNukeRangeAndKeepsCasting() throws Exception {
        final FakeWorld w = BasicBrainTest.world(541, true);
        w.castMaxRange = FakeWorld.NUKE_RANGE;
        w.attackMaxRange = FakeWorld.MELEE_RANGE;
        final BrainHarness h = sniperDd(w, 541, 40); // Spellhowler named Sniper
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 80, beside(w, 800));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        boss.targetId = 901;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(12_000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertTrue(w.pos().distance2d(boss.pos) <= (FakeWorld.NUKE_RANGE + 40),
            "did not walk into nuke range: dist " + w.pos().distance2d(boss.pos) + " " + h.notes() + " " + h.act().calls());
        assertTrue(h.act().countOk("cast") >= 3, "no casts after closing in: " + h.act().countOk("cast") + " " + h.notes() + " " + h.act().calls());
        assertTrue(boss.hp < 8000, "nobody hit it: hp " + boss.hp + " " + h.notes());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("fight ") || n.contains("assists") || n.contains("closes")), "never fought: " + h.notes());
        assertTrue(brain(h).details().contains("fight") || brain(h).details().contains("closes") || brain(h).details().contains("assists"),
            "detail is not combat: " + brain(h).details());
    }

    @Test
    void sniperMageDdUsesTheNextNukeWhenWindStrikeIsOnCooldown() throws Exception {
        BasicBrain.playstyles = Playstyles.EMPTY; // nuke() fallback, not the class rotation
        final FakeWorld w = BasicBrainTest.world(542, true);
        w.addSkill(HYDRO_BLAST, SkillView.Kind.NUKE, 12, 500);
        final BrainHarness h = joined(w, 542, 27, true, "DD", "Sniper");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        boss.targetId = 901;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(3000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        h.act().clear();
        w.casts.clear();
        h.run(8000);
        assertTrue(w.casts.stream().anyMatch(c -> c.startsWith(HYDRO_BLAST + "->")),
            "waited on Wind Strike reuse, never Hydro Blast: " + w.casts + " " + h.notes() + " " + h.act().calls());
        assertTrue(boss.hp < 8000, "nobody hit it: " + h.notes());
    }

    @Test
    void sniperNecroDdNukesWhenDeathSpikeHasNoBones() throws Exception {
        BasicBrain.playstyles = Playstyles.load(PlaystyleTest.DATA);
        final FakeWorld w = BasicBrainTest.world(543, true);
        w.addSkill(new FakeWorld.Extra(DEATH_SPIKE, SkillView.Kind.OTHER, 30, 0, CURSED_BONE, 1, 0));
        w.addSkill(VAMPIRIC_CLAW, SkillView.Kind.NUKE, 20, 1500);
        final BrainHarness h = joined(w, 543, 13, true, "DD", "Sniper"); // Necromancer
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        boss.targetId = 901;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(10_000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertTrue(w.casts.stream().noneMatch(c -> c.startsWith(DEATH_SPIKE + "->")), "Death Spike without bones: " + w.casts);
        assertTrue(w.casts.stream().anyMatch(c -> c.startsWith(VAMPIRIC_CLAW + "->") || c.startsWith(FakeWorld.NUKE_ID + "->")),
            "rotation stuck on boneless Death Spike: " + w.casts + " " + h.notes());
        assertTrue(boss.hp < 8000, "nobody hit it: " + h.notes());
    }

    @Test
    void sniperMageDdStaysOnTheBossWhenTheLeaderClicksAMinion() throws Exception {
        final FakeWorld w = BasicBrainTest.world(544, true);
        final BrainHarness h = sniperDd(w, 544, 27);
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 80, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 5000;
        boss.targetId = 901;
        final FakeWorld.Mob add = w.addMob(10, 40, beside(w, 520));
        add.bossKind = WorldView.BOSS_MINION;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(5000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        final double bossHp = boss.hp;
        final double addHp = add.hp;
        moveGleb(w, beside(w, 100), add.objectId);
        h.run(8000);
        assertTrue(boss.hp < bossHp, "left the boss when the leader clicked a minion: " + h.notes());
        assertEquals(addHp, add.hp, 0.01, "switched to the minion: " + add.hp + " " + h.notes());
    }

    @Test
    void sniperMageDdStillHitsWhenMpIsEmpty() throws Exception {
        final FakeWorld w = BasicBrainTest.world(545, true);
        final BrainHarness h = sniperDd(w, 545, 27);
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 200));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        boss.targetId = 901;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(3000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        w.setMp(0);
        final double hp = boss.hp;
        h.act().clear();
        h.run(6000);
        assertTrue((h.act().countOk("attack") + h.act().countOk("cast") >= 1) || (boss.hp < hp),
            "empty MP, stood idle: " + h.notes() + " " + h.act().calls());
        assertFalse(w.sitting(), "sat down in the raid: " + h.notes());
    }

    @Test
    void bishopSkipsMajorHealWithoutSpiritOreAndHealsWithTheNextLine() {
        final FakeWorld w = BasicBrainTest.world(546, true);
        w.addSkill(new FakeWorld.Extra(1401, SkillView.Kind.OTHER, 20, 0, 3031, 1, 0)); // Major Heal: Spirit Ore
        w.addSkill(1217, SkillView.Kind.OTHER, 20, 0); // Greater Heal: no reagent
        final BrainHarness h = joined(w, 546, 16, true, "BISHOP");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(902, "Tank", 5, 40, beside(w, 250), 0, 40, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 700));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.targetId = 902;
        moveGleb(w, beside(w, 150), boss.objectId);
        w.partyCasts.clear();
        h.run(8000);
        assertTrue(w.partyCasts.stream().noneMatch(c -> c.startsWith("1401>")), "Major Heal without ore: " + w.partyCasts);
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.startsWith("1217>902")),
            "stuck on boneless Major Heal, never Greater Heal: " + w.partyCasts + " " + h.notes());
    }

    // ------------------------------------------------------------------ bow DD (Hawkeye / Silver Ranger / Phantom Ranger): shoot the RB

    /**
     * paceMs=0 + ALWAYS: without a cap, playstyle() returns every tick and attack() never runs.
     * Accuracy as a TOGGLE that never stays on: stance() would also steal ticks.
     */
    private static Playstyles archerStarveStyle() {
        final Playstyles.Entry shot = new Playstyles.Entry(19, "Double Shot", Playstyles.Use.ROTATION, Set.of(Playstyles.Cond.ALWAYS), 0, 0, 0, 0, 0, 0, 999, 0, 0);
        return new Playstyles(List.of(new Playstyles.Playstyle("Archer starve", Set.of(9, 24, 37), "ARCHER", List.of(shot))));
    }

    private static BrainHarness archerDd(FakeWorld w, long seed, int classId) {
        w.setArcher(true);
        w.addSkill(19, SkillView.Kind.STRIKE, 20, 0);
        w.addSkill(256, SkillView.Kind.TOGGLE, 5, 0); // Accuracy: Extra.buffLeft stays 0, so stance retries
        return joined(w, seed, classId, false, "DD");
    }

    @Test
    void aBowDdKeepsShootingTheBossThroughPlaystyleAndStance() {
        BasicBrain.playstyles = archerStarveStyle();
        final FakeWorld w = BasicBrainTest.world(550, false);
        final BrainHarness h = archerDd(w, 550, 9); // Hawkeye, rbRole=DD
        w.teleport(new Pos(40_000, 40_000, 0));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(3000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        h.act().clear();
        for (int i = 0; i < 6; i++) {
            w.setHp(w.maxHp());
            final double hp = boss.hp;
            final long before = h.act().countOk("attack");
            h.run(2500);
            final long shots = h.act().countOk("attack") - before;
            assertTrue((shots >= 1) || (boss.hp < hp), "bow idle slice " + i + " shots " + shots + " hp " + hp + "→" + boss.hp + " " + h.notes() + " " + h.act().calls());
        }
        assertTrue(boss.hp < 8000, "nobody hit it: " + boss.hp + " " + h.notes());
        assertTrue((h.act().countOk("attack") + h.act().countOk("cast")) >= 1, "no attack/cast: " + h.act().calls());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("fight ") || n.contains("assists")), "never fought: " + h.notes());
        assertTrue(brain(h).details().contains("fight") || brain(h).details().contains("assists") || brain(h).details().contains("closes"),
            "detail is not combat: " + brain(h).details());
        assertFalse(brain(h).details().contains("follows") && !brain(h).details().contains("fight"), "only follows: " + brain(h).details());
    }

    @Test
    void aBowDdWalksIntoBowRangeWhenAttackIsUnreachable() {
        BasicBrain.playstyles = archerStarveStyle();
        final FakeWorld w = BasicBrainTest.world(551, false);
        w.attackMaxRange = FakeWorld.BOW_RANGE;
        final BrainHarness h = archerDd(w, 551, 24); // Silver Ranger
        w.teleport(new Pos(40_000, 40_000, 0));
        final FakeWorld.Mob boss = w.addMob(9, 80, beside(w, 800));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(12_000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertTrue(w.pos().distance2d(boss.pos) <= (FakeWorld.BOW_RANGE + 20),
            "did not walk into bow range: dist " + w.pos().distance2d(boss.pos) + " " + h.notes() + " " + h.act().calls());
        assertTrue(h.act().countOk("attack") >= 1, "no shots after closing in: " + h.act().countOk("attack") + " " + h.notes() + " " + h.act().calls());
        assertTrue(boss.hp < 8000, "nobody hit it: hp " + boss.hp + " " + h.notes());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("fight ") || n.contains("assists")), "never fought: " + h.notes());
    }

    @Test
    void aBowDdStaysOnTheRaidBossLikeOtherDd() {
        BasicBrain.playstyles = archerStarveStyle();
        final FakeWorld w = BasicBrainTest.world(552, false);
        final BrainHarness h = archerDd(w, 552, 37); // Phantom Ranger
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 80, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 5000;
        boss.targetId = 901;
        final FakeWorld.Mob add = w.addMob(10, 40, beside(w, 520));
        add.bossKind = WorldView.BOSS_MINION;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(5000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        final double bossHp = boss.hp;
        final double addHp = add.hp;
        moveGleb(w, beside(w, 100), add.objectId);
        boss.targetId = 901;
        h.run(8000);
        assertTrue(boss.hp < bossHp, "left the boss when the leader clicked a minion: " + h.notes());
        assertEquals(addHp, add.hp, 0.01, "switched to the minion: " + add.hp + " " + h.notes());
        assertTrue(h.act().countOk("attack") >= 1, "no attack(boss): " + h.act().countOk("attack") + " " + h.act().calls());
    }

    // ------------------------------------------------------------------ continuous standing combat, auto-engage, notes

    /**
     * paceMs=0 ALWAYS + a STANCE whose buffLeft never becomes -1: without a cap, playstyle/stance return every tick
     * and attack() never runs — the live "two hits, stand still, two hits" cycle.
     */
    private static Playstyles meleeStarveStyle() {
        final Playstyles.Entry stance = new Playstyles.Entry(312, "Vicious Stance", Playstyles.Use.STANCE, Set.of(Playstyles.Cond.ALWAYS), 0, 0, 0, 0, 0, 0, 999, 0, 0);
        final Playstyles.Entry blow = new Playstyles.Entry(16, "Mortal Blow", Playstyles.Use.ROTATION, Set.of(Playstyles.Cond.ALWAYS), 0, 0, 0, 0, 0, 0, 999, 0, 0);
        return new Playstyles(List.of(new Playstyles.Playstyle("Melee starve", Set.of(8, 23, 36), "FIGHTER", List.of(stance, blow))));
    }

    private static BrainHarness meleeDd(FakeWorld w, long seed, int classId) {
        w.addSkill(16, SkillView.Kind.STRIKE, 10, 0);
        w.addSkill(new FakeWorld.Extra(312, SkillView.Kind.TOGGLE, 5, 0, 0, 0, 0, 0));
        return joined(w, seed, classId, false, "DD");
    }

    @Test
    void aMeleeDdKeepsHittingInPlaceThroughPlaystyleAndStance() {
        BasicBrain.playstyles = meleeStarveStyle();
        final FakeWorld w = BasicBrainTest.world(560, false);
        final BrainHarness h = meleeDd(w, 560, 8); // Treasure Hunter
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 80), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 60));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 12_000;
        boss.targetId = 901;
        moveGleb(w, beside(w, 40), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(4000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertTrue(w.pos().distance2d(boss.pos) <= (FakeWorld.MELEE_RANGE + 20), "not in melee: " + w.pos().distance2d(boss.pos));
        final int fightId = brain(h).rbFightId();
        assertTrue(fightId != 0, "no fightId: " + h.notes());
        final long fightNotes = h.notes().stream().filter(n -> n.contains("fight ")).count();
        h.act().clear();
        for (int i = 0; i < 8; i++) {
            w.setHp(w.maxHp());
            final double hp = boss.hp;
            h.act().clear();
            h.run(1500);
            final long hits = h.act().count("attack") + h.act().count("cast");
            assertTrue((hits >= 1) || (boss.hp < hp),
                "idle standing slice " + i + " hits " + hits + " hp " + hp + "→" + boss.hp + " " + h.notes() + " " + h.act().calls());
            assertTrue(h.act().count("moveTo") <= 1, "walked while in melee slice " + i + ": " + h.act().calls());
        }
        assertEquals(fightId, brain(h).rbFightId(), "fightId reset stall: " + h.notes());
        assertTrue(h.notes().stream().filter(n -> n.contains("fight ")).count() <= (fightNotes + 1),
            "startFight loop: " + h.notes());
        assertTrue(boss.hp < 12_000, "nobody hit it: " + boss.hp + " " + h.notes());
    }

    @Test
    void aDdDoesNotRestartKiteEveryTimeTheBossLooksAtIt() {
        final FakeWorld w = BasicBrainTest.world(561, true);
        final BrainHarness h = joined(w, 561, 27, true, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 350));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        moveGleb(w, beside(w, 100), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(4000);
        boss.targetId = FakeWorld.SELF_ID;
        h.run(BasicBrain.RB_DD_KITE_BURST_MS + 400);
        final int afterBurst = brain(h).rbKites();
        assertTrue(afterBurst >= 1, "no first burst: " + h.notes());
        boss.targetId = 901;
        h.run(1500);
        boss.targetId = FakeWorld.SELF_ID;
        h.run(3500);
        assertTrue(brain(h).rbKites() <= afterBurst,
            "restarted kite when the boss looked back: " + afterBurst + " → " + brain(h).rbKites() + " " + h.notes());
        w.setHp(w.maxHp());
        final double hp = boss.hp;
        h.act().clear();
        h.run(1500);
        assertTrue((h.act().count("attack") + h.act().count("cast") >= 1) || (boss.hp < hp),
            "idle after the look-back: " + h.notes() + " " + h.act().calls());
    }

    @Test
    void ddJoinsWhenTheTankIsAlreadyOnTheBossWithoutGo() {
        final FakeWorld w = BasicBrainTest.world(562, false);
        final BrainHarness h = joined(w, 562, 8, false, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Kabanchik", 5, 42, beside(w, 120), 0, 80, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 38, beside(w, 80));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        boss.targetId = 901;
        moveGleb(w, beside(w, 60), 0); // leader is not targeting it, nobody said "го"
        h.run(5000);
        assertTrue(brain(h).rbEngaged(), "did not auto-engage: " + h.notes());
        assertTrue(boss.hp < 8000, "DD did not join: hp " + boss.hp + " " + h.notes() + " " + h.act().calls());
        assertTrue(h.notes().stream().noneMatch(n -> n.contains("says go")), "waited for го: " + h.notes());
    }

    @Test
    void bishopJoinsWhenTheTankIsAlreadyOnTheBossWithoutGo() {
        final FakeWorld w = BasicBrainTest.world(563, true);
        w.addSkill(1011, SkillView.Kind.OTHER, 20, 0);
        final BrainHarness h = joined(w, 563, 16, true, "BISHOP");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Kabanchik", 5, 42, beside(w, 200), 0, 40, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 38, beside(w, 700));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.targetId = 901;
        moveGleb(w, beside(w, 80), 0);
        w.partyCasts.clear();
        h.run(6000);
        assertTrue(brain(h).rbEngaged(), "did not auto-engage: " + h.notes());
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.startsWith("1011>901")), "bishop did not heal the tank: " + w.partyCasts + " " + h.notes());
    }

    @Test
    void goFallsBackToTheLeadersTargetWhenItIsNotTaggedRaid() {
        final FakeWorld w = BasicBrainTest.world(564, false);
        final BrainHarness h = joined(w, 564, 8, false, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        final FakeWorld.Mob mob = w.addMob(9, 38, beside(w, 80));
        mob.hp = 5000;
        moveGleb(w, beside(w, 40), mob.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(8000);
        assertTrue(brain(h).rbEngaged(), "go dropped with no BOSS_RAID: " + h.notes());
        assertTrue(mob.hp < 5000, "did not hit the leader's target: " + h.notes() + " " + h.act().calls());
    }

    @Test
    void goFallsBackToAPartyAttackerWhenTheLeaderHasNoTarget() {
        final FakeWorld w = BasicBrainTest.world(565, false);
        final BrainHarness h = joined(w, 565, 8, false, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Kabanchik", 5, 42, beside(w, 100), 0, 70, 100, false, 0, false));
        final FakeWorld.Mob mob = w.addMob(9, 38, beside(w, 80));
        mob.hp = 5000;
        mob.targetId = 901;
        moveGleb(w, beside(w, 40), 0);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(8000);
        assertTrue(brain(h).rbEngaged(), "go dropped with no target and no BOSS_RAID: " + h.notes());
        assertTrue(mob.hp < 5000, "did not hit the party attacker: " + h.notes() + " " + h.act().calls());
    }

    @Test
    void rbPartyNotesGoToTheGameLog() {
        final List<String> logged = new ArrayList<>();
        final Handler hdl = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final Logger log = Logger.getLogger("module.bots-brain-basic");
        log.addHandler(hdl);
        try {
            final FakeWorld w = BasicBrainTest.world(566, false);
            final BrainHarness h = joined(w, 566, 5, false, "TANK");
            final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 400));
            boss.bossKind = WorldView.BOSS_RAID;
            moveGleb(w, beside(w, 100), boss.objectId);
            w.chatFrom("PARTY", GLEB, "Gleb", "го");
            h.run(4000);
            assertTrue(brain(h).rbEngaged(), h.notes().toString());
            assertTrue(logged.stream().anyMatch(s -> s.contains("Module[bots-brain-basic]") && s.contains("rb party [Bot/TANK]")),
                "notes did not reach the game log with [Name/Role]: " + logged + " harness " + h.notes());
        } finally {
            log.removeHandler(hdl);
        }
    }

    @Test
    void goOnAMinionLocksTheDdOnTheNearbyRaidBoss() {
        final FakeWorld w = BasicBrainTest.world(570, false);
        final BrainHarness h = joined(w, 570, 8, false, "DD");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Tank", 5, 40, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob raid = w.addMob(9, 80, beside(w, 400));
        raid.bossKind = WorldView.BOSS_RAID;
        raid.hp = 8000;
        final FakeWorld.Mob minion = w.addMob(10, 38, beside(w, 220));
        minion.bossKind = WorldView.BOSS_MINION;
        minion.hp = 5000;
        minion.targetId = GLEB; // the follower is on the party
        moveGleb(w, beside(w, 80), minion.objectId); // "го" while the leader has the minion targeted
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(8000);
        assertTrue(brain(h).rbEngaged(), "did not engage: " + h.notes());
        assertEquals(raid.objectId, brain(h).rbBossId(), "locked the minion: bossId " + brain(h).rbBossId() + " " + h.notes());
        assertTrue(raid.hp < 8000, "DD did not hit the raid boss: hp " + raid.hp + " " + h.notes() + " " + h.act().calls());
        assertEquals(5000, minion.hp, 0.01, "hit the minion instead: " + minion.hp + " " + h.notes());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("rb party [Bot/DD]:")), "no [Name/Role] prefix: " + h.notes());
    }

    @Test
    void tankStandsStillWhenKiteStepsAreBlockedAndKeepsFighting() {
        final FakeWorld w = BasicBrainTest.world(571, false);
        w.addSkill(FakeWorld.AGGRESSION, SkillView.Kind.OTHER, 10, 3000);
        final BrainHarness h = joined(w, 571, 5, false, "TANK");
        w.teleport(new Pos(40_000, 40_000, 0));
        // solid ring: kite steps (250–500) cannot leave a 60-wide hole; melee on the boss inside still can
        w.addWall(39_000, 39_000, 39_970, 41_000);
        w.addWall(40_030, 39_000, 41_000, 41_000);
        w.addWall(39_000, 39_000, 41_000, 39_970);
        w.addWall(39_000, 40_030, 41_000, 41_000);
        moveGleb(w, beside(w, 20), 0);
        final FakeWorld.Mob boss = w.addMob(9, 42, beside(w, 15));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        moveGleb(w, beside(w, 20), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(12_000);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        assertTrue(brain(h).rbKiteStand(), "kept trying blocked kite: kites " + brain(h).rbKites() + " " + h.notes());
        assertTrue(w.taunts >= 1, "stopped taunting: " + w.taunts + " " + h.notes());
        assertTrue(boss.hp < 8000, "stood idle without hitting: hp " + boss.hp + " " + h.notes() + " " + h.act().calls());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("stands still")), "no stand-still note: " + h.notes());
    }

    @Test
    void bishopHealsTheTankDuringTheRaidFight() {
        final FakeWorld w = BasicBrainTest.world(572, true);
        w.addSkill(1011, SkillView.Kind.OTHER, 20, 0);
        w.addSkill(1217, SkillView.Kind.OTHER, 20, 0);
        final BrainHarness h = joined(w, 572, 16, true, "BISHOP");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Kabanchik", 5, 42, beside(w, 180), 0, 40, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 38, beside(w, 80));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.hp = 8000;
        boss.targetId = 901;
        moveGleb(w, beside(w, 60), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        w.partyCasts.clear();
        h.run(4000);
        assertTrue(brain(h).rbEngaged(), "did not engage: " + h.notes());
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.startsWith("1011>901") || c.startsWith("1217>901")),
            "bishop did not heal the tank during the fight: " + w.partyCasts + " " + h.notes() + " " + h.act().calls());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("rb party [Bot/BISHOP]:") && n.contains("heals")),
            "no heal note: " + h.notes());
    }

    @Test
    void bishopHealsInsteadOfKitingWhenAnAllyIsLow() {
        final FakeWorld w = BasicBrainTest.world(573, true);
        w.addSkill(1011, SkillView.Kind.OTHER, 20, 0);
        final BrainHarness h = joined(w, 573, 16, true, "BISHOP");
        w.teleport(new Pos(40_000, 40_000, 0));
        w.party.add(new PartyMember(901, "Kabanchik", 5, 42, beside(w, 200), 0, 100, 100, false, 0, false));
        final FakeWorld.Mob boss = w.addMob(9, 38, beside(w, 120));
        boss.bossKind = WorldView.BOSS_RAID;
        boss.targetId = FakeWorld.SELF_ID; // on the bishop: old code kited and cancelled heals
        moveGleb(w, beside(w, 80), boss.objectId);
        w.chatFrom("PARTY", GLEB, "Gleb", "го");
        h.run(2500);
        assertTrue(brain(h).rbEngaged(), h.notes().toString());
        w.party.removeIf(m -> m.objectId() == 901);
        w.party.add(new PartyMember(901, "Kabanchik", 5, 42, beside(w, 200), 0, 35, 100, false, 0, false));
        w.partyCasts.clear();
        h.act().clear();
        h.run(4000);
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.startsWith("1011>901")),
            "kited instead of healing: casts " + w.partyCasts + " kites " + brain(h).rbKites() + " " + h.notes() + " " + h.act().calls());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("heals Kabanchik")), "no heal note: " + h.notes());
    }
}

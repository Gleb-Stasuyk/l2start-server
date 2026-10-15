package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 6b/6c: a real player invites a bot, plays with it (follow, assist, commands, support) and it leaves politely. */
class CompanionBrainTest {
    static final long MIN = 60_000;
    static final int GLEB = 5000;

    @AfterEach
    void reset() {
        BasicBrain.afkShare = 0.15;
    }

    static BotInfo info(boolean mage, double sociability, double patience) {
        return new BotInfo(1, 100, "Bot", mage ? 10 : 0, false, "RU", 0.5, sociability, 0.5, patience, 0.5, 0.8, 700, BasicBrainTest.TOWN, 42);
    }

    static PartyMember gleb(Pos at, int level) {
        return gleb(at, level, 100, false, 0, false);
    }

    static PartyMember gleb(Pos at, int level, double hp, boolean dead, int targetId, boolean sitting) {
        return new PartyMember(GLEB, "Gleb", 0, level, at, 0, hp, 100, dead, targetId, true, false, sitting);
    }

    private static BrainHarness onTheGround(FakeWorld w, long seed, boolean mage, double sociability, double patience) {
        BasicBrain.afkShare = 0;
        w.setLevel(6);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, info(mage, sociability, patience), w, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.run(2 * MIN);
        return h;
    }

    private static Pos beside(FakeWorld w, int dx) {
        return new Pos(w.pos().x() + dx, w.pos().y(), 0);
    }

    /** Invite with a friend's memory (always yes) and let it answer. */
    private static BrainHarness joined(FakeWorld w, long seed, boolean mage, double patience) {
        final BrainHarness h = onTheGround(w, seed, mage, 0.9, patience);
        h.memory().note(GLEB, 50, Memory.Tag.PARTY, true);
        w.invite(gleb(beside(w, 150), 6), 0);
        h.run(12_000);
        assertTrue(w.chat.contains("answerParty:true"), "did not join: " + w.chat + " " + h.notes());
        return h;
    }

    private static void moveGleb(FakeWorld w, PartyMember g) {
        w.party.removeIf(m -> m.objectId() == GLEB);
        w.party.add(g);
    }

    private static BasicBrain brain(BrainHarness h) {
        return (BasicBrain) h.brain();
    }

    // ------------------------------------------------------------------ 6b: the decision

    @Test
    void partyCommandsRuAndEn() {
        assertEquals(PartyCommands.Command.STOP, PartyCommands.parse("стоп"));
        assertEquals(PartyCommands.Command.STOP, PartyCommands.parse("Стой!"));
        assertEquals(PartyCommands.Command.STOP, PartyCommands.parse("не бей его"));
        assertEquals(PartyCommands.Command.STOP, PartyCommands.parse("don't attack"));
        assertEquals(PartyCommands.Command.FOLLOW, PartyCommands.parse("за мной"));
        assertEquals(PartyCommands.Command.FOLLOW, PartyCommands.parse("follow me"));
        assertEquals(PartyCommands.Command.ASSIST, PartyCommands.parse("бей мою"));
        assertEquals(PartyCommands.Command.ASSIST, PartyCommands.parse("assist"));
        assertEquals(PartyCommands.Command.FREE_HUNT, PartyCommands.parse("сам бей"));
        assertEquals(PartyCommands.Command.FREE_HUNT, PartyCommands.parse("фарми"));
        assertEquals(PartyCommands.Command.BUFF, PartyCommands.parse("бафни"));
        assertEquals(PartyCommands.Command.BUFF, PartyCommands.parse("buff pls"));
        assertEquals(PartyCommands.Command.HEAL, PartyCommands.parse("хил"));
        assertEquals(PartyCommands.Command.SIT, PartyCommands.parse("сядь"));
        assertEquals(PartyCommands.Command.SIT, PartyCommands.parse("отдыхаем"));
        assertEquals(PartyCommands.Command.RES, PartyCommands.parse("рес"));
        assertEquals(PartyCommands.Command.GIVE_LEAD, PartyCommands.parse("дай лидера"));
        assertEquals(PartyCommands.Command.TAKE_LEAD, PartyCommands.parse("бери лидера"));
        assertEquals(PartyCommands.Command.WAIT, PartyCommands.parse("жди, иду в город"));
        assertEquals(PartyCommands.Command.NONE, PartyCommands.parse("как дела у тебя сегодня вообще"));
        assertEquals(PartyCommands.Command.NONE, PartyCommands.parse(null));
    }

    // ------------------------------------------------------------------ 6b: in the game

    @Test
    void answersAfterAFewSecondsAndGreetsInPartyChat() {
        final FakeWorld w = BasicBrainTest.world(61, false);
        final BrainHarness h = onTheGround(w, 61, false, 0.9, 0.5);
        h.memory().note(GLEB, 50, Memory.Tag.PARTY, true);
        w.invite(gleb(beside(w, 150), 6), 0);
        h.run(1500);
        assertFalse(w.chat.contains("answerParty:true"), "a person needs a moment: " + w.chat);
        h.run(10_000);
        assertTrue(w.chat.contains("answerParty:true"), w.chat.toString());
        h.run(15_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("party:")), "no hello: " + w.chat);
        assertEquals(GLEB, brain(h).mateId());
    }

    @Test
    void tooHighLevelSaysNoAndMayWhisperWhy() {
        int whispered = 0;
        for (long seed = 70; seed < 76; seed++) {
            final FakeWorld w = BasicBrainTest.world(seed, false);
            final BrainHarness h = onTheGround(w, seed, false, 0.9, 0.5);
            w.invite(gleb(beside(w, 150), 30), 0);
            h.run(15_000);
            assertTrue(w.chat.contains("answerParty:false"), w.chat.toString());
            assertTrue(w.party.isEmpty());
            assertTrue(h.memory().of(GLEB).has(Memory.Tag.DECLINED));
            whispered += w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")) ? 1 : 0;
            assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("party:")), "said something in a party it is not in");
        }
        assertTrue(whispered > 0, "never told why");
    }

    // ------------------------------------------------------------------ 6c

    @Test
    void followsItsMateAndFightsHisMonster() {
        final FakeWorld w = BasicBrainTest.world(62, false);
        final BrainHarness h = joined(w, 62, false, 0.5);
        final Pos far = beside(w, 1200);
        moveGleb(w, gleb(far, 6));
        h.run(20_000);
        assertTrue(w.pos().distance2d(far) < 500, "did not follow: " + w.pos() + " " + h.notes());
        final FakeWorld.Mob mob = w.addMob(2, 6, new Pos(far.x() + 100, far.y(), 0));
        mob.hp = 60; // Gleb hits it already
        moveGleb(w, gleb(far, 6, 100, false, mob.objectId, false));
        final int from = h.act().calls().size();
        h.run(15_000);
        assertTrue(h.act().calls().subList(from, h.act().calls().size()).stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals(String.valueOf(mob.objectId))),
            "did not assist: " + h.notes());
    }

    @Test
    void obeysStopAndFollowAndAnswers() {
        final FakeWorld w = BasicBrainTest.world(63, false);
        final BrainHarness h = joined(w, 63, false, 0.5);
        final int said = (int) w.chat.stream().filter(c -> c.startsWith("party:")).count();
        w.event(new BotEvent.Chat("PARTY", GLEB, "Gleb", "стоп"));
        h.run(8000);
        assertEquals(BasicBrain.PartyMode.STOP, brain(h).partyMode());
        w.event(new BotEvent.Chat("PARTY", GLEB, "Gleb", "сам бей"));
        h.run(8000);
        assertEquals(BasicBrain.PartyMode.FREE, brain(h).partyMode());
        w.event(new BotEvent.Chat("PARTY", GLEB, "Gleb", "за мной"));
        h.run(8000);
        assertEquals(BasicBrain.PartyMode.FOLLOW, brain(h).partyMode());
        assertTrue(w.chat.stream().filter(c -> c.startsWith("party:")).count() > said, "never said ok: " + w.chat);
        w.event(new BotEvent.Chat("PARTY", 777, "Other", "стоп"));
        h.run(5000);
        assertEquals(BasicBrain.PartyMode.FOLLOW, brain(h).partyMode(), "only its mate commands it");
    }

    @Test
    void healerHealsAndRaisesItsMateAndBuffsOnRequest() {
        final FakeWorld w = BasicBrainTest.world(64, true);
        w.addSkill(1011, SkillView.Kind.OTHER, 15, 3000);
        w.addSkill(1016, SkillView.Kind.OTHER, 30, 5000);
        w.addSkill(1068, SkillView.Kind.OTHER, 20, 3000);
        final BrainHarness h = joined(w, 64, true, 0.5);
        w.setMp(w.maxMp());
        moveGleb(w, gleb(beside(w, 200), 6, 35, false, 0, false));
        h.run(10_000);
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.equals("1011>" + GLEB)), "no heal: " + w.partyCasts + " " + h.notes());
        moveGleb(w, gleb(beside(w, 200), 6, 100, false, 0, false));
        w.event(new BotEvent.Chat("PARTY", GLEB, "Gleb", "бафни"));
        h.run(15_000);
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.equals("1068>" + GLEB)), "no buff on request: " + w.partyCasts);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("party:")), w.chat.toString());
        w.setMp(w.maxMp());
        moveGleb(w, gleb(beside(w, 300), 6, 0, true, 0, false));
        h.run(20_000);
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.equals("1016>" + GLEB)), "did not raise its mate: " + w.partyCasts + " " + h.notes());
        assertTrue(h.memory().of(GLEB).has(Memory.Tag.I_REZZED));
    }

    @Test
    void inTownCompanionCastsOnlyWindWalk() {
        final FakeWorld w = BasicBrainTest.world(68, true);
        w.addSkill(PartyBuffs.WIND_WALK, SkillView.Kind.OTHER, 10, 2000);
        w.addSkill(1068, SkillView.Kind.OTHER, 20, 2000); // Might — must not cast in town
        final BrainHarness h = joined(w, 68, true, 0.5);
        w.teleport(BasicBrainTest.TOWN);
        w.setPeace(true);
        w.setMp(w.maxMp());
        moveGleb(w, gleb(beside(w, 150), 6, 100, false, 0, false));
        w.event(new BotEvent.Chat("PARTY", GLEB, "Gleb", "бафни"));
        h.run(20_000);
        assertTrue(w.partyCasts.stream().anyMatch(c -> c.equals(PartyBuffs.WIND_WALK + ">" + GLEB)), "no Wind Walk: " + w.partyCasts + " " + h.notes());
        assertTrue(w.partyCasts.stream().noneMatch(c -> c.equals("1068>" + GLEB)), "Might in town: " + w.partyCasts);
    }

    @Test
    void catchesUpViaGatekeeperWhenMateTeleports() {
        final FakeWorld w = BasicBrainTest.world(65, false);
        final BrainHarness h = joined(w, 65, false, 0.2);
        final Pos homeGk = new Pos(w.pos().x() + 80, w.pos().y(), 0);
        final Pos farGk = new Pos(w.pos().x() + 18_000, w.pos().y(), 0);
        final Pos mateAt = new Pos(farGk.x() + 120, farGk.y(), 0);
        w.addGk(homeGk);
        w.addGk(farGk);
        w.addGkHop(homeGk, farGk);
        moveGleb(w, gleb(mateAt, 6));
        h.run(3 * MIN);
        assertFalse(w.chat.contains("leaveParty"), "left instead of catching up: " + h.notes());
        assertTrue(w.gkTeleports >= 1, "never used the gatekeeper: " + h.act().calls() + " " + h.notes());
        assertTrue(w.pos().distance2d(mateAt) < BasicBrain.MATE_FAR, "did not catch up: bot=" + w.pos() + " mate=" + mateAt + " " + h.notes());
        assertTrue(brain(h).mateId() == GLEB, "left the party while catching up");
    }

    @Test
    void leavesAfterTenMinutesIfCannotCatchUp() {
        final FakeWorld w = BasicBrainTest.world(66, false);
        final BrainHarness h = joined(w, 66, false, 0.9);
        // farther than a bot can run in 10 min at FakeWorld speed (~72k), no GK hops
        moveGleb(w, gleb(new Pos(w.pos().x() + 100_000, w.pos().y(), 0), 6));
        h.run(5 * MIN);
        assertFalse(w.chat.contains("leaveParty"), "left too early: " + h.notes());
        h.run(7 * MIN);
        assertTrue(w.chat.contains("leaveParty"), "catches up forever: " + h.notes());
    }

    @Test
    void afkMateIsLeftWithAGoodbye() {
        final FakeWorld w = BasicBrainTest.world(67, false);
        final BrainHarness h = joined(w, 67, false, 0.5);
        moveGleb(w, gleb(beside(w, 150), 6));
        h.run(12 * MIN);
        assertTrue(w.chat.contains("leaveParty"), "stays with an AFK player: " + h.notes());
        final int i = w.chat.indexOf("leaveParty");
        assertTrue(w.chat.subList(0, i).stream().anyMatch(c -> c.startsWith("party:")), "no goodbye before leaving: " + w.chat);
        assertTrue(h.memory().of(GLEB).partyMinutes() >= 10, "minutes: " + h.memory().of(GLEB));
    }
}

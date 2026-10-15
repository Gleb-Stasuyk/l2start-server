package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.chat.SocialBoard;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 6e: whisper template replies and party-chat reactions (no SocialAction). */
class WhisperBrainTest {
    static final Pos TOWN = BasicBrainTest.TOWN;
    static final long MIN = BasicBrainTest.MIN;
    static final int GLEB = 5000;

    private boolean prevSocial;

    @BeforeEach
    void saveSocial() {
        prevSocial = BasicBrain.social;
        BasicBrain.social = true;
    }

    @AfterEach
    void restoreSocial() {
        BasicBrain.social = prevSocial;
        BasicBrain.afkShare = 0.15;
        BasicBrain.socialNoise = 1.0;
    }

    private static BotInfo chatty(boolean mage, int classId) {
        return new BotInfo(1, 100, "Bot", classId, false, "RU", 0.5, 1.0, 0.5, 0.5, 0.5, 1.0, 400, TOWN, 42);
    }

    private static BrainHarness townBot(FakeWorld w, long seed, int classId) {
        w.classId = classId;
        w.setLevel(40);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, chatty(classId == 16 || classId == 10, classId), w, seed);
        h.setSocial(new SocialBoard(60));
        h.setAssignment(new Assignment(1, Assignment.Kind.TOWN, "gludio", TOWN, 800, 1, 40, List.of(), List.of(), null));
        w.addPlayer(new PlayerView(GLEB, "Gleb", 40, 0, TOWN, 50, 0, false, false, 0, false));
        return h;
    }

    @Test
    void whisperHiGetsReplyAndNeverFirst() {
        final FakeWorld w = new FakeWorld(81, TOWN, false);
        final BrainHarness h = townBot(w, 81, 0);
        h.run(3_000);
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("whisper:")), "bot whispered first: " + w.chat);
        w.whisperFrom("Gleb", "привет");
        h.run(15_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "no GREET reply: " + w.chat + " " + h.notes());
    }

    @Test
    void whisperLevelAndSpot() {
        final FakeWorld w = new FakeWorld(82, TOWN, false);
        final BrainHarness h = townBot(w, 82, 0);
        h.run(1_000);
        w.whisperFrom("Gleb", "какой лвл?");
        h.run(12_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:") && c.contains("40")),
            "no level in reply: " + w.chat);
        w.chat.clear();
        h.run(9_000); // past whisper cooldown
        w.whisperFrom("Gleb", "где качаешься?");
        h.run(12_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "no spot reply: " + w.chat + " " + h.notes());
    }

    @Test
    void insultSetsAnnoyed() {
        final FakeWorld w = new FakeWorld(83, TOWN, false);
        final BrainHarness h = townBot(w, 83, 0);
        h.run(1_000);
        w.whisperFrom("Gleb", "нуб");
        h.run(12_000);
        assertTrue(h.memory().of(GLEB).has(Memory.Tag.ANNOYED), "ANNOYED not set: " + h.memory().of(GLEB));
    }

    @Test
    void lfpPlusStillInvites() {
        BasicBrain.socialNoise = 50;
        final FakeWorld w = new FakeWorld(84, TOWN, false);
        final BrainHarness h = townBot(w, 84, 0);
        final BasicBrain brain = (BasicBrain) h.brain();
        brain.lfpNextTry = 0;
        h.run(5 * MIN);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("shout:") || c.startsWith("trade:")), "no shout: " + w.chat);
        w.whisperFrom("Gleb", "+");
        h.run(20_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("inviteParty:Gleb")),
            "6d invite broken: " + w.chat + " " + h.notes());
    }

    @Test
    void leaveAloneMutesWhisperDay() {
        final FakeWorld w = new FakeWorld(90, TOWN, false);
        final BrainHarness h = townBot(w, 90, 0);
        h.run(1_000);
        w.whisperFrom("Gleb", "отстань");
        h.run(8_000);
        assertTrue(h.memory().of(GLEB).has(Memory.Tag.ANNOYED), "ANNOYED: " + h.memory().of(GLEB));
        w.chat.clear();
        w.whisperFrom("Gleb", "привет");
        h.run(12_000);
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "must stay silent after leave-alone: " + w.chat);
    }

    @Test
    void silentWhenPlayerBusyOrBlocked() {
        final FakeWorld w = new FakeWorld(91, TOWN, false);
        final BrainHarness h = townBot(w, 91, 0);
        h.run(1_000);
        w.clearPlayers();
        w.addPlayer(new PlayerView(GLEB, "Gleb", 40, 0, TOWN, 50, 0, false, true, 0, false)); // in combat
        w.whisperFrom("Gleb", "привет");
        h.run(12_000);
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "no reply in combat: " + w.chat);

        w.chat.clear();
        w.clearPlayers();
        w.addPlayer(new PlayerView(GLEB, "Gleb", 40, 0, TOWN, 50, 0, false, false, 0, false, true, false)); // store
        w.whisperFrom("Gleb", "привет");
        h.run(12_000);
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "no reply in store: " + w.chat);

        w.chat.clear();
        w.clearPlayers();
        w.addPlayer(new PlayerView(GLEB, "Gleb", 40, 0, TOWN, 50, 0, false, false, 0, false));
        w.blockedBy.add("gleb");
        w.whisperFrom("Gleb", "привет");
        h.run(12_000);
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "no reply when blocked: " + w.chat);
    }

    @Test
    void whisperHourLimitStopsReplies() {
        final FakeWorld w = new FakeWorld(92, TOWN, false);
        final BrainHarness h = townBot(w, 92, 0);
        final SocialBoard board = new SocialBoard(60);
        board.setWhispersPerHour(1);
        h.setSocial(board);
        h.run(1_000);
        w.whisperFrom("Gleb", "привет");
        h.run(12_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")), "first reply: " + w.chat);
        w.chat.clear();
        h.run(9_000); // past per-player cooldown
        w.whisperFrom("Gleb", "какой лвл?");
        h.run(12_000);
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "second reply must hit WhisperPerHour: " + w.chat);
    }

    @Test
    void partyGratsOnMateLevelUp() {
        BasicBrain.afkShare = 0;
        BasicBrain.socialNoise = 2.0; // lively: keep party reactions
        final FakeWorld w = new FakeWorld(85, TOWN, false);
        w.setLevel(6);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, chatty(false, 0), w, 85);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.memory().note(GLEB, 50, Memory.Tag.PARTY, true);
        h.run(2 * MIN);
        final Pos at = new Pos(w.pos().x() + 150, w.pos().y(), 0);
        w.invite(new PartyMember(GLEB, "Gleb", 0, 6, at, 0, 100, 100, false, 0, true, false, false), 0);
        h.run(12_000);
        assertTrue(w.chat.contains("answerParty:true"), "did not join: " + w.chat);
        w.chat.clear();
        // mate levels up: replace party member with higher level
        w.party.removeIf(m -> m.objectId() == GLEB);
        w.party.add(new PartyMember(GLEB, "Gleb", 0, 7, at, 150, 100, 100, false, 0, true, false, false));
        h.run(8_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("party:")),
            "no GRATS in party chat: " + w.chat + " " + h.notes());
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("whisper:")), "grats must not be whisper: " + w.chat);
        assertFalse(w.chat.stream().anyMatch(c -> c.startsWith("social:")), "no SocialAction: " + w.chat);
    }

    @Test
    void partyThanksOnNewBuff() {
        BasicBrain.afkShare = 0;
        BasicBrain.socialNoise = 2.0;
        final FakeWorld w = new FakeWorld(86, TOWN, false);
        w.setLevel(6);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, chatty(false, 0), w, 86);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.memory().note(GLEB, 50, Memory.Tag.PARTY, true);
        h.run(2 * MIN);
        final Pos at = new Pos(w.pos().x() + 150, w.pos().y(), 0);
        w.invite(new PartyMember(GLEB, "Gleb", 0, 6, at, 0, 100, 100, false, 0, true, false, false), 0);
        h.run(12_000);
        assertTrue(w.chat.contains("answerParty:true"));
        h.run(160_000); // past party-react cooldown (self-buff may have thanked once)
        w.chat.clear();
        // simulate a new buff appearing on the bot (mate buffed it); id ≠ self-buff Wind Walk
        w.addSkill(new FakeWorld.Extra(1040, SkillView.Kind.BUFF, 20, 0, 0, 0, 0, 600));
        h.run(5_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("party:")),
            "no THANKS_BUFF: " + w.chat + " " + h.notes());
        assertTrue(h.memory().of(GLEB).has(Memory.Tag.BUFFED_ME), "BUFFED_ME not set");
    }
}

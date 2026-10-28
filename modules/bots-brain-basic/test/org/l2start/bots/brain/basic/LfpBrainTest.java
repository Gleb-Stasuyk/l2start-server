package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.chat.SocialBoard;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 6d: LFP/LFM in town, whisper invite, reply to player's LFP. */
class LfpBrainTest {
    static final Pos TOWN = BasicBrainTest.TOWN;
    static final long MIN = BasicBrainTest.MIN;

    private boolean prevSocial;
    private int prevReplies;
    private double prevNoise;

    @BeforeEach
    void saveSocial() {
        prevSocial = BasicBrain.social;
        prevReplies = BasicBrain.lfpReplies;
        prevNoise = BasicBrain.socialNoise;
        BasicBrain.social = true;
        BasicBrain.lfpReplies = 2;
        BasicBrain.socialNoise = 1.0;
    }

    @AfterEach
    void restoreSocial() {
        BasicBrain.social = prevSocial;
        BasicBrain.lfpReplies = prevReplies;
        BasicBrain.socialNoise = prevNoise;
    }

    private static BotInfo sociable(boolean mage, int classId) {
        return new BotInfo(1, 100, "Bot", classId, false, "RU", 0.5, 1.0, 0.5, 0.5, 0.5, 0.8, 400, TOWN, 42);
    }

    private static BrainHarness townBot(FakeWorld w, long seed, int classId) {
        final boolean mage = classId == 16 || classId == 10;
        w.classId = classId;
        w.setLevel(40);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, sociable(mage, classId), w, seed);
        h.setSocial(new SocialBoard(60));
        h.setAssignment(new Assignment(1, Assignment.Kind.TOWN, "gludio", TOWN, 800, 1, 40, List.of(), List.of(), null));
        w.addPlayer(new PlayerView(5000, "Gleb", 40, 0, TOWN, 50, 0, false, false, 0, false));
        return h;
    }

    @Test
    void botInTownEventuallyShoutsLfp() {
        BasicBrain.socialNoise = 50; // almost always takes a shout slot when due
        final FakeWorld w = new FakeWorld(71, TOWN, false);
        final BrainHarness h = townBot(w, 71, 0);
        final BasicBrain brain = (BasicBrain) h.brain();
        brain.lfpNextTry = 0;
        h.run(8 * MIN);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("shout:") || c.startsWith("trade:")),
            "no LFP shout: " + w.chat + " " + h.notes());
    }

    @Test
    void whisperPlusAfterShoutInvites() {
        BasicBrain.socialNoise = 50;
        final FakeWorld w = new FakeWorld(72, TOWN, false);
        final BrainHarness h = townBot(w, 72, 0);
        final BasicBrain brain = (BasicBrain) h.brain();
        brain.lfpNextTry = 0;
        h.run(5 * MIN);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("shout:") || c.startsWith("trade:")), "no shout first: " + w.chat);
        w.whisperFrom("Gleb", "+");
        h.run(20_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("inviteParty:Gleb")),
            "no invite after +: " + w.chat + " " + h.notes());
    }

    @Test
    void playerLfpShoutGetsHealerReply() {
        final FakeWorld w = new FakeWorld(73, TOWN, true);
        final BrainHarness h = townBot(w, 73, 16); // Bishop
        h.run(2_000); // settle in town
        w.chatFrom("SHOUT", 5000, "Gleb", "LFP 40 heal");
        h.run(15_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("whisper:Gleb:")),
            "healer did not whisper LFP_REPLY: " + w.chat + " " + h.notes());
    }
}

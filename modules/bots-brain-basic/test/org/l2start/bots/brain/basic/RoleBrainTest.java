package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SelfView;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.brain.basic.Playstyles.Cond;
import org.l2start.bots.brain.basic.Playstyles.Entry;
import org.l2start.bots.brain.basic.Playstyles.Use;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 4c, second part: summoners with a servitor, daggers from behind, charges, spoil and sweep, healers, Dash. */
class RoleBrainTest {
    static final long MIN = 60_000;
    static Playstyles data;

    @BeforeAll
    static void load() throws Exception {
        data = Playstyles.load(PlaystyleTest.DATA);
    }

    @AfterEach
    void defaults() {
        BasicBrain.playstyles = Playstyles.EMPTY;
        BasicBrain.usePlaystyles = true;
        BasicBrain.summons = true;
    }

    static long casts(FakeWorld w, int skillId) {
        return w.casts.stream().filter(c -> c.startsWith(skillId + "->")).count();
    }

    static FakeWorld summoner(long seed) {
        final FakeWorld w = SurvivalBrainTest.ground(seed, true, 5, 8);
        w.classId = 14; // Warlock
        w.addSkill(new FakeWorld.Extra(1111, SkillView.Kind.SUMMON, 15, 20_000, 1458, 2, 0));
        w.addSkill(BasicBrain.SERVITOR_HEAL, SkillView.Kind.OTHER, 8, 3000);
        w.giveItem(1458, 40);
        return w;
    }

    @Test
    void summonerCallsItsServitorSendsItAtTheTargetAndNeverLeavesItBehind() {
        BasicBrain.playstyles = data;
        final FakeWorld w = summoner(61);
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, true, 61, 20 * MIN);
        assertTrue(r.b().summoned() >= 1, "summoned " + r.b().summoned() + " " + r.h().notes());
        assertTrue(w.itemCount(1458) <= 38, "the call takes its crystals");
        assertTrue(r.h().act().countOk("petAttack") >= 3, "pet orders " + r.h().act().count("petAttack"));
        assertTrue(w.petKills >= 3, "kills by the servitor " + w.petKills + ", all " + w.kills);
        assertTrue(w.petId != 0 || r.b().summoned() >= 2, "a servitor is out (or was called again)");
        // the bot dies: the servitor goes with it
        if (w.petId == 0) {
            r.h().run(MIN);
        }
        assertTrue(w.petId != 0, "servitor out before the death");
        w.kill();
        r.h().run(5000);
        assertEquals(0, w.petId, "no servitor left alone");
        assertTrue(r.h().act().countOk("unsummon") >= 1);
    }

    @Test
    void servitorIsHealedWhenHurt() {
        BasicBrain.playstyles = data;
        final FakeWorld w = summoner(62);
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, true, 62, 20 * MIN);
        assertTrue(casts(w, BasicBrain.SERVITOR_HEAL) >= 1, "servitor heals " + casts(w, BasicBrain.SERVITOR_HEAL) + " " + w.casts);
        assertTrue(w.casts.stream().filter(c -> c.startsWith(BasicBrain.SERVITOR_HEAL + "->")).noneMatch(c -> c.endsWith("->" + FakeWorld.SELF_ID)), "on the servitor");
    }

    @Test
    void noServitorWhenSwitchedOff() {
        BasicBrain.summons = false;
        final FakeWorld w = summoner(63);
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, true, 63, 10 * MIN);
        assertEquals(0, r.b().summoned());
        assertEquals(0, r.h().act().count("petAttack"));
    }

    @Test
    void servitorTierAndChoice() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, true);
        w.addSkill(new FakeWorld.Extra(1111, SkillView.Kind.SUMMON, 15, 0, 1458, 2, 0));
        w.addSkill(new FakeWorld.Extra(1225, SkillView.Kind.SUMMON, 15, 0, 1458, 1, 0));
        final SelfView low = w.view().self();
        assertEquals(1111, BasicBrain.servitorSkill(low, 2).id());
        assertEquals(1225, BasicBrain.servitorSkill(low, 3).id());
        w.addSkill(new FakeWorld.Extra(1276, SkillView.Kind.SUMMON, 15, 0, 1459, 1, 0));
        assertEquals(1276, BasicBrain.servitorSkill(w.view().self(), 2).id(), "the strongest tier it knows");
        assertNull(BasicBrain.servitorSkill(new FakeWorld(1, BasicBrainTest.TOWN, true).view().self(), 1));
    }

    @Test
    void daggerWalksRoundToTheBackAndBackstabs() {
        BasicBrain.playstyles = data;
        final FakeWorld w = SurvivalBrainTest.ground(64, false, 5, 8);
        final Random rnd = new Random(64);
        for (FakeWorld.Mob m : w.mobs().values()) {
            m.heading = rnd.nextInt(65536);
        }
        w.classId = 8; // Treasure Hunter, a dagger (no bow)
        w.addSkill(FakeWorld.BACKSTAB_ID, SkillView.Kind.STRIKE, 8, 6000);
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, false, 64, 20 * MIN);
        assertTrue(r.b().sneaks() >= 3, "round to the back " + r.b().sneaks() + " " + r.h().notes());
        assertTrue(casts(w, FakeWorld.BACKSTAB_ID) >= 3, "backstabs " + casts(w, FakeWorld.BACKSTAB_ID));
        assertEquals(0, w.extraFailures, "never from the front (the game refuses it)");
        assertTrue(w.kills >= 5, "kills " + w.kills);
    }

    @Test
    void gladiatorBuildsChargesAndSpendsThem() {
        BasicBrain.playstyles = data;
        final FakeWorld w = SurvivalBrainTest.ground(65, false, 5, 8);
        w.classId = 2; // Gladiator
        w.addSkill(8, SkillView.Kind.OTHER, 4, 1500); // Sonic Focus: +1 charge
        w.addSkill(new FakeWorld.Extra(6, SkillView.Kind.STRIKE, 8, 4000, 0, 0, 3)); // Sonic Blaster: 3 charges
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, false, 65, 20 * MIN);
        assertTrue(casts(w, 8) >= 3, "Sonic Focus " + casts(w, 8));
        assertTrue(casts(w, 6) >= 1, "Sonic Blaster " + casts(w, 6) + " " + w.casts);
        assertEquals(0, w.extraFailures, "never without the charges");
        assertTrue(r.b().details().contains("Style: Duelist"), r.b().details());
        assertTrue(r.b().details().contains("Last skill: ROTATION"), r.b().details());
        assertTrue(r.b().details().contains("CHARGES"), r.b().details());
    }

    @Test
    void spoilerSpoilsAndSweepsTheBody() {
        BasicBrain.playstyles = data;
        final FakeWorld w = SurvivalBrainTest.ground(66, false, 5, 8);
        w.classId = 54; // Scavenger
        w.addSkill(FakeWorld.SPOIL_ID, SkillView.Kind.OTHER, 5, 2000);
        w.addSkill(FakeWorld.SWEEPER_ID, SkillView.Kind.CORPSE, 3, 0);
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, false, 66, 20 * MIN);
        assertTrue(casts(w, FakeWorld.SPOIL_ID) >= 3, "spoils " + casts(w, FakeWorld.SPOIL_ID));
        assertTrue(w.sweeps >= 3, "sweeps " + w.sweeps + " " + r.h().notes());
        assertEquals(w.sweeps, r.b().sweeps());
        assertEquals(w.sweeps, w.itemCount(FakeWorld.SWEEP_ITEM));
    }

    @Test
    void healerHealsItselfAndUsesHolySkillsOnlyOnUndead() {
        BasicBrain.playstyles = data;
        final FakeWorld w = SurvivalBrainTest.ground(67, true, 6, 8);
        int i = 0;
        for (FakeWorld.Mob m : w.mobs().values()) {
            m.undead = (i++ % 2) == 0;
        }
        w.classId = 16; // Bishop
        w.addSkill(1011, SkillView.Kind.OTHER, 8, 3000); // Heal
        w.addSkill(1028, SkillView.Kind.NUKE, 8, 2000); // Might of Heaven
        w.setHp(w.maxHp() * 0.5); // hurt, MP full: it heals itself instead of sitting down
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, true, 67, 25 * MIN);
        assertTrue(casts(w, 1011) >= 1, "heals " + casts(w, 1011) + " " + r.h().notes());
        assertTrue(r.b().heals() >= 1);
        assertTrue(w.casts.stream().filter(c -> c.startsWith("1011->")).allMatch(c -> c.endsWith("->" + FakeWorld.SELF_ID)), "on itself");
        final long holy = w.casts.stream().filter(c -> c.startsWith("1028->")).count();
        assertTrue(holy >= 2, "holy " + holy);
        for (String c : w.casts) {
            if (c.startsWith("1028->")) {
                assertTrue(w.mobs().get(Integer.parseInt(c.substring(6))).undead, "holy on a living monster: " + c);
            }
        }
    }

    @Test
    void rogueDashesWhenItRunsAway() {
        final FakeWorld w = SurvivalBrainTest.ground(68, false, 12, 6);
        for (FakeWorld.Mob m : w.mobs().values()) {
            m.aggressive = true;
        }
        w.classId = 7;
        w.addSkill(BasicBrain.DASH, SkillView.Kind.OTHER, 5, 60_000);
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, false, 68, 20 * MIN);
        assertTrue(SurvivalBrainTest.noted(r, "runs"), "it ran " + r.h().notes());
        assertTrue(casts(w, BasicBrain.DASH) >= 1, "dash " + w.casts);
    }

    static Entry line(int id, Use use, Set<Cond> when, int chargesAtLeast, int chargesBelow) {
        return new Entry(id, "S" + id, use, when, 0, 0, 0, 0, 0, 0, 999, -1, 0, chargesAtLeast, chargesBelow);
    }

    static SelfView me(FakeWorld w, int charges) {
        final SelfView s = w.self();
        final List<SkillView> skills = List.of(new SkillView(30, 1, SkillView.Kind.STRIKE, 1, 40, true), new SkillView(6, 1, SkillView.Kind.STRIKE, 1, 40, true),
            new SkillView(8, 1, SkillView.Kind.OTHER, 1, 40, true), new SkillView(254, 1, SkillView.Kind.OTHER, 1, 40, true), new SkillView(1028, 1, SkillView.Kind.NUKE, 1, 600, true));
        return new SelfView(s.objectId(), s.name(), s.classId(), s.mage(), s.level(), s.expPercent(), s.sp(), 100, 100, 100, 100, s.pos(), false, false, false, true, false, false, 0,
            false, false, "ATTACK", true, skills, 0, 0, 40, false, charges, 0, 0, 0);
    }

    @Test
    void engineSeesFacingChargesSpoilAndUndead() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, false);
        w.teleport(new Pos(0, 4000, 0));
        final FakeWorld.Mob m = w.addMob(2, 5, new Pos(50, 4000, 0)); // the bot is west of it
        final PlaystyleEngine e = new PlaystyleEngine();
        final Playstyles.Playstyle rear = new Playstyles.Playstyle("t", Set.of(0), null, List.of(line(30, Use.ROTATION, EnumSet.of(Cond.REAR), 0, 0)));
        m.heading = 0; // looks east, away from the bot
        assertEquals(30, e.pick(rear, me(w, 0), w.view().npc(m.objectId), w.view(), Map.of(), 1000).skill().id());
        m.heading = 32768; // looks west, at the bot
        assertNull(e.pick(rear, me(w, 0), w.view().npc(m.objectId), w.view(), Map.of(), 1000));
        final Playstyles.Playstyle sonic = new Playstyles.Playstyle("t", Set.of(0), null,
            List.of(line(6, Use.ROTATION, EnumSet.of(Cond.CHARGES), 3, 0), line(8, Use.ROTATION, EnumSet.of(Cond.CHARGES_BELOW), 0, 4)));
        final NpcView mob = w.view().npc(m.objectId);
        assertEquals(8, new PlaystyleEngine().pick(sonic, me(w, 1), mob, w.view(), Map.of(), 1000).skill().id());
        assertEquals(6, new PlaystyleEngine().pick(sonic, me(w, 3), mob, w.view(), Map.of(), 1000).skill().id());
        assertNull(new PlaystyleEngine().pick(sonic, me(w, 5), mob, w.view(), Map.of(), 1000).skill().id() == 6 ? null : "x", "5 charges: spend, not build");
        final Playstyles.Playstyle spoil = new Playstyles.Playstyle("t", Set.of(0), null, List.of(line(254, Use.DEBUFF, EnumSet.of(Cond.NOT_SPOILED), 0, 0)));
        assertEquals(254, new PlaystyleEngine().pick(spoil, me(w, 0), mob, w.view(), Map.of(), 1000).skill().id());
        m.spoiled = true;
        assertNull(new PlaystyleEngine().pick(spoil, me(w, 0), w.view().npc(m.objectId), w.view(), Map.of(), 1000), "already spoiled");
        final Playstyles.Playstyle holy = new Playstyles.Playstyle("t", Set.of(0), null, List.of(line(1028, Use.ROTATION, EnumSet.of(Cond.TARGET_UNDEAD), 0, 0)));
        assertNull(new PlaystyleEngine().pick(holy, me(w, 0), w.view().npc(m.objectId), w.view(), Map.of(), 1000), "living");
        m.undead = true;
        assertEquals(1028, new PlaystyleEngine().pick(holy, me(w, 0), w.view().npc(m.objectId), w.view(), Map.of(), 1000).skill().id());
    }

    @Test
    void supportAndSummonerStylesExist() {
        assertTrue(data.of(16, false).name().startsWith("Healer"));
        assertTrue(data.of(30, false).name().startsWith("Healer"));
        assertTrue(data.of(43, false).name().startsWith("Healer"));
        assertTrue(data.of(52, false).name().startsWith("Orc support"));
        assertEquals("Summoner", data.of(14, false).name());
        assertEquals("Summoner", data.of(111, false).name());
        assertFalse(data.all().stream().flatMap(p -> p.entries().stream()).anyMatch(x -> x.when().contains(Cond.UNKNOWN)));
        // the gladiator line reads its charges from the data now
        assertTrue(data.of(2, false).entries().stream().anyMatch(x -> (x.skillId() == 261) && (x.chargesAtLeast() == 4)));
        assertTrue(data.of(2, false).entries().stream().anyMatch(x -> (x.skillId() == 8) && (x.chargesBelow() == 4)));
    }
}

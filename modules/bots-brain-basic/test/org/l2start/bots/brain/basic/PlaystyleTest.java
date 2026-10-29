package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

/** Phase 4c: class playstyles (data from Living Worlds, checked against CT0) and the engine that picks the skill. */
class PlaystyleTest {
    static final long MIN = 60_000;
    static final Path DATA = Path.of("modules/bots-brain-basic/data/playstyles.xml");
    static Playstyles data;

    @BeforeAll
    static void load() throws Exception {
        data = Playstyles.load(DATA);
    }

    @AfterEach
    void defaults() {
        BasicBrain.playstyles = Playstyles.EMPTY;
        BasicBrain.usePlaystyles = true;
    }

    @Test
    void dataCoversTheClassesAndEverySkillExistsInCt0() throws Exception {
        assertTrue(data.all().size() >= 35, "playstyles " + data.all().size());
        final Pattern p = Pattern.compile("<skill id=\"(\\d+)\" levels=\"(\\d+)\" name=\"([^\"]*)\"");
        final Map<Integer, String> ct0 = new HashMap<>();
        try (Stream<Path> files = Files.list(Path.of("core/dist/game/data/stats/skills"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".xml")).toList()) {
                final Matcher m = p.matcher(Files.readString(f));
                while (m.find()) {
                    ct0.put(Integer.parseInt(m.group(1)), m.group(3));
                }
            }
        }
        int n = 0;
        for (Playstyles.Playstyle ps : data.all()) {
            for (Entry e : ps.entries()) {
                n++;
                assertTrue(ct0.containsKey(e.skillId()), ps.name() + ": skill " + e.skillId() + " " + e.name() + " is not in CT0");
                assertEquals(norm(e.name()), norm(ct0.get(e.skillId())), ps.name() + ": skill " + e.skillId());
                assertFalse(e.when().contains(Cond.UNKNOWN), ps.name() + ": " + e.name() + " has an unknown condition");
            }
        }
        assertTrue(n >= 200, "lines " + n);
        // every class from the starting ones up to the 3rd professions has a style (support and summoners: the l2start block)
        assertEquals("Human Fighter (base)", data.of(0, false).name());
        assertNotNull(data.of(10, false));
        assertNotNull(data.of(88, false)); // Duelist
        assertEquals("ARCHER", data.of(7, true).role());
        assertEquals("DAGGER", data.of(7, false).role());
        assertNotNull(data.of(16, false)); // Bishop: l2start block (LW leaves support classes to its support logic)
        for (int id = 0; id <= 118; id++) {
            if (((id > 57) && (id < 88)) || (id == 0)) {
                continue;
            }
            assertTrue((data.of(id, false) != null) || (data.of(id, true) != null), "class " + id + " has no playstyle");
        }
    }

    static String norm(String s) {
        return s.toLowerCase().replace(":", "").replace(" ", "");
    }

    static Entry line(int id, Use use, Set<Cond> when, double selfHpBelow, int order) {
        return new Entry(id, "S" + id, use, when, 0, 0, 0, selfHpBelow, 0, 0, 999, -1, order);
    }

    static SelfView self(FakeWorld w, double hp, List<SkillView> skills) {
        final SelfView s = w.self();
        return new SelfView(s.objectId(), s.name(), s.classId(), s.mage(), s.level(), s.expPercent(), s.sp(), hp, 100, s.mp(), s.maxMp(), s.pos(), false, false, false, true,
            false, false, 0, false, false, "ATTACK", true, skills, 0, 0, 40, false);
    }

    @Test
    void engineOrderPanicFirstDebuffNotRepeatedOpenerOnceAreaOnlyWithTwoOnTheBot() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, false);
        w.teleport(new Pos(0, 4000, 0));
        w.setLevel(30);
        final FakeWorld.Mob m = w.addMob(2, 30, new Pos(30, 4000, 0));
        final NpcView mob = w.view().npc(m.objectId);
        final List<SkillView> skills = List.of(new SkillView(10, 1, SkillView.Kind.STRIKE, 1, 40, true), new SkillView(11, 1, SkillView.Kind.OTHER, 1, 40, true),
            new SkillView(12, 1, SkillView.Kind.OTHER, 1, 40, true), new SkillView(13, 1, SkillView.Kind.OTHER, 1, 40, true), new SkillView(14, 1, SkillView.Kind.OTHER, 1, 40, true));
        final Playstyles.Playstyle ps = new Playstyles.Playstyle("t", Set.of(0), null,
            List.of(line(10, Use.ROTATION, EnumSet.of(Cond.ALWAYS), 0, 0), line(11, Use.DEBUFF, EnumSet.of(Cond.DEBUFF_MISSING), 0, 1), line(12, Use.PANIC, EnumSet.of(Cond.SELF_HP_BELOW), 40, 2),
                line(13, Use.OPENER, EnumSet.of(Cond.ALWAYS), 0, 3), line(14, Use.AOE, EnumSet.of(Cond.ALWAYS), 0, 4)));
        final PlaystyleEngine e = new PlaystyleEngine();
        long now = 1000;
        // low HP: panic first, on the bot itself
        PlaystyleEngine.Pick p = e.pick(ps, self(w, 30, skills), mob, w.view(), Map.of(), now);
        assertEquals(12, p.skill().id());
        assertTrue(p.self());
        // healthy, a fresh target: the opener, then the debuff, then the rotation; the debuff not again within 30 s
        p = e.pick(ps, self(w, 90, skills), mob, w.view(), Map.of(), now);
        assertEquals(13, p.skill().id());
        e.cast(p, true, false, now);
        now += 2000;
        p = e.pick(ps, self(w, 90, skills), mob, w.view(), Map.of(), now);
        assertEquals(11, p.skill().id());
        e.cast(p, true, false, now);
        now += 2000;
        p = e.pick(ps, self(w, 90, skills), mob, w.view(), Map.of(), now);
        assertEquals(10, p.skill().id(), "opener once, debuff not repeated, area skill not with one monster");
        e.cast(p, true, false, now);
        // physical pace: the same skill not again within 1.5 s
        assertNull(e.pick(ps, self(w, 90, List.of(skills.get(0))), mob, w.view(), Map.of(), now + 500));
        // a skill refused on this monster type is skipped
        final Map<Long, Long> bad = Map.of(BasicBrain.badKey(10, mob.npcId()), now + 100_000);
        assertNull(e.pick(ps, self(w, 90, List.of(skills.get(0))), mob, w.view(), bad, now + 5000));
        // two monsters on the bot: the area skill comes in (before the rotation)
        final FakeWorld.Mob m2 = w.addMob(2, 30, new Pos(40, 4020, 0));
        m.targetId = FakeWorld.SELF_ID;
        m2.targetId = FakeWorld.SELF_ID;
        p = e.pick(ps, self(w, 90, skills), w.view().npc(m.objectId), w.view(), Map.of(), now + 5000);
        assertEquals(14, p.skill().id());
    }

    static SurvivalBrainTest.Run fighter(long seed, long ms) {
        final FakeWorld w = SurvivalBrainTest.ground(seed, false, 5, 8);
        w.strikeSkill = true;
        return SurvivalBrainTest.hunt(w, false, seed, ms);
    }

    @Test
    void fighterUsesPowerStrikeFromItsPlaystyle() {
        BasicBrain.playstyles = data;
        final SurvivalBrainTest.Run r = fighter(51, 20 * MIN);
        assertTrue(r.w().strikes >= 5, "strikes " + r.w().strikes + " " + r.h().notes());
        assertTrue(r.b().playstyleCasts() >= r.w().strikes, r.b().playstyleCasts() + " / " + r.w().strikes);
        assertTrue(r.b().playstyleLast().contains("Power Strike"), r.b().playstyleLast());
        assertTrue(r.w().kills >= 5, "kills " + r.w().kills);
    }

    @Test
    void mageCastsItsNukeThroughThePlaystyleAndStillKites() {
        BasicBrain.playstyles = data;
        final FakeWorld w = SurvivalBrainTest.ground(52, true, 5, 8);
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, true, 52, 20 * MIN);
        assertTrue(r.b().playstyleCasts() >= 10, "casts " + r.b().playstyleCasts());
        assertTrue(r.b().playstyleLast().contains("Wind Strike"), r.b().playstyleLast());
        assertTrue(r.w().kills >= 5, "kills " + r.w().kills);
        assertTrue(r.b().kites() >= 1, "kites " + r.b().kites());
    }

    @Test
    void noPlaystyleWhenSwitchedOff() {
        BasicBrain.playstyles = data;
        BasicBrain.usePlaystyles = false;
        final SurvivalBrainTest.Run r = fighter(53, 15 * MIN);
        assertEquals(0, r.w().strikes);
        assertEquals(0, r.b().playstyleCasts());
    }

    @Test
    void engineSkipsARotationLineThatNeedsAMissingReagent() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, false);
        w.teleport(new Pos(0, 4000, 0));
        w.setLevel(40);
        final FakeWorld.Mob m = w.addMob(2, 40, new Pos(30, 4000, 0));
        final NpcView mob = w.view().npc(m.objectId);
        final List<SkillView> skills = List.of(new SkillView(1148, 1, SkillView.Kind.OTHER, 30, 900, true, 0, 2508, 1),
            new SkillView(1234, 1, SkillView.Kind.NUKE, 20, 900, true));
        final Playstyles.Playstyle ps = new Playstyles.Playstyle("Soultaker", Set.of(13), null,
            List.of(line(1148, Use.ROTATION, EnumSet.of(Cond.ALWAYS), 0, 0), line(1234, Use.ROTATION, EnumSet.of(Cond.ALWAYS), 0, 1)));
        final PlaystyleEngine e = new PlaystyleEngine();
        final long now = 1000;
        assertEquals(1234, e.pick(ps, self(w, 90, skills), mob, w.view(), Map.of(), now).skill().id(),
            "Death Spike without Cursed Bones must not stall on the first line");
        w.giveItem(2508, 1);
        assertEquals(1148, e.pick(ps, self(w, 90, skills), mob, w.view(), Map.of(), now).skill().id(),
            "with bones the first rotation line fires");
    }

    @Test
    void pvpLineFiresOnlyInPvpPickAndSpoilDoesNot() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, false);
        w.teleport(new Pos(0, 4000, 0));
        w.setLevel(40);
        final FakeWorld.Mob m = w.addMob(2, 40, new Pos(30, 4000, 0));
        final NpcView mob = w.view().npc(m.objectId);
        final List<SkillView> skills = List.of(new SkillView(111, 1, SkillView.Kind.OTHER, 1, 0, true), new SkillView(3, 1, SkillView.Kind.STRIKE, 1, 40, true),
            new SkillView(254, 1, SkillView.Kind.OTHER, 1, 40, true));
        final Playstyles.Playstyle ps = new Playstyles.Playstyle("t", Set.of(0), null,
            List.of(line(111, Use.OPENER, EnumSet.of(Cond.PVP), 0, 0), line(254, Use.DEBUFF, EnumSet.of(Cond.NOT_SPOILED), 0, 1), line(3, Use.ROTATION, EnumSet.of(Cond.ALWAYS), 0, 2)));
        final PlaystyleEngine e = new PlaystyleEngine();
        final long now = 1000;
        assertEquals(254, e.pick(ps, self(w, 90, skills), mob, w.view(), Map.of(), now, false).skill().id(), "hunt: spoil before rotation");
        assertEquals(111, e.pick(ps, self(w, 90, skills), mob, w.view(), Map.of(), now, true).skill().id(), "olympiad: PVP opener, no spoil");
    }
}

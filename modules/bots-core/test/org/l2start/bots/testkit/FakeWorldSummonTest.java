package org.l2start.bots.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.ActionResult;
import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SkillView;

/** Phase 4c: the test world's summon, spoil and sweep, charges (what brain tests rely on). */
class FakeWorldSummonTest {
    @Test
    void summonNeedsCrystalsFightsAndGoes() {
        final FakeWorld w = new FakeWorld(1, new Pos(0, 0, 0), true);
        w.setLevel(20);
        w.teleport(new Pos(3000, 0, 0));
        w.addSkill(new FakeWorld.Extra(1111, SkillView.Kind.SUMMON, 20, 0, 1458, 2, 0));
        final Actuators a = w.actuators();
        assertEquals(ActionResult.INVALID, a.cast(1111, FakeWorld.SELF_ID), "no crystals");
        w.giveItem(1458, 4);
        assertEquals(ActionResult.OK, a.cast(1111, FakeWorld.SELF_ID));
        assertEquals(2, w.itemCount(1458));
        assertTrue(w.view().self().hasPet());
        assertEquals(ActionResult.INVALID, a.cast(1111, FakeWorld.SELF_ID), "one summon at a time");
        final FakeWorld.Mob m = w.addMob(5, 15, new Pos(3100, 0, 0));
        assertEquals(ActionResult.OK, a.petAttack(m.objectId));
        w.runUntil(2000);
        assertEquals(w.petId, m.targetId, "the monster fights the summon");
        assertTrue(w.view().npc(m.objectId).hpPct() < 100);
        assertTrue(!w.view().npc(m.objectId).engagedByOther(), "the bot's own summon is not somebody else");
        assertEquals(ActionResult.OK, a.unsummon());
        assertEquals(0, w.view().self().petId());
        assertEquals(0, m.targetId);
    }

    @Test
    void spoilSweepAndCharges() {
        final FakeWorld w = new FakeWorld(1, new Pos(0, 0, 0), false);
        w.setLevel(20);
        w.teleport(new Pos(3000, 0, 0));
        w.addSkill(FakeWorld.SPOIL_ID, SkillView.Kind.OTHER, 5, 0);
        w.addSkill(FakeWorld.SWEEPER_ID, SkillView.Kind.CORPSE, 5, 0);
        w.addSkill(8, SkillView.Kind.OTHER, 5, 0);
        final FakeWorld.Mob m = w.addMob(5, 1, new Pos(3050, 0, 0));
        final Actuators a = w.actuators();
        assertEquals(ActionResult.INVALID, a.cast(FakeWorld.SWEEPER_ID, m.objectId), "only on a body");
        assertEquals(ActionResult.OK, a.cast(FakeWorld.SPOIL_ID, m.objectId));
        assertTrue(w.view().npc(m.objectId).spoiled());
        assertEquals(ActionResult.OK, a.cast(8, m.objectId));
        assertEquals(1, w.view().self().charges());
        a.attack(m.objectId);
        w.runUntil(20_000);
        assertTrue(m.dead());
        assertEquals(ActionResult.OK, a.cast(FakeWorld.SWEEPER_ID, m.objectId));
        assertEquals(1, w.itemCount(FakeWorld.SWEEP_ITEM));
        assertEquals(ActionResult.INVALID, a.cast(FakeWorld.SWEEPER_ID, m.objectId), "swept once");
    }
}

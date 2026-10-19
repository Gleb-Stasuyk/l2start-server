package org.l2start.bots.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Activity;
import org.l2start.bots.api.Pos;
import org.l2start.bots.runtime.IdleWatch.Reaction;
import org.l2start.bots.runtime.IdleWatch.Verdict;

/** S3 (docs/STARTER_ZONES_PLAN.md): idle = no goal / no movement / an intentional state past its maximum. */
class IdleWatchTest {
    static final Pos AT = new Pos(-90954, 248118, -3570);

    static Verdict ticks(IdleWatch w, long from, int n, long step, Pos p, Activity a) {
        Verdict last = Verdict.OK;
        for (int i = 0; i < n; i++) {
            final Verdict v = w.check(from + (i * step), p, a);
            if (v != Verdict.OK) {
                last = v;
            }
        }
        return last;
    }

    @Test
    void wantsToMoveButStandsIsIdleAfterThreeTicks() {
        final IdleWatch w = new IdleWatch();
        final Activity walk = new Activity(Activity.Kind.MOVE, true, 0);
        assertEquals(Verdict.OK, w.check(0, AT, walk));
        assertEquals(Verdict.OK, w.check(3000, AT, walk));
        assertEquals(Verdict.OK, w.check(6000, AT, walk));
        assertEquals(Verdict.IDLE, w.check(9000, AT, walk), "3 ticks, 9 s without moving");
        assertEquals("not moving", w.why());
        // a second time soon after: idle again (the host teleports it when nobody sees it)
        assertEquals(Verdict.IDLE_AGAIN, ticks(w, 12_000, 5, 3000, AT, walk));
    }

    @Test
    void walkingIsNeverIdle() {
        final IdleWatch w = new IdleWatch();
        final Activity walk = new Activity(Activity.Kind.MOVE, true, 0);
        for (int i = 0; i < 100; i++) {
            assertEquals(Verdict.OK, w.check(i * 3000L, new Pos(AT.x() + (i * 60), AT.y(), AT.z()), walk));
        }
    }

    @Test
    void noGoalAtAllIsIdle() {
        assertEquals(Verdict.IDLE, ticks(new IdleWatch(), 0, 5, 3000, AT, new Activity(Activity.Kind.NONE, false, 0)));
    }

    @Test
    void intentionalStatesWithinTheirMaximumAreNotIdle() {
        final long min = 60_000;
        final Object[][] cases = { { Activity.Kind.AFK, 9 * min, 11 * min }, { Activity.Kind.REST, 2 * min, 4 * min }, { Activity.Kind.FIGHT, 90_000L, 3 * min },
            { Activity.Kind.TRANSIT, 10_000L, 20_000L }, { Activity.Kind.GROUP, 90_000L, 3 * min }, { Activity.Kind.TOWN, 5 * min, 7 * min } };
        for (Object[] c : cases) {
            final Activity.Kind k = (Activity.Kind) c[0];
            final Activity a = new Activity(k, false, 0);
            final IdleWatch w = new IdleWatch();
            assertEquals(Verdict.OK, ticks(w, 0, 20, ((long) c[1]) / 20, AT, a), k + " within its maximum");
            assertTrue(IdleWatch.standsOnPurpose(a, (long) c[1]), k.name());
            assertEquals(Verdict.IDLE, w.check((long) c[2], AT, a), k + " past its maximum");
            assertFalse(IdleWatch.standsOnPurpose(a, (long) c[2]), k.name());
        }
    }

    @Test
    void partyPostAndDeadNeverIdle() {
        for (Activity.Kind k : new Activity.Kind[] { Activity.Kind.PARTY, Activity.Kind.POST, Activity.Kind.DEAD }) {
            assertEquals(Verdict.OK, ticks(new IdleWatch(), 0, 200, 60_000, AT, new Activity(k, false, 0)), k.name());
        }
    }

    @Test
    void teleportOnlyWhenNoLivePlayerSeesIt() {
        assertEquals(Reaction.NONE, IdleWatch.react(Verdict.OK, true));
        assertEquals(Reaction.CHANGE, IdleWatch.react(Verdict.IDLE, true));
        assertEquals(Reaction.CHANGE, IdleWatch.react(Verdict.IDLE, false));
        assertEquals(Reaction.TELEPORT, IdleWatch.react(Verdict.IDLE_AGAIN, false));
        assertEquals(Reaction.RUN_TELEPORT_LATER, IdleWatch.react(Verdict.IDLE_AGAIN, true));
        // the waiting teleport: still seen = wait, unseen = now, ran off = dropped
        assertEquals(Boolean.FALSE, IdleWatch.pendingTeleport(AT, AT, true));
        assertEquals(Boolean.TRUE, IdleWatch.pendingTeleport(AT, new Pos(AT.x() + 100, AT.y(), AT.z()), false));
        assertNull(IdleWatch.pendingTeleport(AT, new Pos(AT.x() + 400, AT.y(), AT.z()), false));
    }
}

package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2start.platform.api.DropListener;
import org.l2start.platform.api.QuestRewardListener;

/** Hooks P7-DROP-FLOAT / P7-QUEST-REWARD: inert without listeners, stock rolls never re-enter a listener. */
class DropHooksTest {
    @Test
    void dropsStockRollsDoNotReenter() {
        assertNull(CoreHooks.drops(false, null, null, List::of)); // no listener
        final AtomicInteger calls = new AtomicInteger();
        final DropListener l = (spoil, v, k, roll) -> {
            calls.incrementAndGet();
            assertNull(DropHooks.drops(spoil, v, k, roll)); // nested calculateDrops: stock
            return List.of(new ItemHolder(57, 5));
        };
        DropHooks.addDrop("m", l);
        try {
            final List<ItemHolder> out = DropHooks.drops(true, null, null, List::of);
            assertEquals(1, out.size());
            out.clear(); // mutable for the stock caller
            assertEquals(1, calls.get());
            assertNull(CoreHooks.drops(false, null, null, List::of)); // null victim/killer never reach it
            assertEquals(1, calls.get());
        } finally {
            DropHooks.removeDrop("m", l);
        }
        assertEquals(0, DropHooks.size());
    }

    @Test
    void questRewardChain() {
        assertEquals(4, CoreHooks.questReward(null, 1458, 2, 4));
        final QuestRewardListener l = (p, id, base, rated) -> (id == 1458) ? base : rated;
        DropHooks.addQuest("m", l);
        try {
            assertEquals(2, CoreHooks.questReward(null, 1458, 2, 4));
            assertEquals(8, CoreHooks.questReward(null, 1864, 4, 8));
        } finally {
            DropHooks.removeQuest("m", l);
        }
        assertEquals(0, DropHooks.size());
    }
}

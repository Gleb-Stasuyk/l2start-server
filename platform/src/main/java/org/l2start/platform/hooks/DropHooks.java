package org.l2start.platform.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2start.platform.api.DropListener;
import org.l2start.platform.api.QuestRewardListener;

/** Listeners of hooks P7-DROP-FLOAT and P7-QUEST-REWARD, each owned by a module (added and removed by the platform). */
public final class DropHooks {
    private record OwnedDrop(String moduleId, DropListener listener) {
    }

    private record OwnedQuest(String moduleId, QuestRewardListener listener) {
    }

    private static final List<OwnedDrop> DROPS = new CopyOnWriteArrayList<>();
    private static final List<OwnedQuest> QUESTS = new CopyOnWriteArrayList<>();
    /** Set while a listener runs: its stock rolls (nested calculateDrops) stay stock. */
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private DropHooks() {
    }

    public static void addDrop(String moduleId, DropListener l) {
        DROPS.add(new OwnedDrop(moduleId, l));
    }

    public static void removeDrop(String moduleId, DropListener l) {
        DROPS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static void addQuest(String moduleId, QuestRewardListener l) {
        QUESTS.add(new OwnedQuest(moduleId, l));
    }

    public static void removeQuest(String moduleId, QuestRewardListener l) {
        QUESTS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return DROPS.size() + QUESTS.size();
    }

    /** First listener with an answer wins; null = stock. Mutable list (the stock caller clears it). */
    static List<ItemHolder> drops(boolean spoil, Creature victim, Creature killer, Supplier<List<ItemHolder>> stockRoll) {
        if (DROPS.isEmpty() || INSIDE.get()) {
            return null;
        }
        INSIDE.set(Boolean.TRUE);
        try {
            for (OwnedDrop o : DROPS) {
                final List<ItemHolder> out = o.listener().onDrops(spoil, victim, killer, stockRoll);
                if (out != null) {
                    return new ArrayList<>(out);
                }
            }
            return null;
        } finally {
            INSIDE.set(Boolean.FALSE);
        }
    }

    static long questReward(Player player, int itemId, long base, long rated) {
        long count = rated;
        for (OwnedQuest o : QUESTS) {
            count = o.listener().onQuestReward(player, itemId, base, count);
        }
        return count;
    }
}

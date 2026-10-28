package org.l2start.bots.brain.basic;

import java.util.Random;

import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;

/**
 * Phase 7b: accept or decline a duel ask (hook {@code P7-BOT-DUEL}). Pure rules: aggression, sociability, memory,
 * level gap. The brain answers a short while later; the duel itself is stock.
 */
public final class DuelDecision {
    public enum Answer {
        ACCEPT, DECLINE
    }

    private DuelDecision() {
    }

    /**
     * @param mem memory of the challenger (may be {@link Memory.Of#none})
     * @param myLevel bot level
     * @param theirLevel challenger level
     */
    public static Answer decide(BotInfo me, Memory.Of mem, int myLevel, int theirLevel, Random rnd) {
        if (mem.has(Memory.Tag.KILLED_ME) || mem.has(Memory.Tag.ANNOYED) || (mem.affinity() <= -40)) {
            return rnd.nextDouble() < 0.9 ? Answer.DECLINE : Answer.ACCEPT;
        }
        double p = 0.12 + (me.aggression() * 0.5) + (me.sociability() * 0.25);
        final int gap = myLevel - theirLevel;
        if (gap < -8) {
            p -= 0.25;
        } else if (gap < -3) {
            p -= 0.1;
        } else if (gap > 5) {
            p += 0.1;
        }
        if (mem.has(Memory.Tag.PARTY) || (mem.affinity() > 20)) {
            p += 0.15;
        }
        p = Math.max(0.05, Math.min(0.9, p));
        return rnd.nextDouble() < p ? Answer.ACCEPT : Answer.DECLINE;
    }
}

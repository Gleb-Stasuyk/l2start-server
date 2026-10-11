package org.l2start.bots.persona;

import java.util.Random;

/**
 * Character traits 0..1 (normal distribution around 0.5, clipped). Phase 3 uses skill (reaction time) and patience
 * (how long a bot rests); the rest are stored for later phases (chat, trade, PvP).
 */
public record Traits(double aggression, double sociability, double greed, double patience, double skill, double chattiness) {
    public static Traits random(Random rnd) {
        return new Traits(t(rnd), t(rnd), t(rnd), t(rnd), t(rnd), t(rnd));
    }

    private static double t(Random rnd) {
        final double v = 0.5 + (rnd.nextGaussian() * 0.18);
        return Math.round(Math.max(0, Math.min(1, v)) * 100) / 100.0;
    }

    /** Reaction time of this bot: skill 1 = 200 ms, skill 0 = 1200 ms. */
    public int reactionMs() {
        return (int) Math.round(1200 - (skill * 1000));
    }
}

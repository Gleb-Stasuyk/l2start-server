package org.l2start.bots.brain.basic;

import org.l2start.bots.api.Memory;

/**
 * Phase 7a: should the bot fight, flee, call help, or do nothing? Pure rules (no stock calls). Actual attacking is
 * phase 7b — here the brain only evaluates and shows the verdict in {@code //bots info}.
 */
public final class PvpDecision {
    /** Live players below this level are never chosen for first attack. */
    public static final int MIN_TARGET_LEVEL = 20;

    /** How aggressive bots are toward live players (initiative only; protect/revenge stay on when off). */
    public enum Mode {
        OFF, LOW, NORMAL, HIGH;

        public static Mode of(String s) {
            if (s == null || s.isBlank()) {
                return NORMAL;
            }
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off" -> OFF;
                case "low" -> LOW;
                case "high" -> HIGH;
                default -> NORMAL;
            };
        }
    }

    public enum Intent {
        NONE, FIGHT, FLEE, CALL_HELP
    }

    /**
     * @param inPvpZone bot is not in peace/town
     * @param targetInPvpZone target is not in peace/town
     * @param initiative true = considering a first attack (not defend/revenge)
     * @param attackedByThem they hit us recently
     * @param revengeActive grudge ({@link Memory.Tag#KILLED_ME}/{@link Memory.Tag#ATTACKED_ME}) still in timeout
     * @param allyUnderAttack a party/clan mate is being hit by them
     * @param targetLive real player (not a bot)
     * @param cooldownBlocks initiative cooldown toward this live player
     */
    public record Situation(boolean inPvpZone, boolean targetInPvpZone, boolean initiative, boolean attackedByThem, boolean revengeActive,
        boolean allyUnderAttack, boolean targetLive, boolean protectPartyOn, boolean protectClanOn, boolean revengeOn, Mode mode, int myLevel,
        int theirLevel, boolean meMage, boolean themMage, int enemiesNear, int alliesNear, double hpPct, double mpPct, double aggression,
        boolean cooldownBlocks, boolean targetUnder20) {
    }

    public record Verdict(Intent intent, String why) {
        public static Verdict none(String why) {
            return new Verdict(Intent.NONE, why);
        }
    }

    private PvpDecision() {
    }

    /** Active grudge: tag present and {@code lastSeen} within {@code timeoutMs} (0 = never expires). */
    public static boolean grudgeActive(Memory.Of mem, long now, long timeoutMs) {
        if (mem == null || (!mem.has(Memory.Tag.KILLED_ME) && !mem.has(Memory.Tag.ATTACKED_ME))) {
            return false;
        }
        if (timeoutMs <= 0) {
            return true;
        }
        if (mem.lastSeen() <= 0) {
            return true;
        }
        return (now - mem.lastSeen()) < timeoutMs;
    }

    public static Verdict decide(Situation s) {
        if (!s.inPvpZone() || !s.targetInPvpZone()) {
            return Verdict.none("peace");
        }
        final boolean defensive = s.attackedByThem() || (s.revengeActive() && s.revengeOn())
            || (s.allyUnderAttack() && (s.protectPartyOn() || s.protectClanOn()));
        if (s.initiative() && !defensive) {
            return initiative(s);
        }
        if (!defensive) {
            return Verdict.none("no threat");
        }
        return defensive(s);
    }

    private static Verdict initiative(Situation s) {
        if (s.targetLive() && (s.mode() == Mode.OFF)) {
            return Verdict.none("off");
        }
        if (s.targetUnder20() || (s.theirLevel() < MIN_TARGET_LEVEL)) {
            return Verdict.none("under20");
        }
        if (s.targetLive() && s.cooldownBlocks()) {
            return Verdict.none("cooldown");
        }
        if (s.hpPct() < 40 || (s.meMage() && s.mpPct() < 20)) {
            return Verdict.none("hurt");
        }
        final int gap = s.myLevel() - s.theirLevel();
        final int power = s.alliesNear() - s.enemiesNear();
        // need clear advantage: level and/or numbers
        double need = switch (s.mode()) {
            case LOW -> 0.75;
            case HIGH -> 0.35;
            default -> 0.55;
        };
        need -= (s.aggression() - 0.5) * 0.3;
        need -= Math.max(-0.25, Math.min(0.25, gap * 0.04));
        need -= Math.max(-0.2, Math.min(0.2, power * 0.15));
        if (s.themMage() && !s.meMage()) {
            need -= 0.05; // melee vs mage: slight edge to open
        }
        if (power < 0 || gap < -3) {
            return Verdict.none("no edge");
        }
        if (need > 0.7) {
            return Verdict.none("cautious");
        }
        return new Verdict(Intent.FIGHT, "initiative");
    }

    private static Verdict defensive(Situation s) {
        final int gap = s.myLevel() - s.theirLevel();
        final int alone = s.enemiesNear() - s.alliesNear();
        if (s.hpPct() < 25) {
            return new Verdict(Intent.FLEE, "low hp");
        }
        if (alone >= 2 && s.alliesNear() == 0) {
            return s.aggression() > 0.7 ? new Verdict(Intent.FLEE, "outnumbered") : new Verdict(Intent.CALL_HELP, "outnumbered");
        }
        if (alone >= 1 && s.alliesNear() > 0 && s.hpPct() < 55) {
            return new Verdict(Intent.CALL_HELP, "need help");
        }
        if (gap <= -8 && s.aggression() < 0.6) {
            return new Verdict(Intent.FLEE, "stronger foe");
        }
        if (s.revengeActive() && s.revengeOn()) {
            return new Verdict(Intent.FIGHT, "revenge");
        }
        if (s.allyUnderAttack()) {
            return new Verdict(Intent.FIGHT, "protect");
        }
        if (s.attackedByThem()) {
            // fight back unless badly overmatched
            if (alone >= 2 || (gap <= -6 && s.hpPct() < 50)) {
                return new Verdict(Intent.FLEE, "attacked weak");
            }
            return new Verdict(Intent.FIGHT, "attacked");
        }
        return Verdict.none("no threat");
    }
}

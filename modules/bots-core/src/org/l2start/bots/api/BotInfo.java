package org.l2start.bots.api;

/**
 * Who the bot is (from its persona). Traits are 0..1.
 * @param home where the bot was created (its starting village)
 * @param reactionMs reaction time of the persona (200..1200 ms)
 */
public record BotInfo(int botId, int charId, String name, int classId, boolean female, String lang, double aggression,
    double sociability, double greed, double patience, double skill, double chattiness, int reactionMs, Pos home, long seed) {
}

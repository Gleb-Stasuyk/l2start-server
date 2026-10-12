package org.l2start.bots.chat;

import java.util.List;
import java.util.Map;

/**
 * Phase 6a: one request to a {@link ChatBrain} (ARCHITECTURE.md 8.7). The template brain uses intent, language and
 * variables; an LLM brain (phase 10) also the persona, the memory of the other player and the last lines.
 * @param intent what to say (PARTY_HELLO, DECLINE_SOLO, ...), decided by the bot's brain, never by the text
 * @param channel PARTY, WHISPER, ALL, SHOUT, TRADE
 * @param lang RU, EN or MIX
 * @param chattiness persona trait 0..1
 * @param memory short summary of what the bot remembers of the other player ("" = nothing)
 * @param history the last lines of the conversation (at most 6), oldest first
 * @param vars {player}, {spot}, {level}, {town} ...
 */
public record ChatRequest(int botId, String intent, String channel, String lang, double chattiness, String memory, List<String> history, Map<String, String> vars) {
}

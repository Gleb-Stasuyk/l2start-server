package org.l2start.bots.chat;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 6a: who words what a bot says (ARCHITECTURE.md 8.7). {@link TemplateBrain} (phrase banks) is always there;
 * the LLM module of phase 10 registers its own and falls back to the templates on a timeout. Decisions (join a party,
 * trade) are made by the bot's brain by its rules, never by a reply text: no prompt injection can make a bot act.
 */
public interface ChatBrain {
    CompletableFuture<Optional<String>> reply(ChatRequest r);
}

package org.l2start.bots.api;

import java.util.List;
import java.util.Map;

/**
 * Phase 6a: what a bot says, from the phrase banks of bots-core (data/chat/phrases.xml) in its persona's language
 * and style. The brain decides WHAT happens (accept, decline, follow); the chat only words it. Lines are already
 * "humanized" (lower case, no full stop, now and then a typo and its "*fix" as a second line).
 */
public interface Chat {
    /** Nothing to say (no phrase banks): every intent is silent. */
    Chat SILENT = new Chat() {
        @Override
        public List<String> lines(String intent, Map<String, String> vars) {
            return List.of();
        }

        @Override
        public long typingMs(String text) {
            return 2000;
        }
    };

    /**
     * A line for this intent ({@code PARTY_HELLO}, {@code DECLINE_SOLO}, ...) with {@code {player}}-style variables
     * filled in; empty = nothing to say (no such intent, or the same line was used in the last 10 minutes and there is
     * no other). Usually one line, two with a typo and its correction. Uses the persona's language.
     */
    List<String> lines(String intent, Map<String, String> vars);

    /**
     * Same as {@link #lines(String, Map)} but forces the phrase language ({@code RU}/{@code EN}/{@code MIX}).
     * Phase 6e: whisper replies follow the player's language when the persona knows it. Default ignores {@code lang}.
     */
    default List<String> lines(String intent, Map<String, String> vars, String lang) {
        return lines(intent, vars);
    }

    /** How long a person of this persona types this text (ms): reaction + length, +-25 %. */
    long typingMs(String text);
}

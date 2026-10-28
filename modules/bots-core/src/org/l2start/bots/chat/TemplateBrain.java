package org.l2start.bots.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Phase 6a: the phrase-bank {@link ChatBrain}: a random line of the intent in the persona's language, not the same
 * line of the same bot twice within {@link #REPEAT_MS} when there is another one.
 */
public final class TemplateBrain implements ChatBrain {
    public static final long REPEAT_MS = 10 * 60_000L;

    private final Phrases phrases;
    private final LongSupplier clock;
    private final Map<String, Long> used = new HashMap<>();
    private final Random rnd;

    public TemplateBrain(Phrases phrases, LongSupplier clock, Random rnd) {
        this.phrases = phrases;
        this.clock = clock;
        this.rnd = rnd;
    }

    public Phrases phrases() {
        return phrases;
    }

    @Override
    public CompletableFuture<Optional<String>> reply(ChatRequest r) {
        return CompletableFuture.completedFuture(pick(r));
    }

    /** The same as {@link #reply}, right away. */
    public synchronized Optional<String> pick(ChatRequest r) {
        final String lang = Phrases.langOf(r.lang(), rnd);
        List<String> lines = phrases.lines(r.intent(), lang);
        if (lines.isEmpty()) {
            lines = phrases.lines(r.intent(), "RU".equals(lang) ? "EN" : "RU");
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        final long now = clock.getAsLong();
        if (used.size() > 4096) {
            used.values().removeIf(t -> (now - t) > REPEAT_MS);
        }
        final int start = rnd.nextInt(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            final String l = lines.get((start + i) % lines.size());
            if (!Phrases.usable(l, r.vars())) {
                continue; // "ищу пати на {spot}" without a place: another variant
            }
            final String key = r.botId() + "|" + l;
            final Long t = used.get(key);
            if ((t == null) || ((now - t) >= REPEAT_MS)) {
                used.put(key, now);
                return Optional.of(Phrases.fill(l, r.vars()));
            }
        }
        return Optional.empty();
    }
}

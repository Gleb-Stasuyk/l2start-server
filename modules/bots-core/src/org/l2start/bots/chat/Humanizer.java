package org.l2start.bots.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 6a: a line written like a person in a game chat writes it (Living Worlds typingDelayMillis): typing time by
 * length, mostly lower case without a full stop, a rare typo fixed with "*word" in the next line.
 */
public final class Humanizer {
    /** About 5-6 characters a second plus thinking. */
    static final long PER_CHAR_MS = 170;
    public static final long MIN_TYPING_MS = 1200;
    public static final long MAX_TYPING_MS = 9000;

    private Humanizer() {
    }

    public static long typingMs(String text, int reactionMs, Random rnd) {
        final double base = reactionMs + 600 + (text.length() * PER_CHAR_MS);
        final double jitter = 0.75 + (rnd.nextDouble() * 0.5); // +-25 %
        return Math.max(MIN_TYPING_MS, Math.min(MAX_TYPING_MS, Math.round(base * jitter)));
    }

    /**
     * @param care 0..1: a careful writer (high skill, low chattiness) keeps capitals and makes fewer typos
     * @return one line, or a line with a typo and the "*fix" line
     */
    public static List<String> style(String text, double care, Random rnd) {
        String s = text;
        if ((s.length() > 1) && (rnd.nextDouble() > (0.3 * care))) {
            s = Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
        if (s.endsWith(".") && !s.endsWith("..") && (rnd.nextDouble() > (0.4 * care))) {
            s = s.substring(0, s.length() - 1);
        }
        final List<String> out = new ArrayList<>(2);
        if (rnd.nextDouble() < (0.05 * (1.2 - care))) {
            final String[] words = s.split(" ");
            for (int i = 0; i < words.length; i++) {
                final String w = words[i];
                if ((w.length() >= 4) && w.chars().allMatch(Character::isLetter)) {
                    final int k = 1 + rnd.nextInt(w.length() - 2);
                    words[i] = w.substring(0, k) + w.charAt(k + 1) + w.charAt(k) + w.substring(k + 2);
                    out.add(String.join(" ", words));
                    out.add("*" + w);
                    return out;
                }
            }
        }
        out.add(s);
        return out;
    }
}

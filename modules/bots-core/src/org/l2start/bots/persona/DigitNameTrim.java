package org.l2start.bots.persona;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure helpers: keep digit nicknames at about {@code digitShare} of the pool; pick who to rename.
 */
public final class DigitNameTrim {
    private DigitNameTrim() {
    }

    /** How many digit-names to keep so keep/total ≈ share (ceil, at least 0). */
    public static int keepDigitCount(int totalBots, int digitBots, double share) {
        if ((totalBots <= 0) || (digitBots <= 0)) {
            return 0;
        }
        final int want = (int) Math.round(Math.max(0, Math.min(1, share)) * totalBots);
        return Math.min(digitBots, Math.max(0, want));
    }

    /**
     * Bot ids among {@code digitBotIds} that should be renamed (all but {@code keep}).
     * Stable order by botId; first {@code keep} stay.
     */
    public static List<Integer> toRename(List<Integer> digitBotIds, int keep) {
        final List<Integer> sorted = new ArrayList<>(digitBotIds);
        sorted.sort(Integer::compareTo);
        final int k = Math.max(0, Math.min(keep, sorted.size()));
        return new ArrayList<>(sorted.subList(k, sorted.size()));
    }

    /** Generate a free name without digits. */
    public static String newNameWithoutDigits(NameGenerator gen, Predicate<String> taken, boolean female, Random rnd) {
        for (int i = 0; i < 80; i++) {
            final NameGenerator.Style style = gen.pickStyle();
            final String name = gen.generate(style);
            if (NameGenerator.hasDigit(name)) {
                continue;
            }
            if (gen.isValid(name) && !taken.test(name)) {
                return name;
            }
        }
        // force fantasy / no-digit path via taken that rejects digits
        return gen.next(n -> taken.test(n) || NameGenerator.hasDigit(n), female).name();
    }

    public static Set<String> takenLower(Iterable<String> names) {
        final Set<String> out = new HashSet<>();
        for (String n : names) {
            if (n != null) {
                out.add(n.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}

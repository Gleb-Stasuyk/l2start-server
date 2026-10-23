package org.l2start.bots.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Human names of the places bots talk about (world.xml grounds: {@code name} / {@code nameEn}; towns: their name). A
 * chat line never carries a place id: {@link #say} turns an unknown id into plain words at worst.
 */
public final class PlaceNames {
    /** A hunting ground the bots may name in chat. */
    public record Ground(String id, String ru, String en, Pos center, int minLevel, int maxLevel) {
    }

    private static final Map<String, String[]> NAMES = new ConcurrentHashMap<>();
    private static volatile List<Ground> grounds = List.of();

    private PlaceNames() {
    }

    /** Set by the world catalogue when it loads. */
    public static void register(Map<String, String[]> names, List<Ground> hunting) {
        NAMES.clear();
        NAMES.putAll(names);
        grounds = List.copyOf(hunting);
    }

    public static List<Ground> grounds() {
        return grounds;
    }

    /** What a bot says for a place id in its language ("ru" / "en"); never an id with underscores. */
    public static String say(String id, String lang) {
        if ((id == null) || id.isBlank()) {
            return "";
        }
        final String[] n = NAMES.get(id);
        if (n != null) {
            return (lang != null) && lang.toLowerCase(Locale.ROOT).startsWith("en") && (n.length > 1) && !n[1].isBlank() ? n[1] : n[0];
        }
        return humanize(id);
    }

    /** "fieldofwhispers_3" / "town_of_goddard" -> "fieldofwhispers" / "town of goddard": the last resort, no ids. */
    public static String humanize(String id) {
        return id.replaceAll("_\\d+$", "").replace('_', ' ').trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A hunting ground fitting this level for a party search, nearest first (one of the nearest three); null = none.
     * Never a town: only grounds are listed.
     */
    public static Ground forLevel(int level, Pos from, java.util.Random rnd) {
        final List<Ground> fit = new ArrayList<>();
        for (Ground g : grounds) {
            if ((level >= (g.minLevel() - 2)) && (level <= (g.maxLevel() + 3))) {
                fit.add(g);
            }
        }
        if (fit.isEmpty()) {
            return null;
        }
        fit.sort(Comparator.comparingDouble(g -> g.center().distance2d(from)));
        return fit.get(rnd.nextInt(Math.min(3, fit.size())));
    }
}

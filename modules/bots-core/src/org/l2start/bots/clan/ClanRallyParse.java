package org.l2start.bots.clan;

import java.util.Locale;

/** Phase 7e: detect clan-chat rally / gather lines (RU+EN). Pure. */
public final class ClanRallyParse {
    private ClanRallyParse() {
    }

    public static boolean isRally(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        final String t = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return t.contains("сбор") || t.contains("rally") || t.contains("gather") || t.contains("на рб") || t.contains("на спот")
            || t.contains("go rb") || t.contains("to spot") || t.contains("всем на");
    }

    public static String kind(String text) {
        if (text == null) {
            return "spot";
        }
        final String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("рб") || t.contains("rb") || t.contains("raid")) {
            return "rb";
        }
        return "spot";
    }
}

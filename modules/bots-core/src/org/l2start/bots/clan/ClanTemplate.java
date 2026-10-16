package org.l2start.bots.clan;

/** One row from {@code data/clans/BotClans.xml}. */
public record ClanTemplate(String id, String name, int level, long crestSeed, boolean large) {
    public ClanTemplate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("clan template: no id");
        }
        if (name == null || name.isBlank() || name.length() > 16) {
            throw new IllegalArgumentException("clan template: bad name " + name);
        }
        if (level < 2 || level > 8) {
            throw new IllegalArgumentException("clan template: level " + level + " not in 2..8");
        }
        if (large && level != 8) {
            throw new IllegalArgumentException("clan template: large requires level 8");
        }
    }

    /** Before phase 7e (no large flag). */
    public ClanTemplate(String id, String name, int level, long crestSeed) {
        this(id, name, level, crestSeed, false);
    }
}

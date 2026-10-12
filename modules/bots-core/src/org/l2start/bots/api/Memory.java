package org.l2start.bots.api;

import java.util.Set;

/**
 * Phase 6a: what a bot's persona remembers of real players (table mod_bots_core_memory, written in batches once a
 * minute and when the bot leaves). Only the bot's own shard thread uses it.
 */
public interface Memory {
    /** Tags a memory can carry. */
    enum Tag {
        PARTY, REZZED_ME, I_REZZED, BUFFED_ME, TRADED, DECLINED, ANNOYED, KILLED_ME
    }

    /**
     * @param affinity -100..100 (liking)
     * @param met how many times they met (a party, a refusal, a trade...)
     * @param partyMinutes minutes played together in a party
     * @param lastSeen ms
     */
    record Of(int charId, int affinity, int met, int partyMinutes, long lastSeen, Set<Tag> tags) {
        public static Of none(int charId) {
            return new Of(charId, 0, 0, 0, 0, Set.of());
        }

        public boolean has(Tag t) {
            return tags.contains(t);
        }
    }

    /** Never null: an empty memory for a stranger. */
    Of of(int charId);

    /** Something happened with this player: affinity changes (clamped), the tag is added (null = none), met +1 when asked. */
    void note(int charId, int affinityDelta, Tag tag, boolean met);

    /** Played in a party with this player for so many more minutes. */
    void partyMinutes(int charId, int minutes);

    /** Forget a tag (DECLINED after a good party, ANNOYED after a while). */
    void forget(int charId, Tag tag);

    /** A memory of nothing (tests, no database). */
    static Memory inMemory() {
        return new MapMemory();
    }

    /** Simple map-backed memory. */
    class MapMemory implements Memory {
        private final java.util.Map<Integer, Of> m = new java.util.HashMap<>();
        private final java.util.function.LongSupplier clock;

        public MapMemory() {
            this(System::currentTimeMillis);
        }

        public MapMemory(java.util.function.LongSupplier clock) {
            this.clock = clock;
        }

        @Override
        public Of of(int charId) {
            return m.getOrDefault(charId, Of.none(charId));
        }

        @Override
        public void note(int charId, int affinityDelta, Tag tag, boolean met) {
            final Of o = of(charId);
            final java.util.EnumSet<Tag> t = o.tags().isEmpty() ? java.util.EnumSet.noneOf(Tag.class) : java.util.EnumSet.copyOf(o.tags());
            if (tag != null) {
                t.add(tag);
            }
            put(new Of(charId, Math.max(-100, Math.min(100, o.affinity() + affinityDelta)), o.met() + (met ? 1 : 0), o.partyMinutes(), clock.getAsLong(), java.util.Set.copyOf(t)));
        }

        @Override
        public void partyMinutes(int charId, int minutes) {
            final Of o = of(charId);
            put(new Of(charId, o.affinity(), o.met(), o.partyMinutes() + minutes, clock.getAsLong(), o.tags()));
        }

        @Override
        public void forget(int charId, Tag tag) {
            final Of o = of(charId);
            if (o.has(tag)) {
                final java.util.EnumSet<Tag> t = java.util.EnumSet.copyOf(o.tags());
                t.remove(tag);
                put(new Of(charId, o.affinity(), o.met(), o.partyMinutes(), o.lastSeen(), java.util.Set.copyOf(t)));
            }
        }

        /** Hook for subclasses that persist (dirty marking). */
        protected void put(Of o) {
            m.put(o.charId(), o);
        }

        /** Everything remembered. */
        public java.util.Collection<Of> all() {
            return m.values();
        }

        /** Loading from the database. */
        public void load(Of o) {
            m.put(o.charId(), o);
        }
    }
}

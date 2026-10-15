package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Memory;

/** Phase 6a/6f: memory rows without a database (tags text both ways, dirty marking, affinity decay). */
class MemoryStoreTest {
    @Test
    void tagsRoundTripAndDirtyRows() throws Exception {
        assertEquals("PARTY,REZZED_ME,ANNOYED", MemoryStore.tagText(Set.of(Memory.Tag.ANNOYED, Memory.Tag.PARTY, Memory.Tag.REZZED_ME)));
        assertEquals(Set.of(Memory.Tag.PARTY, Memory.Tag.ANNOYED), MemoryStore.tags("PARTY, ANNOYED,FUTURE_TAG,"));
        assertEquals(Set.of(), MemoryStore.tags(null));
        final MemoryStore s = new MemoryStore(null);
        final MemoryStore.BotMemory m = s.of(4);
        m.note(100, 5, Memory.Tag.PARTY, true);
        m.partyMinutes(100, 3);
        assertEquals(Set.of(100), m.dirty);
        assertEquals(1, s.summary(4).size());
        assertEquals(0, s.flush(), "no database: nothing written");
    }

    @Test
    void affinityCoolsOnePerWeekWithoutMeeting() {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final Memory.MapMemory m = new Memory.MapMemory(now::get);
        m.note(7, 10, Memory.Tag.PARTY, true);
        assertEquals(10, m.of(7).affinity());
        now.addAndGet(Memory.WEEK_MS); // one full week
        assertEquals(9, m.of(7).affinity());
        now.addAndGet(Memory.WEEK_MS * 3); // three more weeks
        assertEquals(6, m.of(7).affinity());
        // already cooled for those weeks: same clock again does not double-decay
        assertEquals(6, m.of(7).affinity());
        assertEquals(0, Memory.Of.none(1).cooled(now.get()).affinity());
    }
}

package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Memory;

/** Phase 6a: memory rows without a database (tags text both ways, dirty marking). */
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
}

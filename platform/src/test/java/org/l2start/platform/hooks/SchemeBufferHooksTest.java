package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.platform.api.SchemeBufferBuffListener;

/** P11-BUFFER-TIME is inert without a listener and does not run for a null caster or target. */
class SchemeBufferHooksTest {
    @Test
    void inertWithoutListenersAndNulls() {
        final List<Integer> heard = new ArrayList<>();
        final SchemeBufferBuffListener l = (buffer, target, skillId) -> heard.add(skillId);
        CoreHooks.schemeBufferBuff(null, null, 1204);
        assertEquals(0, heard.size());
        SchemeBufferHooks.add("stock-npcs", l);
        try {
            assertEquals(1, SchemeBufferHooks.size());
            CoreHooks.schemeBufferBuff(null, null, 1204);
            assertEquals(0, heard.size(), "null caster/target never reaches the module");
            SchemeBufferHooks.remove("other", l);
            assertEquals(1, SchemeBufferHooks.size());
        } finally {
            SchemeBufferHooks.remove("stock-npcs", l);
        }
        assertEquals(0, SchemeBufferHooks.size());
        CoreHooks.schemeBufferBuff(null, null, 1204);
        assertEquals(0, heard.size());
    }
}

package org.l2start.modules.chbuff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Long clan hall buffs: only listed skills, level <= MaxLevel at cast, bots by switch. */
class ChBuffDurationTest {
    private static final Set<Integer> SKILLS = ChBuffDurationModule.parseSkills(List.of("4342-4360"));

    @Test
    void parsesRangesAndIds() {
        assertEquals(19, SKILLS.size());
        assertTrue(SKILLS.contains(4342));
        assertTrue(SKILLS.contains(4360));
        assertFalse(SKILLS.contains(4361));
        assertEquals(Set.of(1, 2, 3, 7), ChBuffDurationModule.parseSkills(List.of("3-1", " 7 ", "x", "")));
    }

    @Test
    void levelSkillAndBots() {
        assertTrue(ChBuffDurationModule.applies(SKILLS, 39, true, 4342, 1, false));
        assertTrue(ChBuffDurationModule.applies(SKILLS, 39, true, 4357, 39, false));
        assertFalse(ChBuffDurationModule.applies(SKILLS, 39, true, 4357, 40, false), "40+: stock");
        assertFalse(ChBuffDurationModule.applies(SKILLS, 39, true, 1204, 20, false), "not a CH skill");
        assertTrue(ChBuffDurationModule.applies(SKILLS, 39, true, 4342, 20, true), "bots included");
        assertFalse(ChBuffDurationModule.applies(SKILLS, 39, false, 4342, 20, true), "bots excluded");
        assertFalse(ChBuffDurationModule.applies(SKILLS, 0, true, 4342, 20, false), "0 = off");
    }
}
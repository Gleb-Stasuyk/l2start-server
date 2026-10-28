package org.l2start.modules.returnskill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Live players auto-learn up to AutoLearnMaxLevel; bots keep their own skill handling. */
class AutoLearnTest {
    @Test
    void liveUpToNineteenOnly() {
        assertTrue(ReturnSkillModule.autoLearns(false, 1, 19));
        assertTrue(ReturnSkillModule.autoLearns(false, 19, 19));
        assertFalse(ReturnSkillModule.autoLearns(false, 20, 19));
        assertFalse(ReturnSkillModule.autoLearns(true, 5, 19), "bots: never");
        assertFalse(ReturnSkillModule.autoLearns(false, 5, 0), "0 = off");
    }
}

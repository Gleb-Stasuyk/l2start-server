package org.l2start.bots.persona;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.chat.LfpParse;

/** KT12 F2: five inviteable buffer roles are reachable and match LFP. */
class SupportRolesTest {
    @Test
    void fiveRolesCoverSecondAndThird() {
        assertTrue(SupportRoles.isPartyBuffer(34)); // BD
        assertTrue(SupportRoles.isPartyBuffer(107)); // Spectral Dancer
        assertTrue(SupportRoles.isPartyBuffer(21)); // SWS
        assertTrue(SupportRoles.isPartyBuffer(17)); // PP
        assertTrue(SupportRoles.isPartyBuffer(43)); // SE
        assertTrue(SupportRoles.isPartyBuffer(16)); // BP
        assertFalse(SupportRoles.isPartyBuffer(88)); // Duelist
    }

    @Test
    void futureBiasRaisesBufferShareForHumanMystic() {
        final Random rnd = new Random(42);
        final Map<Integer, Integer> got = new HashMap<>();
        final int n = 5000;
        for (int i = 0; i < n; i++) {
            got.merge(ClassPicker.futureClass(10, rnd), 1, Integer::sum);
        }
        final int buffers = got.getOrDefault(16, 0) + got.getOrDefault(17, 0);
        // 2 of 5 futures are buffers; with 40% bias expected ≈ 0.4 + 0.6*(2/5) = 0.64
        assertTrue(buffers > n * 0.50, "buffer futures too rare: " + buffers + "/" + n + " " + got);
    }

    @Test
    void lfpFitsBdSwsPpSeBp() {
        assertTrue(LfpParse.fits(LfpParse.Role.BUFF, 34));
        assertTrue(LfpParse.fits(LfpParse.Role.BUFF, 21));
        assertTrue(LfpParse.fits(LfpParse.Role.BUFF, 17));
        assertTrue(LfpParse.fits(LfpParse.Role.HEAL, 43));
        assertTrue(LfpParse.fits(LfpParse.Role.HEAL, 16));
        assertTrue(LfpParse.parse("LFP bd 40").role() == LfpParse.Role.BUFF);
        assertTrue(LfpParse.parse("ищу пати sws 55").role() == LfpParse.Role.BUFF);
        assertTrue(LfpParse.parse("lfp prophet").role() == LfpParse.Role.BUFF);
        assertTrue(LfpParse.parse("нужен хил se").role() == LfpParse.Role.HEAL);
    }
}

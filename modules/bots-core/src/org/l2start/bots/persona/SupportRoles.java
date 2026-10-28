package org.l2start.bots.persona;

import java.util.Set;

/**
 * KT12 F2: the five party-buffer professions a live player can invite (2nd and 3rd Interlude class ids).
 * Director personas bias toward these; LFP HEAL/BUFF already match via {@link org.l2start.bots.chat.LfpParse}.
 */
public final class SupportRoles {
    /** Bishop (16) / Cardinal (97). */
    public static final Set<Integer> BISHOP = Set.of(16, 97);
    /** Prophet (17) / Hierophant (98). */
    public static final Set<Integer> PROPHET = Set.of(17, 98);
    /** Swordsinger (21) / Sword Muse (100). */
    public static final Set<Integer> SWORDSINGER = Set.of(21, 100);
    /** Bladedancer (34) / Spectral Dancer (107). */
    public static final Set<Integer> BLADEDANCER = Set.of(34, 107);
    /** Shillien Elder (43) / Shillien Saint (112). */
    public static final Set<Integer> SHILLIEN_ELDER = Set.of(43, 112);

    /** Planned 2nd professions used when rolling {@link ClassPicker#futureClass}. */
    public static final Set<Integer> PARTY_BUFFER_2ND = Set.of(16, 17, 21, 34, 43);

    /** Any tier of the five roles. */
    public static final Set<Integer> ALL = Set.of(16, 17, 21, 34, 43, 97, 98, 100, 107, 112);

    private SupportRoles() {
    }

    /** True when this class id is one of BD / SWS / PP / SE / BP (any evolution). */
    public static boolean isPartyBuffer(int classId) {
        return ALL.contains(classId);
    }

    /** Chance to pick a party-buffer 2nd profession when the starting class can reach one. */
    public static final double FUTURE_BIAS = 0.40;
}

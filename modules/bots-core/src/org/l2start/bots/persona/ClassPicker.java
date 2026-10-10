package org.l2start.bots.persona;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Starting class, sex and look of a new bot. Phase 3 bots start at level 1 like players, so only the 9 starting
 * classes. Shares from the approved plan (many dwarves/spoilers, fewer orc mystics), editable later via config.
 */
public final class ClassPicker {
    /** classId -> share in percent. */
    public static final Map<Integer, Integer> DEFAULT_WEIGHTS;
    static {
        final Map<Integer, Integer> m = new TreeMap<>();
        m.put(0, 15); // Human Fighter
        m.put(10, 12); // Human Mystic
        m.put(18, 9); // Elven Fighter
        m.put(25, 10); // Elven Mystic
        m.put(31, 11); // Dark Fighter
        m.put(38, 10); // Dark Mystic
        m.put(44, 9); // Orc Fighter
        m.put(49, 6); // Orc Mystic
        m.put(53, 18); // Dwarven Fighter
        DEFAULT_WEIGHTS = Map.copyOf(m);
    }

    private static final java.util.Set<Integer> MYSTICS = java.util.Set.of(10, 25, 38, 49);

    public record Look(int classId, boolean female, byte face, byte hairStyle, byte hairColor) {
    }

    private final Random rnd;
    private final int[] ids;
    private final int[] cumulative;

    public ClassPicker(Random rnd, Map<Integer, Integer> weights) {
        this.rnd = rnd;
        final TreeMap<Integer, Integer> sorted = new TreeMap<>(weights);
        ids = new int[sorted.size()];
        cumulative = new int[sorted.size()];
        int i = 0;
        int sum = 0;
        for (Map.Entry<Integer, Integer> e : sorted.entrySet()) {
            if (!DEFAULT_WEIGHTS.containsKey(e.getKey())) {
                throw new IllegalArgumentException("class " + e.getKey() + " is not a starting class");
            }
            sum += Math.max(0, e.getValue());
            ids[i] = e.getKey();
            cumulative[i++] = sum;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("all class weights are 0");
        }
    }

    public ClassPicker(Random rnd) {
        this(rnd, DEFAULT_WEIGHTS);
    }

    public int pickClass() {
        final int r = rnd.nextInt(cumulative[cumulative.length - 1]);
        for (int i = 0; i < ids.length; i++) {
            if (r < cumulative[i]) {
                return ids[i];
            }
        }
        return ids[ids.length - 1];
    }

    /** Sex and look within the stock character creation limits (face 0-2, hair color 0-3, hair style 0-4 male / 0-6 female). */
    public Look look(int classId) {
        final int femalePercent = classId == 53 ? 30 : MYSTICS.contains(classId) ? 50 : 35;
        final boolean female = rnd.nextInt(100) < femalePercent;
        return new Look(classId, female, (byte) rnd.nextInt(3), (byte) rnd.nextInt(female ? 7 : 5), (byte) rnd.nextInt(4));
    }

    public static boolean isStartingClass(int classId) {
        return DEFAULT_WEIGHTS.containsKey(classId);
    }
}

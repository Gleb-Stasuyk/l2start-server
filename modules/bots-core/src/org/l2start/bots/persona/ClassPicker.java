package org.l2start.bots.persona;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Starting class, sex and look of a new bot. Phase 3 bots start at level 1 like players, so only the 9 starting
 * classes. Shares from the approved plan (many dwarves/spoilers, fewer orc mystics); config key ClassWeights.
 * Looks stay inside the stock creation limits; config keys FaceWeights, HairColorWeights, HairStyleWeightsMale/Female.
 */
public final class ClassPicker {
    public static final List<String> DEFAULT_CLASSES = List.of("0:15", "10:12", "18:9", "25:10", "31:11", "38:10", "44:9", "49:6", "53:18");
    public static final List<String> DEFAULT_FACE = List.of("4", "3", "3");
    public static final List<String> DEFAULT_HAIR_COLOR = List.of("4", "3", "2", "2");
    public static final List<String> DEFAULT_HAIR_MALE = List.of("3", "3", "2", "2", "1");
    public static final List<String> DEFAULT_HAIR_FEMALE = List.of("3", "3", "2", "2", "2", "1", "1");

    /** classId -> share in percent (the approved defaults). */
    public static final Map<Integer, Integer> DEFAULT_WEIGHTS = Map.of(0, 15, 10, 12, 18, 9, 25, 10, 31, 11, 38, 10, 44, 9, 49, 6, 53, 18);
    private static final Set<Integer> MYSTICS = Set.of(10, 25, 38, 49);

    /** Second profession a starting class can reach (stored in the persona now, used from phase 4). */
    public static final Map<Integer, List<Integer>> FUTURE = Map.of(
        0, List.of(2, 3, 5, 6, 8, 9), // Gladiator, Warlord, Paladin, Dark Avenger, Treasure Hunter, Hawkeye
        10, List.of(12, 13, 14, 16, 17), // Sorcerer, Necromancer, Warlock, Bishop, Prophet
        18, List.of(20, 21, 23, 24), // Temple Knight, Swordsinger, Plainswalker, Silver Ranger
        25, List.of(27, 28, 30), // Spellsinger, Elemental Summoner, Elder
        31, List.of(33, 34, 36, 37), // Shillien Knight, Bladedancer, Abyss Walker, Phantom Ranger
        38, List.of(40, 41, 43), // Spellhowler, Phantom Summoner, Shillien Elder
        44, List.of(46, 48), // Destroyer, Tyrant
        49, List.of(51, 52), // Overlord, Warcryer
        53, List.of(55, 57)); // Bounty Hunter, Warsmith

    public record Look(int classId, boolean female, byte face, byte hairStyle, byte hairColor) {
    }

    private final Weights<Integer> classes;
    private final Weights<Integer> face;
    private final Weights<Integer> hairColor;
    private final Weights<Integer> hairMale;
    private final Weights<Integer> hairFemale;

    public ClassPicker(List<String> classWeights, List<String> faceWeights, List<String> hairColorWeights, List<String> hairMaleWeights, List<String> hairFemaleWeights) {
        classes = Weights.parse(classWeights, k -> {
            final int id = Integer.parseInt(k);
            if (!isStartingClass(id)) {
                throw new IllegalArgumentException("class " + id + " is not a starting class");
            }
            return id;
        });
        face = limited(faceWeights, 3, "FaceWeights");
        hairColor = limited(hairColorWeights, 4, "HairColorWeights");
        hairMale = limited(hairMaleWeights, 5, "HairStyleWeightsMale");
        hairFemale = limited(hairFemaleWeights, 7, "HairStyleWeightsFemale");
    }

    public ClassPicker() {
        this(DEFAULT_CLASSES, DEFAULT_FACE, DEFAULT_HAIR_COLOR, DEFAULT_HAIR_MALE, DEFAULT_HAIR_FEMALE);
    }

    private static Weights<Integer> limited(List<String> w, int max, String key) {
        if (w.isEmpty() || (w.size() > max)) {
            throw new IllegalArgumentException(key + ": 1.." + max + " weights (stock creation limit)");
        }
        return Weights.indexed(w);
    }

    public int pickClass(Random rnd) {
        return classes.pick(rnd);
    }

    public double share(int classId) {
        return classes.share(classId);
    }

    /** Sex and look within the stock character creation limits (face 0-2, hair color 0-3, hair style 0-4 male / 0-6 female). */
    public Look look(int classId, Random rnd) {
        final int femalePercent = classId == 53 ? 30 : MYSTICS.contains(classId) ? 50 : 35;
        final boolean female = rnd.nextInt(100) < femalePercent;
        return new Look(classId, female, face.pick(rnd).byteValue(), (female ? hairFemale : hairMale).pick(rnd).byteValue(), hairColor.pick(rnd).byteValue());
    }

    /**
     * Planned 2nd profession. KT12 F2: when the starting class can become BD/SWS/PP/SE/BP, prefer those
     * ({@link SupportRoles#FUTURE_BIAS}) so a solo player can invite a buffer of his level.
     */
    public static int futureClass(int classId, Random rnd) {
        final List<Integer> l = FUTURE.get(classId);
        if (l == null) {
            return classId;
        }
        final List<Integer> buffers = l.stream().filter(SupportRoles.PARTY_BUFFER_2ND::contains).toList();
        if (!buffers.isEmpty() && (rnd.nextDouble() < SupportRoles.FUTURE_BIAS)) {
            return buffers.get(rnd.nextInt(buffers.size()));
        }
        return l.get(rnd.nextInt(l.size()));
    }

    public static boolean isStartingClass(int classId) {
        return DEFAULT_WEIGHTS.containsKey(classId);
    }
}

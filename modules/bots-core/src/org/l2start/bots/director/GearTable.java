package org.l2start.bots.director;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Equipment by level and profession (plan 20.4) from data/gear.xml (stock armour sets and weapons, generated).
 * Grade by level: none below 20 (below 10: the starting gear stays), D 20-39, C 40-51, B 52-60, A 61-75, S 76+, a new
 * grade 0-5 levels late by persona (phase 4e). One weapon and one set per persona and grade (stable by its seed), now
 * and then a visible enchant.
 */
public final class GearTable {
    public enum Grade {
        NONE(1, 19), D(20, 39), C(40, 51), B(52, 60), A(61, 75), S(76, 80);

        final int from;
        final int to;

        Grade(int from, int to) {
            this.from = from;
            this.to = to;
        }

        public static Grade of(int level) {
            for (Grade g : values()) {
                if (level <= g.to) {
                    return g;
                }
            }
            return S;
        }
    }

    public enum Armor {
        HEAVY, LIGHT, MAGIC
    }

    /** One set: item ids by slot (chest, legs, head, gloves, feet, shield). */
    public record ArmorSet(int id, Grade grade, Armor armor, long price, Map<String, Integer> parts) {
    }

    public record Weapon(int id, Grade grade, String kind, long price) {
    }

    /**
     * What a bot wears: item ids to equip (weapon first), empty = keep what it has (starting gear).
     * @param weaponId the weapon of the plan (0 = none)
     * @param weaponEnchant visible enchant of the weapon (+0..+4, phase 4e)
     * @param armorEnchant visible enchant of the armour parts (+0..+3)
     */
    public record Plan(Grade grade, String weaponKind, Armor armor, List<Integer> items, int weaponId, int weaponEnchant, int armorEnchant) {
        public Plan(Grade grade, String weaponKind, Armor armor, List<Integer> items) {
            this(grade, weaponKind, armor, items, 0, 0, 0);
        }
    }

    /** Highest visible enchant (config EnchantMax of bots-core). */
    private static volatile int enchantMax = 4;

    public static void setEnchantMax(int max) {
        enchantMax = Math.max(0, Math.min(16, max));
    }

    private static final Set<Integer> MAGES = Set.of(10, 11, 12, 13, 14, 15, 16, 17, 25, 26, 27, 28, 29, 30, 38, 39, 40, 41, 42, 43, 49, 50, 51, 52, 94, 95, 96, 97, 98, 103,
        104, 105, 110, 111, 112, 115, 116);
    /** Homunkulus's Sword - Acumen (C, SA Acumen). */
    public static final int HOMUNKULUS_ACUMEN = 6313;
    /** Full Plate Armor (C heavy, chest of the Full Plate set). */
    public static final int FULL_PLATE = 356;
    /** Tanks: Paladin, Dark Avenger, Temple Knight, Shillien Knight and their 3rd classes. */
    private static final Set<Integer> TANKS = Set.of(5, 6, 20, 33, 90, 91, 99, 106);

    /** A tank's heavy set: C = Full Plate when the datapack has it, else the most complete heavy set (with a shield). */
    private ArmorSet tankSet(Grade grade, double rank) {
        final Map<Armor, List<ArmorSet>> byArmor = sets.get(grade);
        final List<ArmorSet> heavy = byArmor == null ? null : byArmor.get(Armor.HEAVY);
        if ((heavy == null) || heavy.isEmpty()) {
            return set(grade, Armor.HEAVY, rank);
        }
        ArmorSet best = null;
        for (ArmorSet x : heavy) {
            if ((grade == Grade.C) && Integer.valueOf(FULL_PLATE).equals(x.parts().get("chest"))) {
                return x;
            }
            final int n = x.parts().size() + (x.parts().containsKey("shield") ? 1 : 0);
            final int bn = best == null ? -1 : best.parts().size() + (best.parts().containsKey("shield") ? 1 : 0);
            if (n > bn) {
                best = x;
            }
        }
        return best;
    }
    private static final Set<Integer> LIGHT = Set.of(7, 8, 9, 21, 22, 23, 24, 35, 36, 37, 47, 48, 92, 93, 100, 101, 102, 108, 109, 114);
    private static final Set<Integer> SHIELD = Set.of(4, 5, 6, 19, 20, 32, 33, 90, 91, 99, 106);
    private static final Map<Integer, String> WEAPON = new HashMap<>();
    static {
        for (int id : new int[] { 0, 4, 5, 6, 90, 91, 18, 19, 20, 99, 21, 100, 31, 32, 33, 106 }) {
            WEAPON.put(id, "SWORD1H");
        }
        for (int id : new int[] { 1, 44, 45, 46, 113 }) {
            WEAPON.put(id, "SWORD2H");
        }
        for (int id : new int[] { 2, 88, 34, 107 }) {
            WEAPON.put(id, "DUAL");
        }
        for (int id : new int[] { 3, 89 }) {
            WEAPON.put(id, "POLE");
        }
        for (int id : new int[] { 7, 8, 93, 22, 23, 101, 35, 36, 108 }) {
            WEAPON.put(id, "DAGGER");
        }
        for (int id : new int[] { 9, 92, 24, 102, 37, 109 }) {
            WEAPON.put(id, "BOW");
        }
        for (int id : new int[] { 47, 48, 114 }) {
            WEAPON.put(id, "DUALFIST");
        }
        for (int id : new int[] { 53, 54, 55, 117, 56, 57, 118 }) {
            WEAPON.put(id, "BLUNT1H");
        }
    }

    private final Map<Grade, Map<Armor, List<ArmorSet>>> sets = new EnumMap<>(Grade.class);
    private final Map<Grade, Map<String, List<Weapon>>> weapons = new EnumMap<>(Grade.class);
    private int setCount;
    private int weaponCount;

    public GearTable(List<ArmorSet> s, List<Weapon> w) {
        for (Grade g : Grade.values()) {
            sets.put(g, new EnumMap<>(Armor.class));
            weapons.put(g, new HashMap<>());
        }
        for (ArmorSet a : s) {
            sets.get(a.grade()).computeIfAbsent(a.armor(), k -> new ArrayList<>()).add(a);
            setCount++;
        }
        for (Weapon x : w) {
            weapons.get(x.grade()).computeIfAbsent(x.kind(), k -> new ArrayList<>()).add(x);
            weaponCount++;
        }
        for (Grade g : Grade.values()) {
            sets.get(g).values().forEach(l -> l.sort((a, b) -> Long.compare(a.price(), b.price())));
            weapons.get(g).values().forEach(l -> l.sort((a, b) -> Long.compare(a.price(), b.price())));
        }
    }

    public int sets() {
        return setCount;
    }

    public int weapons() {
        return weaponCount;
    }

    public static boolean mage(int classId) {
        return MAGES.contains(classId);
    }

    public static String weaponKind(int profession) {
        if (MAGES.contains(profession)) {
            return "MAGIC";
        }
        return WEAPON.getOrDefault(profession, "SWORD1H");
    }

    /** HEAVY/LIGHT/MAGIC by profession; gladiators and warriors pick heavy or light by seed. */
    public static Armor armor(int profession, long seed) {
        if (MAGES.contains(profession)) {
            return Armor.MAGIC;
        }
        if (LIGHT.contains(profession)) {
            return Armor.LIGHT;
        }
        if (((profession == 2) || (profession == 88) || (profession == 1)) && ((seed & 1) == 0)) {
            return Armor.LIGHT;
        }
        return Armor.HEAVY;
    }

    public Plan plan(int profession, int level, long seed) {
        return plan(profession, level, seed, 0);
    }

    /**
     * The grade a bot wears: a new grade comes {@code lag} levels late (saving up for it, phase 4e: D at 20, C at 40, B
     * at 52, A at 61, S at 76, plus 0-5 by persona).
     */
    public static Grade gradeWorn(int level, int lag) {
        final Grade now = Grade.of(level);
        final Grade late = Grade.of(Math.max(1, level - Math.max(0, lag)));
        return late.ordinal() < now.ordinal() ? Grade.values()[now.ordinal() - 1] : now; // at most one grade behind
    }

    /** Lag of a persona: {@code min..max} by greed (a greedy one saves longer). */
    public static int lag(double greed, int min, int max) {
        final int lo = Math.max(0, min);
        final int hi = Math.max(lo, max);
        return lo + (int) Math.round(Math.max(0, Math.min(1, greed)) * (hi - lo));
    }

    /** Stable 0..0.999 for a persona and a grade: the same set and weapon for the whole grade. */
    static double rank(long seed, Grade grade, int salt) {
        long h = (seed * 0x9E3779B97F4A7C15L) ^ ((grade.ordinal() + 1) * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return Math.floorMod(h, 1000) / 1000.0;
    }

    /** Visible weapon enchant of a persona in this grade: +0 mostly, now and then +1..+4 (never on no-grade items). */
    static int weaponEnchant(long seed, Grade grade) {
        if (grade == Grade.NONE) {
            return 0;
        }
        final double r = rank(seed, grade, 7);
        final int e = r < 0.55 ? 0 : r < 0.70 ? 1 : r < 0.82 ? 2 : r < 0.92 ? 3 : 4;
        return Math.min(enchantMax, e);
    }

    /** Armour enchant: most have none, a third +1..+3 (safe enchant), never above the weapon's or EnchantMax. */
    static int armorEnchant(long seed, Grade grade) {
        if (grade == Grade.NONE) {
            return 0;
        }
        final double r = rank(seed, grade, 11);
        final int e = r < 0.67 ? 0 : r < 0.82 ? 1 : r < 0.93 ? 2 : 3;
        return Math.min(Math.min(enchantMax, 3), e);
    }

    /**
     * @param profession the class the bot will have (its 2nd profession when known: weapon/armour style)
     * @param level its level now
     * @param lag levels a new grade comes late (see {@link #gradeWorn})
     */
    public Plan plan(int profession, int level, long seed, int lag) {
        final Grade grade = gradeWorn(level, lag);
        final String kind = weaponKind(profession);
        final Armor armor = armor(profession, seed);
        if (level < 10) {
            return new Plan(grade, kind, armor, List.of());
        }
        // one weapon and one set for the whole grade (the same bot tomorrow wears the same), by persona
        final double rank = rank(seed, grade, 1);
        final List<Integer> items = new ArrayList<>();
        final Weapon w = weapon(grade, kind, rank);
        // mages 40-52: Homunkulus's Sword with the Acumen SA (Gleb 30.10, «хомка с СА на акумен»)
        final int weaponId = (MAGES.contains(profession) && (level >= 40) && (level <= 52)) ? HOMUNKULUS_ACUMEN : (w == null ? 0 : w.id());
        if (weaponId != 0) {
            items.add(weaponId);
        }
        // tanks: a full heavy set with its shield (C grade: Full Plate)
        final ArmorSet s = TANKS.contains(profession) ? tankSet(grade, rank) : set(grade, armor, rank);
        if (s != null) {
            for (String slot : new String[] { "chest", "legs", "head", "gloves", "feet" }) {
                final Integer id = s.parts().get(slot);
                if (id != null) {
                    items.add(id);
                }
            }
            final Integer shield = s.parts().get("shield");
            if ((shield != null) && SHIELD.contains(profession)) {
                items.add(shield);
            }
        }
        return new Plan(grade, kind, armor, List.copyOf(items), weaponId, weaponEnchant(seed, grade), armorEnchant(seed, grade));
    }

    /**
     * Grand Olympiad kit (phase 9 E): mix of A and S for the weapon, the armour set and jewellery. Never a full S
     * set (if both weapon and armour roll S, the armour drops to A). Jewellery is always one A and one S of each
     * paired slot (earrings, rings) plus an A or S necklace. Stable by persona seed.
     */
    public Plan olympiadPlan(int profession, long seed) {
        final String kind = weaponKind(profession);
        final Armor armor = armor(profession, seed);
        Grade weaponGrade = asGrade(seed, 21);
        Grade armorGrade = asGrade(seed, 22);
        if ((weaponGrade == Grade.S) && (armorGrade == Grade.S)) {
            armorGrade = Grade.A;
        }
        final double weaponRank = rank(seed, weaponGrade, 1);
        final double armorRank = rank(seed, armorGrade, 1);
        final List<Integer> items = new ArrayList<>();
        final Weapon w = weapon(weaponGrade, kind, weaponRank);
        if (w != null) {
            items.add(w.id());
        }
        final ArmorSet s = set(armorGrade, armor, armorRank);
        if (s != null) {
            for (String slot : new String[] { "chest", "legs", "head", "gloves", "feet" }) {
                final Integer id = s.parts().get(slot);
                if (id != null) {
                    items.add(id);
                }
            }
            final Integer shield = s.parts().get("shield");
            if ((shield != null) && SHIELD.contains(profession)) {
                items.add(shield);
            }
        }
        items.addAll(olympiadJewels(seed));
        return new Plan(weaponGrade, kind, armor, List.copyOf(items), w == null ? 0 : w.id(), weaponEnchant(seed, weaponGrade),
            armorEnchant(seed, armorGrade));
    }

    /** Interlude A / S jewellery: Phoenix or Majestic (A) and Tateossian (S). */
    public static final int[] A_NECK = { 933, 924 }; // Phoenix Necklace, Majestic Necklace
    public static final int[] A_EAR = { 871, 862 }; // Phoenix Earring, Majestic Earring
    public static final int[] A_RING = { 902, 893 }; // Phoenix Ring, Majestic Ring
    public static final int S_NECK = 920; // Tateossian Necklace
    public static final int S_EAR = 858; // Tateossian Earring
    public static final int S_RING = 889; // Tateossian Ring

    /** Necklace + both earrings + both rings: mixed A and S, unique item ids so both ears/rings can be worn. */
    public static List<Integer> olympiadJewels(long seed) {
        final int aPick = rank(seed, Grade.A, 31) < 0.5 ? 0 : 1;
        final int neck = asGrade(seed, 32) == Grade.S ? S_NECK : A_NECK[aPick];
        return List.of(neck, A_EAR[aPick], S_EAR, A_RING[aPick], S_RING);
    }

    public static boolean olympiadJewel(int itemId) {
        if ((itemId == S_NECK) || (itemId == S_EAR) || (itemId == S_RING)) {
            return true;
        }
        for (int id : A_NECK) {
            if (id == itemId) {
                return true;
            }
        }
        for (int id : A_EAR) {
            if (id == itemId) {
                return true;
            }
        }
        for (int id : A_RING) {
            if (id == itemId) {
                return true;
            }
        }
        return false;
    }

    private static Grade asGrade(long seed, int salt) {
        return rank(seed, Grade.S, salt) < 0.5 ? Grade.A : Grade.S;
    }

    private Weapon weapon(Grade grade, String kind, double rank) {
        for (int g = grade.ordinal(); g >= 0; g--) {
            final Grade gg = Grade.values()[g];
            for (String k : kindFallbacks(kind, gg)) {
                final List<Weapon> l = weapons.get(gg).get(k);
                if ((l != null) && !l.isEmpty()) {
                    return l.get((int) (rank * l.size()));
                }
            }
        }
        return null;
    }

    private static List<String> kindFallbacks(String kind, Grade g) {
        final List<String> l = new ArrayList<>();
        l.add(kind);
        if (kind.equals("DUALFIST")) {
            l.add("FIST");
        } else if (kind.equals("DUAL") || kind.equals("SWORD2H")) {
            l.add("SWORD1H");
        } else if (kind.equals("BLUNT1H")) {
            l.add("SWORD1H");
        }
        return l;
    }

    private ArmorSet set(Grade grade, Armor armor, double rank) {
        for (int g = grade.ordinal(); g >= 0; g--) {
            final Grade gg = Grade.values()[g];
            for (Armor a : new Armor[] { armor, Armor.LIGHT, Armor.HEAVY, Armor.MAGIC }) {
                if ((armor == Armor.MAGIC) != (a == Armor.MAGIC)) {
                    continue; // a mage never wears plate, a fighter never a robe
                }
                final List<ArmorSet> l = sets.get(gg).get(a);
                if ((l != null) && !l.isEmpty()) {
                    return l.get((int) (rank * l.size()));
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ loading

    public static GearTable load(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    public static GearTable load(InputStream in) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Document doc = f.newDocumentBuilder().parse(in);
        final List<ArmorSet> s = new ArrayList<>();
        final NodeList sl = doc.getElementsByTagName("set");
        for (int i = 0; i < sl.getLength(); i++) {
            final Element e = (Element) sl.item(i);
            final Map<String, Integer> parts = new HashMap<>();
            for (String slot : new String[] { "chest", "legs", "head", "gloves", "feet", "shield" }) {
                if (e.hasAttribute(slot)) {
                    parts.put(slot, Integer.parseInt(e.getAttribute(slot)));
                }
            }
            s.add(new ArmorSet(Integer.parseInt(e.getAttribute("id")), Grade.valueOf(e.getAttribute("grade")), Armor.valueOf(e.getAttribute("armor")),
                Long.parseLong(e.getAttribute("price")), Map.copyOf(parts)));
        }
        final List<Weapon> w = new ArrayList<>();
        final NodeList wl = doc.getElementsByTagName("weapon");
        for (int i = 0; i < wl.getLength(); i++) {
            final Element e = (Element) wl.item(i);
            w.add(new Weapon(Integer.parseInt(e.getAttribute("id")), Grade.valueOf(e.getAttribute("grade")), e.getAttribute("kind"), Long.parseLong(e.getAttribute("price"))));
        }
        return new GearTable(s, w);
    }
}

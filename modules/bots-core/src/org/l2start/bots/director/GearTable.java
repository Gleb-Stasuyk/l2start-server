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
 * Grade by level: none below 20 (below 10: the starting gear stays), D 20-39, C 40-51, B 52-60, A 61-75, S 76+.
 * Early in a grade's level band the cheaper items, late the dearer ones, spread by the persona's seed.
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

    /** What a bot wears: item ids to equip (weapon first), empty = keep what it has (starting gear). */
    public record Plan(Grade grade, String weaponKind, Armor armor, List<Integer> items) {
    }

    private static final Set<Integer> MAGES = Set.of(10, 11, 12, 13, 14, 15, 16, 17, 25, 26, 27, 28, 29, 30, 38, 39, 40, 41, 42, 43, 49, 50, 51, 52, 94, 95, 96, 97, 98, 103,
        104, 105, 110, 111, 112, 115, 116);
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

    /**
     * @param profession the class the bot will have (its 2nd profession when known: weapon/armour style)
     * @param level its level now
     */
    public Plan plan(int profession, int level, long seed) {
        final Grade grade = Grade.of(level);
        final String kind = weaponKind(profession);
        final Armor armor = armor(profession, seed);
        if (level < 10) {
            return new Plan(grade, kind, armor, List.of());
        }
        final double inBand = grade.to == grade.from ? 0.5 : (level - grade.from) / (double) (grade.to - grade.from);
        final double jitter = ((Math.floorMod(seed * 0x9E3779B97F4A7C15L, 1000) / 1000.0) - 0.5) * 0.5;
        final double rank = Math.max(0, Math.min(0.999, inBand + jitter));
        final List<Integer> items = new ArrayList<>();
        final Weapon w = weapon(grade, kind, rank);
        if (w != null) {
            items.add(w.id());
        }
        final ArmorSet s = set(grade, armor, rank);
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
        return new Plan(grade, kind, armor, List.copyOf(items));
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

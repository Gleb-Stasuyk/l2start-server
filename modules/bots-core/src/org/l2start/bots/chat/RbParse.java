package org.l2start.bots.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RB party, iteration 1 (docs/RB_PARTY_PLAN.md): a live player's request in trade / shout for party members for a raid
 * boss, e.g. "ищу танка и биша на рб", "нужен пп rb", "2 дд на рб", "сх на рб". Only the roles named answer. Pure: no
 * game types, unit-tested on a plain JDK.
 */
public final class RbParse {
    /** What the player asks for. */
    public enum Role {
        TANK, BISHOP, PROPHET, DD
    }

    /** Planned 2nd professions of each role (persona futureClassId; 3rd professions follow from them). */
    public static final Set<Integer> TANKS = Set.of(5, 6, 20, 33); // Paladin, Dark Avenger, Temple Knight, Shillien Knight
    public static final Set<Integer> BISHOPS = Set.of(16);
    public static final Set<Integer> PROPHETS = Set.of(17);
    public static final int NECROMANCER = 13;
    public static final int SPELLSINGER = 27;
    public static final int SPELLHOWLER = 40;
    public static final Set<Integer> DDS = Set.of(NECROMANCER, SPELLSINGER, SPELLHOWLER);

    /** Current classes of the same roles at 40+ (2nd) and 76+ (3rd). */
    private static final Set<Integer> TANK_NOW = Set.of(5, 6, 20, 33, 90, 91, 99, 106);
    private static final Set<Integer> BISHOP_NOW = Set.of(16, 97);
    private static final Set<Integer> PROPHET_NOW = Set.of(17, 98);
    private static final Set<Integer> DD_NOW = Set.of(13, 27, 40, 95, 103, 110);

    /** One place in the party: the role and the planned 2nd professions that fit it. */
    public record Slot(Role role, Set<Integer> classes) {
        public boolean fits(int futureClass) {
            return classes.contains(futureClass);
        }
    }

    /** A party has 9 places: the player and at most 8 others. */
    public static final int MAX_SLOTS = 8;

    private static final int RX = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern RB = Pattern.compile("\\b(?:rb|рб|raid|рейд[а-я]*|raidboss|рейдбосс[а-я]*)\\b", RX);

    private static final String N = "(?:(\\d)\\s*(?:x|х)?\\s*)?";
    private static final String TAIL = "(?:\\s*(?:x|х)\\s*(\\d))?";

    private static final Pattern TANK = Pattern.compile("\\b" + N + "(?:танк[а-я]*|tank[a-z]*|паладин[а-я]*|пала|паладина|тк|шк|tk|sk)\\b" + TAIL, RX);
    private static final Pattern BISHOP = Pattern.compile("\\b" + N + "(?:биш[а-я]*|bish[a-z]*|bp|епископ[а-я]*|кардинал[а-я]*|cardinal|хил[а-я]*|heal[a-z]*)\\b" + TAIL, RX);
    private static final Pattern PROPHET = Pattern.compile("\\b" + N + "(?:пп|pp|проф[а-я]*|prophet[a-z]*|пророк[а-я]*|иерофант[а-я]*|hiero[a-z]*)\\b" + TAIL, RX);
    private static final Pattern SH = Pattern.compile("\\b" + N + "(?:сх|sh|spellhowler[a-z]*|хаулер[а-я]*|storm\\s*screamer)\\b" + TAIL, RX);
    private static final Pattern SPS = Pattern.compile("\\b" + N + "(?:сс|спс|sps|spellsinger[a-z]*|спеллсингер[а-я]*|mystic\\s*muse)\\b" + TAIL, RX);
    private static final Pattern NECRO = Pattern.compile("\\b" + N + "(?:некр[а-я]*|necr[a-z]*|соултейкер[а-я]*|soultaker)\\b" + TAIL, RX);
    private static final Pattern DD = Pattern.compile("\\b" + N + "(?:дд|dd|дпс|dps|нюкер[а-я]*|nuker[a-z]*)\\b" + TAIL, RX);

    private RbParse() {
    }

    /** @return true when the line is about a raid boss at all (rb / рб / рейд). */
    public static boolean aboutRb(String text) {
        return (text != null) && RB.matcher(norm(text)).find();
    }

    /**
     * The places asked for, in the order roles are listed (tank, bishop, prophet, then damage dealers); empty when the
     * line is not an RB party request (no rb word, or no role named).
     */
    public static List<Slot> parse(String text) {
        final List<Slot> out = new ArrayList<>();
        if ((text == null) || text.isBlank()) {
            return out;
        }
        final String t = norm(text);
        if (!RB.matcher(t).find()) {
            return out;
        }
        add(out, TANK, t, Role.TANK, TANKS);
        add(out, BISHOP, t, Role.BISHOP, BISHOPS);
        add(out, PROPHET, t, Role.PROPHET, PROPHETS);
        add(out, SH, t, Role.DD, Set.of(SPELLHOWLER));
        add(out, SPS, t, Role.DD, Set.of(SPELLSINGER));
        add(out, NECRO, t, Role.DD, Set.of(NECROMANCER));
        add(out, DD, t, Role.DD, DDS);
        return out.size() > MAX_SLOTS ? new ArrayList<>(out.subList(0, MAX_SLOTS)) : out;
    }

    /**
     * Role of a bot: by its planned 2nd profession (a future bishop is a cleric below 40), else by its current class;
     * null = none of the RB roles.
     */
    public static Role roleOf(int futureClass, int classId) {
        if (TANKS.contains(futureClass) || TANK_NOW.contains(classId)) {
            return Role.TANK;
        }
        if (BISHOPS.contains(futureClass) || BISHOP_NOW.contains(classId)) {
            return Role.BISHOP;
        }
        if (PROPHETS.contains(futureClass) || PROPHET_NOW.contains(classId)) {
            return Role.PROPHET;
        }
        if (DDS.contains(futureClass) || DD_NOW.contains(classId)) {
            return Role.DD;
        }
        return null;
    }

    /** Planned 2nd profession of a current class of the RB roles (40+), 0 = not one. */
    public static int futureOf(int classId) {
        return switch (classId) {
            case 90 -> 5;
            case 91 -> 6;
            case 99 -> 20;
            case 106 -> 33;
            case 97 -> 16;
            case 98 -> 17;
            case 95 -> 13;
            case 103 -> 27;
            case 110 -> 40;
            default -> (TANKS.contains(classId) || BISHOPS.contains(classId) || PROPHETS.contains(classId) || DDS.contains(classId)) ? classId : 0;
        };
    }

    /** Starting class of a planned 2nd profession of the RB roles (-1 = not one). */
    public static int startClassOf(int futureClass) {
        return switch (futureClass) {
            case 5, 6 -> 0; // Human Fighter
            case 13, 16, 17 -> 10; // Human Mystic
            case 20 -> 18; // Elven Fighter
            case 27 -> 25; // Elven Mystic
            case 33 -> 31; // Dark Fighter
            case 40 -> 38; // Dark Mystic
            default -> -1;
        };
    }

    /** A bot of this level answers a player of {@code playerLevel}: from 2 below to 3 above. */
    public static boolean levelFits(int botLevel, int playerLevel) {
        return (botLevel >= (playerLevel - 2)) && (botLevel <= (playerLevel + 3));
    }

    /** Role word for the whisper: RU танк/биш/пп/дд, EN tank/bishop/pp/dd. */
    public static String word(Role role, boolean ru) {
        return switch (role) {
            case TANK -> ru ? "танк" : "tank";
            case BISHOP -> ru ? "биш" : "bishop";
            case PROPHET -> ru ? "пп" : "pp";
            case DD -> ru ? "дд" : "dd";
        };
    }

    private static void add(List<Slot> out, Pattern p, String t, Role role, Set<Integer> classes) {
        final Matcher m = p.matcher(t);
        int n = 0;
        while (m.find()) {
            final String a = m.group(1);
            final String b = m.group(2);
            n += Math.max(1, Math.min(MAX_SLOTS, a != null ? Integer.parseInt(a) : b != null ? Integer.parseInt(b) : 1));
        }
        for (int i = 0; i < Math.min(n, MAX_SLOTS); i++) {
            out.add(new Slot(role, classes));
        }
    }

    private static String norm(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}

package org.l2start.bots.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RB party, iteration 1 (docs/RB_PARTY_PLAN.md): a live player's request in trade / shout for party members for a raid
 * boss, e.g. "ищу танка и биша на рб", "нужен пп rb", "2 дд на рб", "сх на рб". Only the roles named answer. The shout
 * «ищу фп на рб» is a second full party (bishop + Shillien Elder SE + 7 mage DD), not a fill of the player's party.
 * SE here is Shillien Elder 43 / Shillien Saint 112 (Empower, Recharge; Gleb 30.10 t410u), not Elven Elder.
 * Pure: no game types, unit-tested on a plain JDK.
 */
public final class RbParse {
    /** What the player asks for. */
    public enum Role {
        TANK, BISHOP, PROPHET, ELDER, DD
    }

    /** Planned 2nd professions of each role (persona futureClassId; 3rd professions follow from them). */
    public static final Set<Integer> TANKS = Set.of(5, 6, 20, 33); // Paladin, Dark Avenger, Temple Knight, Shillien Knight
    public static final Set<Integer> BISHOPS = Set.of(16);
    public static final Set<Integer> PROPHETS = Set.of(17);
    /** Elven Elder (RB «SE»: Empower + Recharge). Not Shillien Elder (town-buffer) and not Spellhowler. */
    public static final int SHILLIEN_ELDER = 43;
    public static final Set<Integer> ELDERS = Set.of(SHILLIEN_ELDER);
    public static final int NECROMANCER = 13;
    public static final int SPELLSINGER = 27;
    public static final int SPELLHOWLER = 40;
    public static final Set<Integer> DDS = Set.of(NECROMANCER, SPELLSINGER, SPELLHOWLER);

    /** Current classes of the same roles at 40+ (2nd) and 76+ (3rd). */
    private static final Set<Integer> TANK_NOW = Set.of(5, 6, 20, 33, 90, 91, 99, 106);
    private static final Set<Integer> BISHOP_NOW = Set.of(16, 97);
    private static final Set<Integer> PROPHET_NOW = Set.of(17, 98);
    private static final Set<Integer> ELDER_NOW = Set.of(43, 112); // Shillien Elder / Shillien Saint
    private static final Set<Integer> DD_NOW = Set.of(13, 27, 40, 95, 103, 110);

    /** One place in the party: the role and the planned 2nd professions that fit it. */
    public record Slot(Role role, Set<Integer> classes) {
        public boolean fits(int futureClass) {
            return classes.contains(futureClass);
        }
    }

    /** A party has 9 places: the player and at most 8 others. */
    public static final int MAX_SLOTS = 8;
    /** Second full party: 9 bots of their own (bishop + SE + 7 mage DD). */
    public static final int MAX_FP_SLOTS = 9;
    /** Mage DDs in the second full party. */
    public static final int FP_DD_COUNT = 7;

    private static final int RX = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern RB = Pattern.compile("\\b(?:rb|рб|raid|рейд[а-я]*|raidboss|рейдбосс[а-я]*)\\b", RX);
    /** «ищу фп на рб» / "lf fp for rb": a second full party, not «еще пати» / «2 пати». */
    private static final Pattern FP = Pattern.compile("\\b(?:фп|fp)\\b", RX);

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
     * line is not an RB party request (no rb word, or no role named). «ищу фп на рб» is the second full party
     * (9 places: bishop, Shillien Elder, 7 mage DD) and is not capped at {@link #MAX_SLOTS}.
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
        if (FP.matcher(t).find()) {
            return fullPartySlots();
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

    /** True when the line is the second-full-party shout («ищу фп на рб»), not a fill of the player's party. */
    public static boolean isFullParty(String text) {
        if ((text == null) || text.isBlank()) {
            return false;
        }
        final String t = norm(text);
        return RB.matcher(t).find() && FP.matcher(t).find();
    }

    /** Bishop + Shillien Elder + 7 mage DD (9 bots, their own party). */
    public static List<Slot> fullPartySlots() {
        final List<Slot> out = new ArrayList<>(MAX_FP_SLOTS);
        out.add(new Slot(Role.BISHOP, BISHOPS));
        out.add(new Slot(Role.ELDER, ELDERS));
        for (int i = 0; i < FP_DD_COUNT; i++) {
            out.add(new Slot(Role.DD, DDS));
        }
        return out;
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
        if (ELDERS.contains(futureClass) || ELDER_NOW.contains(classId)) {
            return Role.ELDER;
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
            case 112 -> 43;
            case 95 -> 13;
            case 103 -> 27;
            case 110 -> 40;
            default -> (TANKS.contains(classId) || BISHOPS.contains(classId) || PROPHETS.contains(classId) || ELDERS.contains(classId)
                || DDS.contains(classId)) ? classId : 0;
        };
    }

    /** Starting class of a planned 2nd profession of the RB roles (-1 = not one). */
    public static int startClassOf(int futureClass) {
        return switch (futureClass) {
            case 5, 6 -> 0; // Human Fighter
            case 13, 16, 17 -> 10; // Human Mystic
            case 20 -> 18; // Elven Fighter
            case 27, 30 -> 25; // Elven Mystic (Spellsinger / Elven Elder)
            case 33 -> 31; // Dark Fighter
            case 40, 43 -> 38; // Dark Mystic (Spellhowler / Shillien Elder)
            default -> -1;
        };
    }

    /** A bot of this level answers a player of {@code playerLevel}: from 2 below to 3 above. */
    private static final Pattern LV_RANGE = Pattern.compile("\\b([1-8]\\d)\\s*(?:-|–|—|\\.\\.|до)\\s*([1-8]\\d)\\b", RX);
    private static final Pattern LV_ONE = Pattern.compile("\\b([1-8]\\d)\\s*\\+?\\s*(?:лвл|лв|ур[а-я]*|lvl|lv|level)\\b|\\b(?:лвл|ур[а-я]*|lvl|lv|level)\\s*([1-8]\\d)\\b", RX);

    /**
     * Levels asked for in the line (Gleb 31.10 t441u): «44-48» = 44..48 inclusive; «52 лвл» = 51..53 (±1 so a
     * pool of the exact level is not too thin); none = the caller's -2..+3. @return {lo, hi}
     */
    public static int[] levelBand(String text, int playerLevel) {
        if (text != null) {
            final String t = norm(text);
            final java.util.regex.Matcher r = LV_RANGE.matcher(t);
            if (r.find()) {
                final int a = Integer.parseInt(r.group(1));
                final int b = Integer.parseInt(r.group(2));
                return new int[] { Math.min(a, b), Math.max(a, b) };
            }
            final java.util.regex.Matcher o = LV_ONE.matcher(t);
            if (o.find()) {
                final int l = Integer.parseInt(o.group(1) != null ? o.group(1) : o.group(2));
                return new int[] { l - 1, l + 1 };
            }
        }
        return new int[] { playerLevel - 2, playerLevel + 3 };
    }

    public static boolean levelFits(int botLevel, int playerLevel) {
        return (botLevel >= (playerLevel - 2)) && (botLevel <= (playerLevel + 3));
    }

    /** Role word for the whisper: RU танк/биш/пп/се/дд, EN tank/bishop/pp/se/dd. */
    public static String word(Role role, boolean ru) {
        return switch (role) {
            case TANK -> ru ? "танк" : "tank";
            case BISHOP -> ru ? "биш" : "bishop";
            case PROPHET -> ru ? "пп" : "pp";
            case ELDER -> ru ? "се" : "se";
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

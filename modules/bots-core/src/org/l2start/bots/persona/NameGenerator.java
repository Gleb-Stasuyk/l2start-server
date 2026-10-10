package org.l2start.bots.persona;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Realistic player nicknames in four styles (shares from the approved plan: RU transliteration 35 %, English 30 %,
 * fantasy 20 %, "gamer" 15 %). Pure Java, deterministic for a given {@link Random}. The Interlude client accepts
 * Latin letters and digits, up to 16 characters, so Russian names are transliterated.
 */
public final class NameGenerator {
    public enum Style {
        RU, EN, FANTASY, GAMER
    }

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;
    private static final Pattern ALNUM = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern TRIPLE = Pattern.compile("(?i)([a-z0-9])\\1\\1");

    // ---- word pools
    private static final List<String> RU_NAMES = List.of("Vasya", "Vasyan", "Dimon", "Dima", "Serega", "Sergo", "Lexa", "Lesha", "Kolyan", "Kolya", "Petya", "Vovan", "Vova", "Sanya", "Sanek", "Tolik", "Zhenya", "Maks", "Andrey", "Artem", "Artemka", "Igor", "Oleg", "Pasha", "Pashok", "Roma", "Romka", "Slava", "Stas", "Tema", "Yura", "Vitek", "Vitalik", "Denis", "Kirill", "Ilya", "Egor", "Nikita", "Misha", "Mishka", "Grisha", "Fedya", "Anya", "Katya", "Lena", "Masha", "Natasha", "Olya", "Sveta", "Yulya", "Dasha", "Ksyusha", "Nastya", "Lera", "Alena", "Marina", "Ira", "Vika", "Tanya", "Zhora", "Gosha", "Lyoha", "Seva", "Borya");
    private static final List<String> RU_WORDS = List.of("Kotik", "Kotyara", "Zlobnyi", "Bratok", "Shustryi", "Volk", "Medved", "Lis", "Yozh", "Tank", "Hilka", "Lekar", "Ohotnik", "Strelok", "Koldun", "Vedma", "Molot", "Topor", "Kulak", "Bes", "Demon", "Angel", "Ten", "Mrak", "Grom", "Veter", "Ogon", "Sneg", "Zver", "Bogatyr", "Vityaz", "Knyaz", "Batya", "Dedok", "Malysh", "Barsik", "Pirozhok", "Babai", "Leshii", "Sokol", "Berkut", "Kabanchik", "Shaman", "Dobryak", "Zloi", "Tihii", "Hitryi");
    private static final List<String> RU_SUFFIX = List.of("4ik", "ok", "chik", "ka", "on", "ych");
    private static final List<String> EN_FIRST = List.of("Iron", "Shadow", "Night", "Dark", "Storm", "Blood", "Silver", "Frost", "Fire", "Stone", "Wild", "Grim", "Swift", "Black", "Red", "Star", "Moon", "Sun", "Steel", "Ghost", "Raven", "Wolf", "Bear", "Hawk", "Thunder", "Rage", "Soul", "Dream", "Blade", "Ash", "Ember", "Mist", "Doom", "Holy", "Gold");
    private static final List<String> EN_SECOND = List.of("wolf", "step", "fall", "blade", "heart", "fang", "claw", "bane", "walker", "rider", "hunter", "slayer", "born", "shade", "song", "strike", "fire", "storm", "wind", "shield", "arrow", "mind", "eye", "hand", "guard", "light", "thorn", "crow", "spark");
    private static final List<String> EN_NAMES = List.of("Kira", "Alex", "Mike", "Jack", "Tom", "Nick", "Leon", "Max", "Kate", "Lisa", "Emma", "Mia", "Zoe", "Ryan", "Chris", "Dan", "Sam", "Ben", "Luke", "Mark", "Neo", "Ash", "Jade", "Ruby", "Luna", "Nova", "Ace", "Rex", "Kai", "Finn", "Eva", "Lily", "Jake", "Oscar", "Viktor", "Hugo", "Iris", "Nina");
    private static final List<String> FANTASY_START = List.of("Ae", "Al", "Ar", "Bel", "Cal", "Dar", "El", "Eld", "Fae", "Gal", "Gor", "Hal", "Il", "Kal", "Lor", "Mal", "Mor", "Nar", "Or", "Rag", "Sar", "Tal", "Thal", "Thar", "Ul", "Val", "Vor", "Xan", "Zar", "Ky", "Ny", "Syl", "Drak", "Fen", "Gil", "Is", "Jor", "Ler");
    private static final List<String> FANTASY_MID = List.of("a", "e", "i", "o", "u", "ae", "ia", "ra", "ri", "ro", "la", "li", "an", "en", "or", "ar", "ath", "el", "ys");
    private static final List<String> FANTASY_END = List.of("n", "r", "s", "th", "l", "rin", "ra", "ion", "wen", "dor", "gar", "mir", "nas", "ros", "thas", "vin", "wyn", "rok", "gul", "mar", "iel", "ia", "us");
    private static final List<String> GAMER_ROLES = List.of("Healer", "Tank", "Archer", "Mage", "Killer", "Nuker", "Spoiler", "Buffer", "Dagger", "Pvper", "Farmer", "Sniper");
    private static final List<String> GAMER_NUMBERS = List.of("777", "666", "1337", "228", "99", "007", "13", "69", "88", "2005", "1999");
    private static final List<String> STOP = List.of("admin", "moder", "support", "bot", "server", "gamemaster", "fuck", "shit", "bitch", "cunt", "nigg", "pidor", "pidar", "huy", "xyu", "blya", "suka", "pizd", "ebat", "eban", "mudak", "gandon", "dick", "porn", "hitler", "nazi");

    private final Random rnd;
    private final int[] weights;

    /** @param weights shares of RU, EN, FANTASY, GAMER (any non-negative numbers, at least one positive) */
    public NameGenerator(Random rnd, int... weights) {
        if (weights.length != Style.values().length) {
            throw new IllegalArgumentException("need " + Style.values().length + " weights");
        }
        int sum = 0;
        for (int w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("negative weight");
            }
            sum += w;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("all weights are 0");
        }
        this.rnd = rnd;
        this.weights = weights.clone();
    }

    public NameGenerator(Random rnd) {
        this(rnd, 35, 30, 20, 15);
    }

    public Style pickStyle() {
        int sum = 0;
        for (int w : weights) {
            sum += w;
        }
        int r = rnd.nextInt(sum);
        for (Style s : Style.values()) {
            r -= weights[s.ordinal()];
            if (r < 0) {
                return s;
            }
        }
        return Style.EN;
    }

    /**
     * A valid name that {@code taken} does not reject (taken = already exists, stock forbidden names, ...).
     * @throws IllegalStateException when no free name was found in 200 tries
     */
    public Named next(Predicate<String> taken) {
        // keep the drawn style while retrying, so taken names do not shift the style mix
        for (int i = 0; i < 5; i++) {
            final Style style = pickStyle();
            for (int k = 0; k < 40; k++) {
                final String name = generate(style);
                if (isValid(name) && !taken.test(name)) {
                    return new Named(name, style);
                }
            }
        }
        throw new IllegalStateException("no free name found");
    }

    public record Named(String name, Style style) {
    }

    public String generate(Style style) {
        return switch (style) {
            case RU -> ru();
            case EN -> en();
            case FANTASY -> fantasy();
            case GAMER -> gamer();
        };
    }

    private String ru() {
        switch (rnd.nextInt(7)) {
            case 0:
                return pick(RU_NAMES);
            case 1:
                return pick(RU_NAMES) + year();
            case 2:
                return stem(pick(RU_NAMES)) + pick(RU_SUFFIX);
            case 3:
                return pick(RU_WORDS);
            case 4:
                return pick(RU_WORDS) + pick(RU_NAMES);
            case 5:
                return "Ne" + pick(RU_WORDS);
            default:
                return pick(RU_WORDS) + (10 + rnd.nextInt(90));
        }
    }

    private String en() {
        switch (rnd.nextInt(5)) {
            case 0:
            case 1:
                return pick(EN_FIRST) + pick(EN_SECOND);
            case 2:
                return pick(EN_NAMES);
            case 3:
                return pick(EN_NAMES) + (rnd.nextBoolean() ? year() : String.valueOf(1 + rnd.nextInt(99)));
            default:
                return pick(EN_NAMES) + pick(EN_FIRST);
        }
    }

    private String fantasy() {
        final StringBuilder sb = new StringBuilder(pick(FANTASY_START));
        if (rnd.nextInt(3) > 0) {
            sb.append(pick(FANTASY_MID));
        }
        sb.append(pick(FANTASY_END));
        return sb.toString();
    }

    private String gamer() {
        final String word = capitalize(pick(EN_SECOND));
        switch (rnd.nextInt(6)) {
            case 0:
                return "xX" + pick(EN_FIRST) + word + "Xx";
            case 1:
                return pick(EN_FIRST) + "Lord" + pick(GAMER_NUMBERS);
            case 2:
                return "Pro" + pick(GAMER_ROLES);
            case 3:
                return alternateCase(pick(GAMER_ROLES));
            case 4:
                return pick(EN_FIRST) + word + pick(GAMER_NUMBERS);
            default:
                return (rnd.nextBoolean() ? "Mr" : "Lord") + pick(EN_FIRST) + (rnd.nextBoolean() ? "" : pick(GAMER_NUMBERS));
        }
    }

    /** Length, characters, no 3 equal characters in a row (except the xX decoration), stop words. */
    public static boolean isValid(String name) {
        if ((name == null) || (name.length() < MIN_LENGTH) || (name.length() > MAX_LENGTH) || !ALNUM.matcher(name).matches()) {
            return false;
        }
        final String core = name.startsWith("xX") && name.endsWith("Xx") ? name.substring(2, name.length() - 2) : name;
        if (TRIPLE.matcher(core).find() && !core.matches(".*(777|666|000|111).*")) {
            return false;
        }
        final String low = name.toLowerCase(Locale.ROOT);
        if (low.startsWith("gm") || low.endsWith("gm")) {
            return false;
        }
        for (String s : STOP) {
            if (low.contains(s)) {
                return false;
            }
        }
        return true;
    }

    private String year() {
        return String.valueOf(rnd.nextInt(10) < 7 ? 1980 + rnd.nextInt(30) : 10 + rnd.nextInt(90));
    }

    private static String stem(String name) {
        // Dima -> Dim(4ik), Vasya -> Vas(ok); short names stay as they are
        String s = name;
        while ((s.length() > 3) && ("aeiouy".indexOf(Character.toLowerCase(s.charAt(s.length() - 1))) >= 0)) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String alternateCase(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        final boolean startUpper = rnd.nextBoolean();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            sb.append(((i % 2) == 0) == startUpper ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String pick(List<String> l) {
        return l.get(rnd.nextInt(l.size()));
    }
}

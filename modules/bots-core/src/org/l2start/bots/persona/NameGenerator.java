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

    /** Approved default mix: RU 35, EN 30, FANTASY 20, GAMER 15 (config key NameStyles). */
    public static final List<String> DEFAULT_STYLES = List.of("ru:35", "en:30", "fantasy:20", "gamer:15");

    private final Random rnd;
    private boolean female;
    private final NameDictionary dict;
    private final Weights<Style> styles;
    /** Share of new names allowed to contain digits (default 0.03). */
    private final double digitShare;
    private final List<String> ruMale, ruFemale, enMale, enFemale, ruWords, ruSuffixes, enFirst, enSecond, fantasyStart, fantasyMid, fantasyEnd, gamerRoles, gamerNumbers, stopWords;

    public NameGenerator(Random rnd, NameDictionary dict, Weights<Style> styles) {
        this(rnd, dict, styles, 0.03);
    }

    public NameGenerator(Random rnd, NameDictionary dict, Weights<Style> styles, double digitShare) {
        this.rnd = rnd;
        this.dict = dict;
        this.styles = styles;
        this.digitShare = Math.max(0, Math.min(1, digitShare));
        ruMale = dict.get("ru_names_male");
        ruFemale = dict.get("ru_names_female");
        enMale = dict.get("en_names_male");
        enFemale = dict.get("en_names_female");
        ruWords = dict.get("ru_words");
        ruSuffixes = dict.get("ru_suffixes");
        enFirst = dict.get("en_first");
        enSecond = dict.get("en_second");
        fantasyStart = dict.get("fantasy_start");
        fantasyMid = dict.get("fantasy_mid");
        fantasyEnd = dict.get("fantasy_end");
        gamerRoles = dict.get("gamer_roles");
        gamerNumbers = dict.get("gamer_numbers");
        stopWords = dict.get("stop_words");
    }

    public NameGenerator(Random rnd, NameDictionary dict) {
        this(rnd, dict, parseStyles(DEFAULT_STYLES), 0.03);
    }

    public double digitShare() {
        return digitShare;
    }

    public static boolean hasDigit(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static Weights<Style> parseStyles(List<String> entries) {
        return Weights.parse(entries, k -> Style.valueOf(k.toUpperCase(Locale.ROOT)));
    }

    public Style pickStyle() {
        return styles.pick(rnd);
    }

    public NameDictionary dictionary() {
        return dict;
    }

    /**
     * A valid name that {@code taken} does not reject (taken = already exists, stock forbidden names, ...).
     * @throws IllegalStateException when no free name was found in 200 tries
     */
    public Named next(Predicate<String> taken) {
        return next(taken, false);
    }

    /** Same, for a character of this sex: first names follow it (a female character has a male nickname in ~15 % of cases, like players). */
    public Named next(Predicate<String> taken, boolean female) {
        this.female = female;
        // keep the drawn style while retrying, so taken names do not shift the style mix
        for (int i = 0; i < 5; i++) {
            final Style style = pickStyle();
            for (int k = 0; k < 40; k++) {
                // first half: natural mix; digit names kept with probability digitShare
                final String name = generate(style);
                if (!isValid(name) || taken.test(name)) {
                    continue;
                }
                if (hasDigit(name) && (rnd.nextDouble() >= digitShare)) {
                    continue;
                }
                return new Named(name, style);
            }
            // second half: force no digits so the pool never stalls
            for (int k = 0; k < 40; k++) {
                final String name = generateWithoutDigits(style);
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
            case RU -> ru(true);
            case EN -> en(true);
            case FANTASY -> fantasy();
            case GAMER -> gamer(true);
        };
    }

    private String generateWithoutDigits(Style style) {
        return switch (style) {
            case RU -> ru(false);
            case EN -> en(false);
            case FANTASY -> fantasy();
            case GAMER -> gamer(false);
        };
    }

    private String ru(boolean allowDigits) {
        final int roll = allowDigits ? rnd.nextInt(7) : rnd.nextInt(5);
        switch (roll) {
            case 0:
                return pick(ruNames());
            case 1:
                return allowDigits ? pick(ruNames()) + year() : stem(pick(ruNames())) + pick(ruSuffixes);
            case 2:
                return stem(pick(ruNames())) + pick(ruSuffixes);
            case 3:
                return pick(ruWords);
            case 4:
                return pick(ruWords) + pick(ruNames());
            case 5:
                return "Ne" + pick(ruWords);
            default:
                return allowDigits ? pick(ruWords) + (10 + rnd.nextInt(90)) : pick(ruWords);
        }
    }

    private List<String> ruNames() {
        return firstNames(ruMale, ruFemale);
    }

    private List<String> enNames() {
        return firstNames(enMale, enFemale);
    }

    private List<String> firstNames(List<String> male, List<String> fem) {
        if (female) {
            return rnd.nextInt(100) < 85 ? fem : male;
        }
        return rnd.nextInt(100) < 3 ? fem : male;
    }

    private String en(boolean allowDigits) {
        final int roll = allowDigits ? rnd.nextInt(5) : rnd.nextInt(4);
        switch (roll) {
            case 0:
            case 1:
                return pick(enFirst) + pick(enSecond);
            case 2:
                return pick(enNames());
            case 3:
                return allowDigits ? pick(enNames()) + (rnd.nextBoolean() ? year() : String.valueOf(1 + rnd.nextInt(99)))
                    : pick(enNames()) + pick(enFirst);
            default:
                return pick(enNames()) + pick(enFirst);
        }
    }

    private String fantasy() {
        final StringBuilder sb = new StringBuilder(pick(fantasyStart));
        if (rnd.nextInt(3) > 0) {
            sb.append(pick(fantasyMid));
        }
        sb.append(pick(fantasyEnd));
        return sb.toString();
    }

    private String gamer(boolean allowDigits) {
        final String word = capitalize(pick(enSecond));
        final int roll = allowDigits ? rnd.nextInt(6) : rnd.nextInt(3);
        switch (roll) {
            case 0:
                return "xX" + pick(enFirst) + word + "Xx";
            case 1:
                return allowDigits ? pick(enFirst) + "Lord" + pick(gamerNumbers) : "Pro" + pick(gamerRoles);
            case 2:
                return "Pro" + pick(gamerRoles);
            case 3:
                return alternateCase(pick(gamerRoles));
            case 4:
                return pick(enFirst) + word + pick(gamerNumbers);
            default:
                return (rnd.nextBoolean() ? "Mr" : "Lord") + pick(enFirst) + ((allowDigits && rnd.nextBoolean()) ? pick(gamerNumbers) : "");
        }
    }

    /** Length, characters, no 3 equal characters in a row (except the xX decoration), stop words. */
    public boolean isValid(String name) {
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
        for (String s : stopWords) {
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

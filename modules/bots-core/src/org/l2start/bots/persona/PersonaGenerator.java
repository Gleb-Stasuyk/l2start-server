package org.l2start.bots.persona;

import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Builds a {@link Persona} from a seed. Deterministic: the same seed, settings and set of taken names give the same
 * persona (tests, re-creation). Every part draws from its own Random derived from the seed, so changing one setting
 * (e.g. the class mix) does not change the names or traits of other seeds.
 */
public final class PersonaGenerator {
    public static final List<String> DEFAULT_TIMEZONES = List.of("Europe/Moscow:70", "Europe/Kaliningrad:10", "Europe/Samara:10", "Asia/Yekaterinburg:10");
    public static final List<String> DEFAULT_CHRONOTYPES = List.of("EVENING:45", "NIGHT_OWL:20", "WEEKEND:15", "ALLDAY:10", "MORNING:10");

    private final NameDictionary dict;
    private final Weights<NameGenerator.Style> styles;
    private final ClassPicker classes;
    private final Weights<String> timezones;
    private final Weights<Chronotype> chronotypes;
    /** Phase 7c: share of new personas that are PK (0..1). */
    private volatile double pkShare = 0.02;

    public PersonaGenerator(NameDictionary dict, Weights<NameGenerator.Style> styles, ClassPicker classes, Weights<String> timezones, Weights<Chronotype> chronotypes) {
        this.dict = dict;
        this.styles = styles;
        this.classes = classes;
        this.timezones = timezones;
        this.chronotypes = chronotypes;
    }

    public PersonaGenerator(NameDictionary dict) {
        this(dict, NameGenerator.parseStyles(NameGenerator.DEFAULT_STYLES), new ClassPicker(), parseTimezones(DEFAULT_TIMEZONES), parseChronotypes(DEFAULT_CHRONOTYPES));
    }

    public void setPkShare(double share) {
        pkShare = Math.max(0, Math.min(0.03, share)); // plan: 1–3 %
    }

    public double pkShare() {
        return pkShare;
    }

    public static Weights<String> parseTimezones(List<String> entries) {
        return Weights.parse(entries, k -> ZoneId.of(k).getId()); // fails on an unknown zone
    }

    public static Weights<Chronotype> parseChronotypes(List<String> entries) {
        return Weights.parse(entries, k -> Chronotype.valueOf(k.toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * @param classId a starting class, or -1 for the configured class mix
     * @param taken   rejects names that exist or are forbidden
     */
    public Persona generate(long seed, int classId, Predicate<String> taken) {
        final Random rClass = part(seed, 1);
        final Random rLook = part(seed, 2);
        final Random rTraits = part(seed, 3);
        final Random rTime = part(seed, 4);
        final Random rName = part(seed, 5);
        final int cls = classId < 0 ? classes.pickClass(rClass) : classId;
        if (!ClassPicker.isStartingClass(cls)) {
            throw new IllegalArgumentException("class " + cls + " is not a starting class");
        }
        final int future = ClassPicker.futureClass(cls, rClass);
        final ClassPicker.Look look = classes.look(cls, rLook);
        final NameGenerator.Named named = new NameGenerator(rName, dict, styles).next(taken, look.female());
        final Persona.Lang lang = lang(named.style(), rName);
        final boolean pk = part(seed, 6).nextDouble() < pkShare;
        return new Persona(Persona.VERSION, seed, named.name(), named.style().name(), lang, cls, future, look.female(), look.face(), look.hairStyle(), look.hairColor(), Traits.random(rTraits), chronotypes.pick(rTime), timezones.pick(rTime), pk);
    }

    /** Persona for a bot made before personas existed (step 3a): its real name, class and look, the rest from the seed. */
    public Persona adopt(long seed, String name, int classId, boolean female, int face, int hairStyle, int hairColor) {
        final Random rClass = part(seed, 1);
        final Random rTime = part(seed, 4);
        final Random rName = part(seed, 5);
        final String style = guessStyle(name);
        final Persona.Lang lang = style.equals("UNKNOWN") ? Persona.Lang.MIX : lang(NameGenerator.Style.valueOf(style), rName);
        final boolean pk = part(seed, 6).nextDouble() < pkShare;
        return new Persona(Persona.VERSION, seed, name, style, lang, classId, ClassPicker.futureClass(classId, rClass), female, face, hairStyle, hairColor, Traits.random(part(seed, 3)), chronotypes.pick(rTime), timezones.pick(rTime), pk);
    }

    private String guessStyle(String name) {
        final String low = name.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("xX") || name.startsWith("Pro") || name.contains("Lord")) {
            return NameGenerator.Style.GAMER.name();
        }
        for (String w : concat(dict.get("ru_names_male"), dict.get("ru_names_female"))) {
            if (low.startsWith(w.toLowerCase(java.util.Locale.ROOT))) {
                return NameGenerator.Style.RU.name();
            }
        }
        for (String w : dict.get("ru_words")) {
            if (low.contains(w.toLowerCase(java.util.Locale.ROOT))) {
                return NameGenerator.Style.RU.name();
            }
        }
        for (String w : dict.get("en_first")) {
            if (low.startsWith(w.toLowerCase(java.util.Locale.ROOT))) {
                return NameGenerator.Style.EN.name();
            }
        }
        for (String w : concat(dict.get("en_names_male"), dict.get("en_names_female"))) {
            if (low.startsWith(w.toLowerCase(java.util.Locale.ROOT))) {
                return NameGenerator.Style.EN.name();
            }
        }
        for (String w : dict.get("fantasy_start")) {
            if (name.startsWith(w)) {
                return NameGenerator.Style.FANTASY.name();
            }
        }
        return "UNKNOWN";
    }

    private static List<String> concat(List<String> a, List<String> b) {
        final List<String> l = new java.util.ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    /** RU-style names write in Russian; others are mostly Russian players with English nicknames. */
    private static Persona.Lang lang(NameGenerator.Style style, Random rnd) {
        final int r = rnd.nextInt(100);
        return switch (style) {
            case RU -> Persona.Lang.RU;
            case EN -> r < 40 ? Persona.Lang.EN : Persona.Lang.MIX;
            case FANTASY -> r < 50 ? Persona.Lang.RU : Persona.Lang.MIX;
            case GAMER -> r < 60 ? Persona.Lang.RU : Persona.Lang.MIX;
        };
    }

    private static Random part(long seed, int part) {
        long z = seed + (part * 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }
}

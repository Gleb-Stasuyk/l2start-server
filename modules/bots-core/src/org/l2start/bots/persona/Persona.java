package org.l2start.bots.persona;

import java.util.Locale;
import java.util.Map;

import org.l2start.platform.util.Json;

/**
 * The permanent character of a bot, generated from a seed (same seed + same settings = same persona).
 * Stored as JSON in mod_bots_core_persona.data; {@link #VERSION} grows when fields are added.
 */
public record Persona(int version, long seed, String name, String nameStyle, Lang lang, int classId, int futureClassId, boolean female, int face, int hairStyle, int hairColor, Traits traits, Chronotype chronotype, String timezone) {
    public static final int VERSION = 1;

    /** Chat language (used from phase 6): RU, EN, or a Russian player who mixes in English. */
    public enum Lang {
        RU, EN, MIX
    }

    public String toJson() {
        final StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        kv(sb, "version", version).append(',');
        kv(sb, "seed", String.valueOf(seed)).append(',');
        kv(sb, "name", name).append(',');
        kv(sb, "nameStyle", nameStyle).append(',');
        kv(sb, "lang", lang.name()).append(',');
        kv(sb, "classId", classId).append(',');
        kv(sb, "futureClassId", futureClassId).append(',');
        sb.append("\"female\":").append(female).append(',');
        kv(sb, "face", face).append(',');
        kv(sb, "hairStyle", hairStyle).append(',');
        kv(sb, "hairColor", hairColor).append(',');
        sb.append("\"traits\":{");
        kv(sb, "aggression", traits.aggression()).append(',');
        kv(sb, "sociability", traits.sociability()).append(',');
        kv(sb, "greed", traits.greed()).append(',');
        kv(sb, "patience", traits.patience()).append(',');
        kv(sb, "skill", traits.skill()).append(',');
        kv(sb, "chattiness", traits.chattiness()).append("},");
        kv(sb, "chronotype", chronotype.name()).append(',');
        kv(sb, "timezone", timezone);
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    public static Persona fromJson(String json) {
        final Map<String, Object> m = (Map<String, Object>) Json.parse(json);
        final Map<String, Object> t = (Map<String, Object>) m.get("traits");
        return new Persona(i(m, "version"), Long.parseLong(String.valueOf(m.get("seed"))), s(m, "name"), s(m, "nameStyle"), Lang.valueOf(s(m, "lang")), i(m, "classId"), i(m, "futureClassId"),
            Boolean.TRUE.equals(m.get("female")), i(m, "face"), i(m, "hairStyle"), i(m, "hairColor"),
            new Traits(d(t, "aggression"), d(t, "sociability"), d(t, "greed"), d(t, "patience"), d(t, "skill"), d(t, "chattiness")),
            Chronotype.valueOf(s(m, "chronotype")), s(m, "timezone"));
    }

    private static String s(Map<String, Object> m, String k) {
        final Object v = m.get(k);
        if (v == null) {
            throw new IllegalArgumentException("persona: no " + k);
        }
        return String.valueOf(v);
    }

    private static int i(Map<String, Object> m, String k) {
        final Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : Integer.parseInt(s(m, k));
    }

    private static double d(Map<String, Object> m, String k) {
        final Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(s(m, k));
    }

    private static StringBuilder kv(StringBuilder sb, String k, Object v) {
        sb.append('"').append(k).append("\":");
        if (v instanceof String str) {
            sb.append('"').append(str.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (v instanceof Double dv) {
            sb.append(String.format(Locale.ROOT, "%.2f", dv));
        } else {
            sb.append(v);
        }
        return sb;
    }

    /** Short text for //bots info. */
    public String summary() {
        return nameStyle + " name, " + lang + ", " + chronotype + ", " + timezone;
    }
}

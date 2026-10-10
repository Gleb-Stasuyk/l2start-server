package org.l2start.platform.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.l2start.platform.util.Durations;

/** One key of the "config" schema in module.json. */
public final class ConfigKeySpec {
    public enum Type {
        BOOL, INT, LONG, DOUBLE, STRING, ENUM, LIST, DURATION;

        static Type of(String s) {
            if (s == null) {
                return null;
            }
            switch (s.toLowerCase(Locale.ROOT)) {
                case "bool":
                case "boolean":
                    return BOOL;
                case "int":
                    return INT;
                case "long":
                    return LONG;
                case "double":
                    return DOUBLE;
                case "string":
                    return STRING;
                case "enum":
                    return ENUM;
                case "list":
                    return LIST;
                case "duration":
                    return DURATION;
                default:
                    return null;
            }
        }
    }

    private final String name;
    private final Type type;
    private final String defaultValue;
    private final String min;
    private final String max;
    private final List<String> values;
    private final String description;
    private final boolean reloadable;

    public ConfigKeySpec(String name, Type type, String defaultValue, String min, String max, List<String> values, String description, boolean reloadable) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.values = values == null ? List.of() : List.copyOf(values);
        this.description = description == null ? "" : description;
        this.reloadable = reloadable;
    }

    public static Type parseType(String s) {
        return Type.of(s);
    }

    public String name() { return name; }
    public Type type() { return type; }
    public String defaultValue() { return defaultValue; }
    public String min() { return min; }
    public String max() { return max; }
    public List<String> values() { return values; }
    public String description() { return description; }
    public boolean reloadable() { return reloadable; }

    /** Validates and normalizes a raw value; throws IllegalArgumentException with a readable message. */
    public String normalize(String raw) {
        final String v = raw == null ? "" : raw.trim();
        switch (type) {
            case BOOL: {
                final String l = v.toLowerCase(Locale.ROOT);
                if (l.equals("true") || l.equals("on") || l.equals("yes") || l.equals("1")) {
                    return "true";
                }
                if (l.equals("false") || l.equals("off") || l.equals("no") || l.equals("0")) {
                    return "false";
                }
                throw new IllegalArgumentException(name + ": '" + v + "' is not a boolean (true/false)");
            }
            case INT:
            case LONG: {
                final long n;
                try {
                    n = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + ": '" + v + "' is not an integer");
                }
                if (type == Type.INT && (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)) {
                    throw new IllegalArgumentException(name + ": " + v + " is out of int range");
                }
                if (min != null && n < Long.parseLong(min)) {
                    throw new IllegalArgumentException(name + ": " + v + " < min " + min);
                }
                if (max != null && n > Long.parseLong(max)) {
                    throw new IllegalArgumentException(name + ": " + v + " > max " + max);
                }
                return Long.toString(n);
            }
            case DOUBLE: {
                final double d;
                try {
                    d = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + ": '" + v + "' is not a number");
                }
                if (min != null && d < Double.parseDouble(min)) {
                    throw new IllegalArgumentException(name + ": " + v + " < min " + min);
                }
                if (max != null && d > Double.parseDouble(max)) {
                    throw new IllegalArgumentException(name + ": " + v + " > max " + max);
                }
                return v;
            }
            case ENUM: {
                for (String allowed : values) {
                    if (allowed.equalsIgnoreCase(v)) {
                        return allowed;
                    }
                }
                throw new IllegalArgumentException(name + ": '" + v + "' is not one of " + values);
            }
            case DURATION: {
                final long ms = Durations.parse(v).toMillis();
                if (min != null && ms < Durations.parse(min).toMillis()) {
                    throw new IllegalArgumentException(name + ": " + v + " < min " + min);
                }
                if (max != null && ms > Durations.parse(max).toMillis()) {
                    throw new IllegalArgumentException(name + ": " + v + " > max " + max);
                }
                return v;
            }
            case LIST: {
                final List<String> parts = new ArrayList<>();
                for (String p : v.split(",")) {
                    if (!p.trim().isEmpty()) {
                        parts.add(p.trim());
                    }
                }
                return String.join(",", parts);
            }
            default:
                return v;
        }
    }
}

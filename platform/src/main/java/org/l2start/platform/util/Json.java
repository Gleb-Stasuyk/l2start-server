package org.l2start.platform.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser (RFC 8259) for module manifests. No dependencies.
 * Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link Long} (integral) or {@link Double}.
 */
public final class Json {
    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        String t = text;
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }
        final Json p = new Json(t);
        p.ws();
        final Object v = p.value();
        p.ws();
        if (p.pos != p.s.length()) {
            throw p.error("unexpected trailing content");
        }
        return v;
    }

    private Object value() {
        if (pos >= s.length()) {
            throw error("unexpected end of input");
        }
        final char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        final Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            ws();
            if (!peek('"')) {
                throw error("expected string key");
            }
            final String key = string();
            ws();
            expect(':');
            ws();
            if (map.containsKey(key)) {
                throw error("duplicate key \"" + key + "\"");
            }
            map.put(key, value());
            ws();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        final List<Object> list = new ArrayList<>();
        pos++; // [
        ws();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        pos++; // "
        final StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            final char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= s.length()) {
                    throw error("unterminated escape");
                }
                final char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw error("bad unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad unicode escape");
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("bad escape \\" + e);
                }
            } else if (c < 0x20) {
                throw error("control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        final int start = pos;
        if (peek('-')) {
            pos++;
        }
        boolean integral = true;
        digits();
        if (peek('.')) {
            integral = false;
            pos++;
            digits();
        }
        if (peek('e') || peek('E')) {
            integral = false;
            pos++;
            if (peek('+') || peek('-')) {
                pos++;
            }
            digits();
        }
        final String num = s.substring(start, pos);
        try {
            if (integral) {
                return Long.parseLong(num);
            }
            return Double.parseDouble(num);
        } catch (NumberFormatException ex) {
            throw error("bad number " + num);
        }
    }

    private void digits() {
        final int start = pos;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw error("digit expected");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, pos)) {
            throw error("unexpected token");
        }
        pos += word.length();
        return v;
    }

    private boolean peek(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private void ws() {
        while (pos < s.length()) {
            final char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private JsonException error(String msg) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new JsonException(msg + " at line " + line + ", column " + col);
    }

    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }
}

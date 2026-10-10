package org.l2start.platform.db;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into statements at ';' outside of quotes and comments. Comments are removed.
 * Supports '...', "...", `...`, "-- " / "#" line comments and C-style comments. No DELIMITER / procedures.
 */
public final class SqlSplitter {
    private SqlSplitter() {
    }

    public static List<String> split(String script) {
        final List<String> out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        final String s = script.replace("\uFEFF", "");
        final int n = s.length();
        int i = 0;
        while (i < n) {
            final char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                final int end = quoted(s, i, c);
                cur.append(s, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-' && (i + 2 >= n || Character.isWhitespace(s.charAt(i + 2)))) {
                i = lineEnd(s, i);
            } else if (c == '#') {
                i = lineEnd(s, i);
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                final int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                cur.append(' ');
            } else if (c == ';') {
                add(out, cur);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        add(out, cur);
        return out;
    }

    private static int quoted(String s, int start, char q) {
        int i = start + 1;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '\\' && q != '`') {
                i += 2;
                continue;
            }
            if (c == q) {
                if (i + 1 < s.length() && s.charAt(i + 1) == q) {
                    i += 2; // doubled quote
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("unterminated " + q + " quote");
    }

    private static int lineEnd(String s, int i) {
        final int nl = s.indexOf('\n', i);
        return nl < 0 ? s.length() : nl;
    }

    private static void add(List<String> out, StringBuilder cur) {
        final String stmt = cur.toString().trim();
        if (!stmt.isEmpty()) {
            out.add(stmt);
        }
        cur.setLength(0);
    }
}

package org.l2start.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesAllTypes() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> m = (Map<String, Object>) Json.parse("{\"s\":\"x\",\"i\":42,\"n\":-1.5e2,\"t\":true,\"f\":false,\"z\":null,\"a\":[1,[2]],\"o\":{}}");
        assertEquals("x", m.get("s"));
        assertEquals(42L, m.get("i"));
        assertEquals(-150.0, m.get("n"));
        assertEquals(true, m.get("t"));
        assertEquals(false, m.get("f"));
        assertTrue(m.containsKey("z"));
        assertNull(m.get("z"));
        assertEquals(List.of(1L, List.of(2L)), m.get("a"));
        assertEquals(Map.of(), m.get("o"));
    }

    @Test
    void keepsKeyOrder() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> m = (Map<String, Object>) Json.parse("{\"b\":1,\"a\":2,\"c\":3}");
        assertEquals(List.of("b", "a", "c"), List.copyOf(m.keySet()));
    }

    @Test
    void escapesAndUnicode() {
        assertEquals("a\"b\\c/d\n\tЖ€", Json.parse("\"a\\\"b\\\\c\\/d\\n\\tЖ\\u20ac\""));
    }

    @Test
    void bomIsIgnored() {
        assertEquals(1L, Json.parse("\uFEFF 1 "));
    }

    @Test
    void errorsHaveLineAndColumn() {
        final Json.JsonException e = assertThrows(Json.JsonException.class, () -> Json.parse("{\n  \"a\": 1,\n  \"b\" 2\n}"));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }

    @Test
    void rejectsBadInput() {
        for (String bad : new String[] { "", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "tru", "01x", "\"abc", "{\"a\":1}{", "{\"a\":1,\"a\":2}", "\"\u0001\"" }) {
            assertThrows(Json.JsonException.class, () -> Json.parse(bad), bad);
        }
    }
}

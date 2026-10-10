package org.l2start.platform.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.l2start.platform.TestModules;

class MigrationTest {
    @TempDir
    Path dir;

    @Test
    void scannerOrdersAndValidatesNames() throws Exception {
        TestModules.write(dir.resolve("V010__ten.sql"), "SELECT 10;");
        TestModules.write(dir.resolve("V2__two_words.sql"), "SELECT 2;");
        TestModules.write(dir.resolve("V001__one.sql"), "SELECT 1;");
        TestModules.write(dir.resolve("remove.sql"), "DROP TABLE x;");
        TestModules.write(dir.resolve("V3-bad.sql"), "x");
        TestModules.write(dir.resolve("notes.txt"), "x");
        final List<String> errors = new ArrayList<>();
        final List<Migration> m = MigrationScanner.scan(dir, errors);
        assertEquals(List.of(1, 2, 10), m.stream().map(Migration::version).toList());
        assertEquals("two words", m.get(1).description());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("V3-bad.sql"));
    }

    @Test
    void duplicateVersions() throws Exception {
        TestModules.write(dir.resolve("V1__a.sql"), "a");
        TestModules.write(dir.resolve("V001__b.sql"), "b");
        final List<String> errors = new ArrayList<>();
        MigrationScanner.scan(dir, errors);
        assertTrue(errors.get(0).contains("used twice"));
    }

    @Test
    void checksumIgnoresLineEndingsAndBom() {
        assertEquals(MigrationScanner.checksum("a;\nb;\n"), MigrationScanner.checksum("\uFEFFa;\r\nb;\r\n"));
        assertFalse(MigrationScanner.checksum("a;").equals(MigrationScanner.checksum("a; ")));
        assertEquals(64, MigrationScanner.checksum("").length());
    }

    private static Migration m(int v, String sql) {
        return MigrationScanner.of(v, "d" + v, "V" + v + "__d.sql", sql);
    }

    private static AppliedMigration ok(Migration m) {
        return new AppliedMigration(m.version(), m.script(), m.checksum(), true, null);
    }

    @Test
    void plannerPendingOnly() {
        final Migration a = m(1, "a"), b = m(2, "b"), c = m(3, "c");
        final Map<Integer, AppliedMigration> applied = new LinkedHashMap<>();
        applied.put(1, ok(a));
        final MigrationPlanner.Plan p = MigrationPlanner.plan(List.of(a, b, c), applied);
        assertTrue(p.ok());
        assertEquals(List.of(b, c), p.pending());
        assertTrue(MigrationPlanner.plan(List.of(a), Map.of(1, ok(a))).pending().isEmpty());
        assertEquals(3, MigrationPlanner.plan(List.of(a, b, c), Map.of()).pending().size());
    }

    @Test
    void plannerChecksumMismatchAndFailedEarlier() {
        final Migration a = m(1, "a");
        MigrationPlanner.Plan p = MigrationPlanner.plan(List.of(m(1, "changed")), Map.of(1, ok(a)));
        assertFalse(p.ok());
        assertTrue(p.errors().get(0).contains("changed after it had been applied"));
        p = MigrationPlanner.plan(List.of(a), Map.of(1, new AppliedMigration(1, a.script(), a.checksum(), false, "Syntax error")));
        assertFalse(p.ok());
        assertTrue(p.errors().get(0).contains("failed earlier (Syntax error)"));
    }

    @Test
    void plannerWarnings() {
        final Migration a = m(1, "a"), c = m(3, "c"), b = m(2, "b");
        final MigrationPlanner.Plan p = MigrationPlanner.plan(List.of(a, b), Map.of(3, ok(c)));
        assertTrue(p.ok());
        assertEquals(2, p.pending().size());
        assertEquals(3, p.warnings().size(), p.warnings().toString()); // V3 vanished, V1 and V2 older than V3
    }

    @Test
    void splitter() {
        assertEquals(List.of("CREATE TABLE a (x INT)", "INSERT INTO a VALUES (1)"), SqlSplitter.split("-- head\nCREATE TABLE a (x INT);\n\n# c\nINSERT INTO a VALUES (1); -- tail\n;\n"));
        assertEquals(List.of("INSERT INTO t VALUES ('a;b', \"c;d\", `e;f`, 'it''s', 'x\\'y;')"), SqlSplitter.split("INSERT INTO t VALUES ('a;b', \"c;d\", `e;f`, 'it''s', 'x\\'y;');"));
        assertEquals(List.of("SELECT 1", "SELECT 2"), SqlSplitter.split("SELECT 1; /* multi;\nline */ SELECT 2"));
        assertEquals(List.of("SELECT 1 --x"), SqlSplitter.split("SELECT 1 --x;")); // MySQL needs '-- '
        assertEquals(List.of("SELECT 5-1"), SqlSplitter.split("SELECT 5-1;"));
        assertEquals(List.of("SELECT 1"), SqlSplitter.split("SELECT 1 -- comment;\n;"));
        assertTrue(SqlSplitter.split("  -- only comments\n/* x */ ; ;").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SqlSplitter.split("SELECT 'open;"));
    }

    @Test
    void helloMigrationIsSplittable() throws Exception {
        final List<String> errors = new ArrayList<>();
        final List<Migration> m = MigrationScanner.scan(Path.of("modules/hello/sql"), errors);
        assertTrue(errors.isEmpty());
        assertEquals(1, m.size());
        assertEquals(1, SqlSplitter.split(m.get(0).sql()).size());
    }
}

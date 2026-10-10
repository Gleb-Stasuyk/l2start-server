package org.l2start.platform.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds V###__description.sql files. Other *.sql files (e.g. remove.sql) are ignored; a bad V-file name is an error. */
public final class MigrationScanner {
    public static final Pattern NAME = Pattern.compile("^V(\\d{1,9})__([A-Za-z0-9_]+)\\.sql$");

    private MigrationScanner() {
    }

    public static List<Migration> scan(Path dir, List<String> errors) throws IOException {
        final List<Migration> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                final String name = f.getFileName().toString();
                if (!name.startsWith("V") && !name.startsWith("v")) {
                    continue;
                }
                final Matcher m = NAME.matcher(name);
                if (!m.matches()) {
                    errors.add("migration file '" + name + "' must be named V<number>__<description>.sql");
                    continue;
                }
                out.add(of(Integer.parseInt(m.group(1)), m.group(2).replace('_', ' '), name, Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        out.sort(Comparator.comparingInt(Migration::version));
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).version() == out.get(i - 1).version()) {
                errors.add("migration version " + out.get(i).version() + " is used twice (" + out.get(i - 1).script() + ", " + out.get(i).script() + ")");
            }
        }
        return out;
    }

    public static Migration of(int version, String description, String script, String sql) {
        return new Migration(version, description, script, checksum(sql), sql);
    }

    /** SHA-256 of the file with BOM removed and CRLF normalized to LF (git checkouts on Windows must not change it). */
    public static String checksum(String sql) {
        final String normalized = sql.replace("\uFEFF", "").replace("\r\n", "\n");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

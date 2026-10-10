package org.l2start.platform.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Light pre-scan of module XML: NPC ids from &lt;npc id="..."&gt;, multisell/buylist ids from numeric file names. */
public final class IdScanner {
    private static final Pattern NPC = Pattern.compile("<npc\\s[^>]*?\\bid\\s*=\\s*\"(\\d+)\"");
    private static final Pattern NUMERIC_FILE = Pattern.compile("^(\\d+)\\.xml$", Pattern.CASE_INSENSITIVE);

    private IdScanner() {
    }

    public static TreeSet<Integer> scan(DataType type, Path dir, List<String> errors) throws IOException {
        final TreeSet<Integer> ids = new TreeSet<>();
        if (!type.hasIds() || !Files.isDirectory(dir)) {
            return ids;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                final String name = f.getFileName().toString();
                if (!name.toLowerCase().endsWith(".xml")) {
                    continue;
                }
                if (type == DataType.NPCS) {
                    final Matcher m = NPC.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (m.find()) {
                        final int id = Integer.parseInt(m.group(1));
                        if (!ids.add(id)) {
                            errors.add("npcs: id " + id + " is defined twice (" + name + ")");
                        }
                    }
                } else {
                    final Matcher m = NUMERIC_FILE.matcher(name);
                    if (!m.matches()) {
                        errors.add(type.key() + ": file name '" + name + "' must be the numeric list id (e.g. 95000.xml)");
                        continue;
                    }
                    if (!ids.add(Integer.parseInt(m.group(1)))) {
                        errors.add(type.key() + ": list id " + m.group(1) + " is defined twice");
                    }
                }
            }
        }
        return ids;
    }
}

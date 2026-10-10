package org.l2start.bots.persona;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Word lists for {@link NameGenerator}, from {@code modules/bots-core/data/names/*.txt} (one entry per line, # comments).
 * Editable without a rebuild: read on start and on {@code //module reload bots-core}.
 */
public final class NameDictionary {
    public static final List<String> FILES = List.of("ru_names_male", "ru_names_female", "ru_words", "ru_suffixes", "en_first", "en_second", "en_names_male", "en_names_female", "fantasy_start", "fantasy_mid", "fantasy_end", "gamer_roles", "gamer_numbers", "stop_words");

    private final Map<String, List<String>> lists;

    private NameDictionary(Map<String, List<String>> lists) {
        this.lists = lists;
    }

    public static NameDictionary load(Path dir) throws IOException {
        final Map<String, List<String>> m = new TreeMap<>();
        for (String f : FILES) {
            final Path p = dir.resolve(f + ".txt");
            if (!Files.isRegularFile(p)) {
                throw new IOException("missing " + p);
            }
            final LinkedHashSet<String> words = new LinkedHashSet<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                final String w = line.strip();
                if (w.isEmpty() || w.startsWith("#")) {
                    continue;
                }
                if (!w.matches("[A-Za-z0-9]+")) {
                    throw new IOException(p.getFileName() + ": '" + w + "' (only Latin letters and digits)");
                }
                words.add(f.equals("stop_words") ? w.toLowerCase(Locale.ROOT) : w);
            }
            if (words.isEmpty()) {
                throw new IOException(p.getFileName() + " is empty");
            }
            m.put(f, List.copyOf(new ArrayList<>(words)));
        }
        return new NameDictionary(m);
    }

    public List<String> get(String list) {
        final List<String> l = lists.get(list);
        if (l == null) {
            throw new IllegalArgumentException("no word list " + list);
        }
        return l;
    }

    public int size() {
        return lists.values().stream().mapToInt(List::size).sum();
    }
}

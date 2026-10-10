package org.l2start.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds module folders for tests. */
public final class TestModules {
    private TestModules() {
    }

    /** Minimal valid data module with extra JSON fields appended (e.g. ", \"dependencies\": [...]"). */
    public static Path module(Path root, String id, String version, String extra) throws IOException {
        final Path dir = root.resolve(id);
        Files.createDirectories(dir);
        write(dir.resolve("module.json"), "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"" + version + "\",\"kind\":\"data\",\"platform\":\"^1.0\"" + extra + "}");
        return dir;
    }

    public static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}

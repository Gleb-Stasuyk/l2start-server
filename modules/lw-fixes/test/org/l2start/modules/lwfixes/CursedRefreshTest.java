package org.l2start.modules.lwfixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleManifest;

class CursedRefreshTest {
    @Test
    void refreshOnlyWhenWeaponLeftAndOwnerOnline() {
        assertTrue(CursedRefresh.needed(true, false));
        assertFalse(CursedRefresh.needed(true, true), "weapon still held: nothing dropped");
        assertFalse(CursedRefresh.needed(false, false), "owner logged out");
    }

    @Test
    void manifestIsValidAndOnInBothProfiles() throws Exception {
        final Path dir = Path.of("modules/lw-fixes");
        final ModuleManifest m = ManifestReader.read(dir);
        assertEquals("lw-fixes", m.id());
        final String json = Files.readString(dir.resolve("module.json"));
        assertTrue(json.contains("\"defaultEnabled\": { \"solo\": true, \"online\": true }"));
    }
}

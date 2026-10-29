package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Wiring checks for fixes found in the live test (stock behaviour that unit tests cannot run). */
class LiveFixesSourceTest {
    static String read(String p) throws Exception {
        return Files.readString(Path.of(p));
    }

    /** Clan notifies ON_PLAYER_CLAN_JOIN/LEFT on the global container only: a players-container listener never fired. */
    @Test
    void clanEventsAreGlobal() throws Exception {
        final String clan = read("core/java/org/l2jmobius/gameserver/entity/clan/Clan.java");
        assertTrue(clan.contains("notifyEventAsync(new OnPlayerClanJoin(member, this));"));
        assertTrue(clan.contains("notifyEventAsync(new OnPlayerClanLeft(exMember, this));"));
        final String m = read("modules/bots-core/src/org/l2start/bots/core/BotsCoreModule.java").replaceAll("\\s+", " ");
        assertTrue(m.contains("OnPlayerClanJoin> onGlobal("));
        assertTrue(m.contains("OnPlayerClanLeft> onGlobal("));
        assertFalse(m.contains("OnPlayerClanJoin> onPlayers("));
        assertFalse(m.contains("OnPlayerClanLeft> onPlayers("));
    }

    /** Class/level are stored with the skills given in prepare (else: "has invalid skill" after a server stop). */
    @Test
    void prepareStoresClassWithSkills() throws Exception {
        final String s = read("modules/bots-core/src/org/l2start/bots/core/Materializer.java");
        final int give = s.indexOf("giveAvailableSkills(");
        final int persist = s.indexOf("persist(p);");
        assertTrue((give > 0) && (persist > give));
        assertTrue(s.contains("p.store(false);"));
    }

    /** Hunter / RB prepare stocks class-skill reagents after cleanup; olympiad path is untouched. */
    @Test
    void hunterPrepareStocksSkillReagentsOlympiadDoesNot() throws Exception {
        final String s = read("modules/bots-core/src/org/l2start/bots/core/Materializer.java");
        final int cleanup = s.indexOf("cleanup(p, pk);");
        final int hunter = s.indexOf("skillReagents(p)");
        final int olympiad = s.indexOf("olympiadShots(p)");
        assertTrue((cleanup > 0) && (hunter > cleanup), "reagents must come after cleanup or they vanish");
        assertTrue(s.contains("if (!olympiad)"));
        assertTrue(olympiad > 0);
        assertTrue(s.contains("p.getSkills().get(1148)")); // olympiad still only Death Spike bones
        final String recruit = read("modules/bots-core/src/org/l2start/bots/core/RbRecruit.java");
        assertTrue(recruit.contains("restockReagents(r)"));
    }
}

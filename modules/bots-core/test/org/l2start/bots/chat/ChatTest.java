package org.l2start.bots.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Memory;

/** Phase 6a: phrase banks, the template brain, the humanizer and the memory of players. */
class ChatTest {
    static final Path FILE = Path.of("modules/bots-core/data/chat/phrases.xml");

    @Test
    void everyIntentSpeaksBothLanguages() throws Exception {
        final Phrases p = Phrases.load(FILE);
        assertTrue(p.intents().size() >= 15, "intents: " + p.intents());
        for (String i : List.of("PARTY_HELLO", "DECLINE_SOLO", "DECLINE_LEAVING", "DECLINE_LEVEL", "DECLINE_FULL", "DECLINE_AGAIN", "ACK", "HUH", "BUFFED", "NO_RES",
            "THANKS_RES", "RESSED", "WARN_LEAVE", "BYE", "STAY_HERE", "WAIT", "AFK_BYE", "LEADER", "GRATS", "LFP", "LFM", "LFP_REPLY", "ASK_OK", "ASK_NO", "PAIR_REDIRECT",
            "GREET", "WHISPER_BYE", "WHISPER_THANKS", "WHISPER_SORRY", "HOW_ARE_YOU", "ASK_LEVEL", "ASK_CLASS", "ASK_SPOT", "WHERE_TO_HUNT", "ASK_RES", "ASK_BUFF",
            "INSULT", "WHISPER_HUH", "BUSY_COMBAT", "THANKS_BUFF", "SORRY_KS", "MEMORY_PARTY")) {
            assertFalse(p.lines(i, "RU").isEmpty(), i + " RU");
            assertFalse(p.lines(i, "EN").isEmpty(), i + " EN");
        }
    }

    @Test
    void sameLineNotAgainWithinTenMinutes() throws Exception {
        final long[] now = { 0 };
        final TemplateBrain b = new TemplateBrain(Phrases.load(FILE), () -> now[0], new Random(1));
        final int n = b.phrases().lines("ACK", "RU").size();
        final Set<String> seen = new HashSet<>();
        final ChatRequest r = new ChatRequest(7, "ACK", "PARTY", "RU", 0.5, "", List.of(), Map.of());
        for (int i = 0; i < n; i++) {
            seen.add(b.pick(r).orElseThrow());
        }
        assertEquals(new HashSet<>(b.phrases().lines("ACK", "RU")), seen, "every line once before any repeats");
        assertTrue(b.pick(r).isEmpty(), "all used: silent rather than repeating");
        assertTrue(b.pick(new ChatRequest(8, "ACK", "PARTY", "RU", 0.5, "", List.of(), Map.of())).isPresent(), "another bot has its own lines");
        now[0] += TemplateBrain.REPEAT_MS;
        assertTrue(b.pick(r).isPresent(), "after 10 min the lines come back");
        assertTrue(b.pick(new ChatRequest(7, "NO_SUCH", "PARTY", "RU", 0.5, "", List.of(), Map.of())).isEmpty());
    }

    @Test
    void variablesAndLanguages() {
        assertEquals("hi Gleb", Phrases.fill("hi {player}", Map.of("player", "Gleb")));
        assertEquals("EN", Phrases.langOf("EN", new Random(1)));
        assertEquals("RU", Phrases.langOf("RU", new Random(1)));
        int en = 0;
        final Random r = new Random(5);
        for (int i = 0; i < 1000; i++) {
            en += "EN".equals(Phrases.langOf("MIX", r)) ? 1 : 0;
        }
        assertTrue((en > 200) && (en < 400), "MIX: about 30 % English, got " + en);
    }

    @Test
    void lfpTemplatesIncludeProfessionAndLevel() throws Exception {
        final Phrases p = Phrases.load(FILE);
        assertTrue(p.lines("LFP", "RU").stream().anyMatch(l -> l.contains("{class}") && l.contains("{level}")));
        assertTrue(p.lines("LFP", "EN").stream().anyMatch(l -> l.contains("{class}") && l.contains("{level}")));
        final String filled = Phrases.fill("LFP {class} {level}", Map.of("class", "Bishop", "level", "58"));
        assertEquals("LFP Bishop 58", filled);
        assertEquals("Bladedancer", ClassLabels.name(34, "EN"));
        assertTrue(Phrases.fill("{class} {level} ищет пати", Map.of("class", ClassLabels.name(34, "RU"), "level", "62")).contains("62"));
    }

    @Test
    void humanizerTypesLikeAPerson() {
        final Random r = new Random(3);
        for (int i = 0; i < 200; i++) {
            final long shortT = Humanizer.typingMs("ок", 400, r);
            final long longT = Humanizer.typingMs("спасибо за пати, я офф, всем удачи", 400, r);
            assertTrue((shortT >= Humanizer.MIN_TYPING_MS) && (longT <= Humanizer.MAX_TYPING_MS));
            assertTrue(longT > shortT, "a longer line takes longer");
        }
        int typos = 0;
        int lower = 0;
        for (int i = 0; i < 2000; i++) {
            final List<String> l = Humanizer.style("Привет всем.", 0.2, r);
            if (l.size() == 2) {
                typos++;
                assertTrue(l.get(1).startsWith("*"), l.toString());
            }
            lower += Character.isLowerCase(l.get(0).charAt(0)) ? 1 : 0;
        }
        assertTrue((typos > 20) && (typos < 200), "rare typos: " + typos);
        assertTrue(lower > 1500, "mostly lower case: " + lower);
    }

    @Test
    void memoryClampsAndTags() {
        final Memory.MapMemory m = new Memory.MapMemory(() -> 5);
        m.note(1, 80, Memory.Tag.PARTY, true);
        m.note(1, 80, Memory.Tag.REZZED_ME, true);
        m.partyMinutes(1, 12);
        final Memory.Of o = m.of(1);
        assertEquals(100, o.affinity());
        assertEquals(2, o.met());
        assertEquals(12, o.partyMinutes());
        assertTrue(o.has(Memory.Tag.PARTY) && o.has(Memory.Tag.REZZED_ME));
        m.forget(1, Memory.Tag.PARTY);
        assertFalse(m.of(1).has(Memory.Tag.PARTY));
        assertEquals(0, m.of(2).affinity(), "a stranger");
    }
}

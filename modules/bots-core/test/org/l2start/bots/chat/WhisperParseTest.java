package org.l2start.bots.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Phase 6e: whisper intent parsing (EQUALS / STARTS_WITH / CONTAINS, RU + EN). */
class WhisperParseTest {
    @Test
    void greetByeThanksSorry() {
        assertEquals(WhisperParse.Intent.GREET, WhisperParse.parse("привет"));
        assertEquals(WhisperParse.Intent.GREET, WhisperParse.parse("Hi!"));
        assertEquals(WhisperParse.Intent.GREET, WhisperParse.parse("hello there"));
        assertEquals(WhisperParse.Intent.BYE, WhisperParse.parse("пока"));
        assertEquals(WhisperParse.Intent.BYE, WhisperParse.parse("bb"));
        assertEquals(WhisperParse.Intent.THANKS, WhisperParse.parse("спс"));
        assertEquals(WhisperParse.Intent.THANKS, WhisperParse.parse("thanks"));
        assertEquals(WhisperParse.Intent.SORRY, WhisperParse.parse("сорри"));
        assertEquals(WhisperParse.Intent.SORRY, WhisperParse.parse("sorry"));
    }

    @Test
    void howAreYouAndAsks() {
        assertEquals(WhisperParse.Intent.HOW_ARE_YOU, WhisperParse.parse("как дела?"));
        assertEquals(WhisperParse.Intent.HOW_ARE_YOU, WhisperParse.parse("how are you"));
        assertEquals(WhisperParse.Intent.ASK_LEVEL, WhisperParse.parse("какой лвл?"));
        assertEquals(WhisperParse.Intent.ASK_LEVEL, WhisperParse.parse("what level are you"));
        assertEquals(WhisperParse.Intent.ASK_LEVEL, WhisperParse.parse("а сколько тебе?"));
        assertEquals(WhisperParse.Intent.ASK_CLASS, WhisperParse.parse("какой класс?"));
        assertEquals(WhisperParse.Intent.ASK_CLASS, WhisperParse.parse("what class"));
        assertEquals(WhisperParse.Intent.ASK_SPOT, WhisperParse.parse("где качаешься?"));
        assertEquals(WhisperParse.Intent.ASK_SPOT, WhisperParse.parse("where are you"));
        assertEquals(WhisperParse.Intent.WHERE_TO_HUNT, WhisperParse.parse("куда качаться"));
        assertEquals(WhisperParse.Intent.WHERE_TO_HUNT, WhisperParse.parse("where to hunt"));
        assertEquals(WhisperParse.Intent.ASK_RES, WhisperParse.parse("заресай"));
        assertEquals(WhisperParse.Intent.ASK_RES, WhisperParse.parse("can you res"));
        assertEquals(WhisperParse.Intent.ASK_BUFF, WhisperParse.parse("бафни"));
        assertEquals(WhisperParse.Intent.ASK_BUFF, WhisperParse.parse("buff me"));
    }

    @Test
    void partyAskAndInsult() {
        assertEquals(WhisperParse.Intent.ASK_PARTY, WhisperParse.parse("+"));
        assertEquals(WhisperParse.Intent.ASK_PARTY, WhisperParse.parse("можно к вам?"));
        assertEquals(WhisperParse.Intent.ASK_PARTY, WhisperParse.parse("invite me"));
        assertEquals(WhisperParse.Intent.INSULT, WhisperParse.parse("нуб"));
        assertEquals(WhisperParse.Intent.INSULT, WhisperParse.parse("idiot"));
        assertEquals(WhisperParse.Intent.INSULT, WhisperParse.parse("отстань"));
        assertEquals(WhisperParse.Intent.INSULT, WhisperParse.parse("stop spam"));
    }

    @Test
    void leaveAlonePhrases() {
        assertTrue(WhisperParse.isLeaveAlone("отстань"));
        assertTrue(WhisperParse.isLeaveAlone("не пиши мне"));
        assertTrue(WhisperParse.isLeaveAlone("stop spam please"));
        assertTrue(WhisperParse.isLeaveAlone("shut up"));
        assertFalse(WhisperParse.isLeaveAlone("привет"));
        assertFalse(WhisperParse.isLeaveAlone("нуб"));
    }

    @Test
    void unknownFloodAndLang() {
        assertEquals(WhisperParse.Intent.UNKNOWN, WhisperParse.parse("абракадабра xyz"));
        assertEquals(WhisperParse.Intent.UNKNOWN, WhisperParse.parse(null));
        assertEquals(WhisperParse.Intent.UNKNOWN, WhisperParse.parse(""));
        assertTrue(WhisperParse.isFlood("AAAAAAAAAAAAAAAA"));
        assertFalse(WhisperParse.isFlood("привет"));
        assertEquals(WhisperParse.Intent.UNKNOWN, WhisperParse.parse("HELLO ARE YOU THERE NOW???"));
        assertEquals("RU", WhisperParse.langOf("привет, где качаешься?"));
        assertEquals("EN", WhisperParse.langOf("hi where are you"));
        assertEquals("EN", WhisperParse.langOf(null));
    }
}

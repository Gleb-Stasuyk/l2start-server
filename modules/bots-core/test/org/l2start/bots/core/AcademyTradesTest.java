package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Academy join reward offered by trade; declined -> whisper, the player opens it himself. */
class AcademyTradesTest {
    @Test
    void acceptedRequestGoesToTrading() {
        assertEquals(AcademyTrades.State.TRADING, AcademyTrades.afterAsk(true, false, 1000));
    }

    @Test
    void declinedOrIgnoredGoesToWaiting() {
        assertEquals(AcademyTrades.State.WAITING, AcademyTrades.afterAsk(false, true, 1000), "declined");
        assertEquals(AcademyTrades.State.WAITING, AcademyTrades.afterAsk(false, false, AcademyTrades.ASK_TIMEOUT_MS + 1), "ignored");
        assertEquals(AcademyTrades.State.ASKED, AcademyTrades.afterAsk(false, false, 1000), "still thinking");
    }

    @Test
    void paidOnlyWhenAdenaLeftTheBot() {
        assertTrue(AcademyTrades.paid(150_000, 100_000, 50_000));
        assertFalse(AcademyTrades.paid(150_000, 150_000, 50_000), "cancelled trade");
    }

    @Test
    void whisperIsRussianAndTellsToOpenTrade() {
        assertTrue(AcademyTrades.WHISPER_RU.matches(".*[а-яА-Я].*"));
        assertTrue(AcademyTrades.WHISPER_RU.contains("трейд"));
    }
}

package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Olympiad bots stood idle and every match ended in a 6-minute draw: stock ports the contestants to stadium x +900 and
 * x -900 (1800 apart), the bot view radius is 1500, so the rival was never in {@code players()} and the brain waited for
 * "no rival" forever.
 */
class OlympiadViewRadiusTest {
    static int defaultViewRadius() throws Exception {
        final String json = Files.readString(Path.of("modules/bots-core/module.json"));
        final Matcher m = Pattern.compile("\"ViewRadius\"\\s*:\\s*\\{[^}]*\"default\"\\s*:\\s*(\\d+)").matcher(json);
        assertTrue(m.find(), "ViewRadius default in module.json");
        return Integer.parseInt(m.group(1));
    }

    @Test
    void stadiumSpreadIsBeyondDefaultView() throws Exception {
        final String stock = Files.readString(Path.of("core/java/org/l2jmobius/gameserver/mechanics/olympiad/OlympiadGame.java"));
        assertTrue(stock.contains("_stadiumPort[0] + 900") && stock.contains("_stadiumPort[0] - 900"), "stock stadium spread is still +-900");
        assertTrue(MobiusWorldView.OLYMPIAD_SPREAD > defaultViewRadius(), "the bug: rival out of the plain view radius");
    }

    @Test
    void inMatchPlayersRadiusCoversTheStadium() throws Exception {
        final int view = defaultViewRadius();
        assertTrue(MobiusWorldView.playersRadius(view, true) > MobiusWorldView.OLYMPIAD_SPREAD);
        assertTrue(MobiusWorldView.playersRadius(view, true) <= MobiusActuators.MAX_TARGET, "attack/cast can still reach the rival");
        assertEquals(view, MobiusWorldView.playersRadius(view, false), "world AI unchanged outside olympiad");
        assertEquals(4000, MobiusWorldView.playersRadius(4000, true), "a larger configured radius is kept");
    }
}

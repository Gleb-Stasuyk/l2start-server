package org.l2start.modules.stocknpcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.l2jmobius.gameserver.mechanics.stats.Formulas;

/**
 * Karma wash is 10x via the stock RateKarmaLost divisor (not a second multiplier).
 * Scheme-buffer duration is absolute and only for that NPC's own buff.
 * Spawns are the shipped XML, one of each spot.
 */
class StockNpcsTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    /** Same expression as {@code Formulas.calculateKarmaLost} for exp gained (finalExp &gt; 0). */
    static int karmaLost(long exp, float rateKarmaLost, double karmaLossMod) {
        return (int) ((Math.abs(exp / rateKarmaLost) / karmaLossMod) / 30);
    }

    /** Stock Rates.ini: -1 means use RateXp. An explicit value replaces that, it is not multiplied again. */
    static float effectiveKarmaLost(float configured, float rateXp) {
        return configured == -1f ? rateXp : configured;
    }

    @Test
    void sameExpWashesTenTimesFasterAndPkGainStays() {
        final float soloXp = iniFloat(ROOT.resolve("docker/overrides/solo/game/config/Rates.ini"), "RateXp");
        final float onlineXp = iniFloat(ROOT.resolve("docker/overrides/online/game/config/Rates.ini"), "RateXp");
        assertEquals(10f, soloXp);
        assertEquals(10f, onlineXp);
        assertEquals(1f, iniFloat(ROOT.resolve("core/dist/game/config/Rates.ini"), "RateXp"), "local package does not override RateXp");
        assertEquals(-1f, iniFloat(ROOT.resolve("core/dist/game/config/Rates.ini"), "RateKarmaLost"), "stock sentinel stays -1");

        assertTenTimes(10f, iniFloat(ROOT.resolve("docker/overrides/solo/game/config/Rates.ini"), "RateKarmaLost"));
        assertTenTimes(10f, iniFloat(ROOT.resolve("docker/overrides/online/game/config/Rates.ini"), "RateKarmaLost"));
        assertTenTimes(1f, iniFloat(ROOT.resolve("docker/release/overrides-local/game/config/Rates.ini"), "RateKarmaLost"));

        for (Path rates : List.of(
            ROOT.resolve("docker/overrides/solo/game/config/Rates.ini"),
            ROOT.resolve("docker/overrides/online/game/config/Rates.ini"),
            ROOT.resolve("docker/release/overrides-local/game/config/Rates.ini"))) {
            assertFalse(assigns(rates, "RateKarmaExpLost"), rates + " must not touch death XP");
            assertFalse(assigns(rates, "KarmaRateDrop"), rates + " must not touch PK item drop");
        }
        assertFalse(assigns(ROOT.resolve("docker/release/overrides-local/game/config/Rates.ini"), "RateXp"), "local package keeps stock RateXp");

        assertEquals(720, Formulas.calculateKarmaGain(0, false));
        assertEquals(720, Formulas.calculateKarmaGain(0, true));
        assertEquals(2520, Formulas.calculateKarmaGain(5, false));
        assertEquals(36180, Formulas.calculateKarmaGain(100, false));
        assertEquals(43200, Formulas.calculateKarmaGain(200, false));
    }

    private static void assertTenTimes(float previousEffective, float configuredNow) {
        final float now = effectiveKarmaLost(configuredNow, previousEffective);
        assertEquals(previousEffective / 10f, now, 0.0001f);
        final long exp = 3_000_000L;
        final double mod = 1.0;
        final int before = karmaLost(exp, previousEffective, mod);
        final int after = karmaLost(exp, now, mod);
        assertTrue(before > 0);
        assertEquals(before * 10, after);
        assertEquals(before, karmaLost(exp, effectiveKarmaLost(-1f, previousEffective), mod), "the old -1 sentinel is the baseline, not applied again");
    }

    @Test
    void npcBuffBecomesOneHourAndAnotherCastersBuffDoesNot() {
        assertEquals(3600, StockNpcBuffs.abnormalTimeFor(1200, 1200, 3600), "fresh stock 20 min buff");
        assertEquals(3700, StockNpcBuffs.abnormalTimeFor(1200, 1100, 3600), "100 s already elapsed, 3600 still left");
        assertEquals(3600, StockNpcBuffs.abnormalTimeFor(3660, 3660, 3600), "does not add on top of SkillDurationList");
        assertFalse(StockNpcBuffs.extend(null, null, 1204, 3600));
        assertFalse(StockNpcBuffs.extend(null, null, 1204, 0), "0 keeps the duration applyEffects set");
    }

    @Test
    void spawnsOnceFromShippedDataAndGeneratorStaysOff() throws Exception {
        final String xml = Files.readString(ROOT.resolve("modules/stock-npcs/data/spawns/stock-npcs.xml"));
        final List<Npc> npcs = npcs(xml);
        assertEquals(5, npcs.stream().filter(n -> n.id == 50008).count());
        assertEquals(1, npcs.stream().filter(n -> n.id == 50010).count());
        assertEquals(6, npcs.size());
        assertTrue(npcs.stream().anyMatch(n -> n.id == 50008 && n.x == 83561 && n.y == 147904 && n.z == -3404), "Giran buffer keeps the old spot");
        final Npc shop = npcs.stream().filter(n -> n.id == 50010).findFirst().orElseThrow();
        assertEquals(83680, shop.x);
        assertEquals(147780, shop.y);
        assertEquals(-3404, shop.z);
        for (Npc other : npcs) {
            if (other == shop) {
                continue;
            }
            assertTrue(dist(shop, other) >= 150, "GM shop overlaps " + other.id);
        }
        assertTrue(dist(shop, new Npc(30080, 83396, 147904, -3404)) >= 150, "overlaps Clarissa");
        assertTrue(dist(shop, new Npc(30990, 83331, 147905, -3400)) >= 150, "overlaps the lottery seller");
        assertTrue(dist(shop, new Npc(0, 83461, 147904, -3404)) >= 150, "overlaps the Giran town-buffer");
        assertTrue(dist(shop, new Npc(80000, 83485, 147998, -3407)) >= 150, "overlaps fake player Evi");

        final String env = Files.readString(ROOT.resolve("docker/release/settings.env"));
        assertTrue(env.contains("L2S_STOCK_NPC_BUFFER=off"), "the container generator would spawn a second 50008");
        final String html = Files.readString(ROOT.resolve("core/dist/game/data/html/merchant/50010.htm"));
        assertFalse(html.contains("admin_"));
        assertTrue(html.contains("npc_%objectId%_multisell"));
        final String list = Files.readString(ROOT.resolve("core/dist/game/data/multisell/custom/50039001.xml"));
        assertTrue(list.contains("<npc>50010</npc>"));
        assertTrue(list.contains("<ingredient id=\"57\""));
    }

    private static boolean assigns(Path file, String key) {
        try {
            return iniFloat(file, key) == iniFloat(file, key);
        } catch (AssertionError missing) {
            return false;
        }
    }

    private static float iniFloat(Path file, String key) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String t = line.trim();
                if (t.startsWith("#") || !t.startsWith(key)) {
                    continue;
                }
                final int eq = t.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (!t.substring(0, eq).trim().equals(key)) {
                    continue;
                }
                return Float.parseFloat(t.substring(eq + 1).trim().split("\\s+")[0]);
            }
        } catch (Exception e) {
            throw new AssertionError(file + " " + key, e);
        }
        throw new AssertionError(file + " has no " + key);
    }

    private static final Pattern NPC = Pattern.compile("<npc id=\"(\\d+)\" x=\"(-?\\d+)\" y=\"(-?\\d+)\" z=\"(-?\\d+)\"");

    private static List<Npc> npcs(String xml) {
        final Matcher m = NPC.matcher(xml);
        final List<Npc> out = new ArrayList<>();
        while (m.find()) {
            out.add(new Npc(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        }
        return out;
    }

    private static double dist(Npc a, Npc b) {
        final double dx = a.x - b.x;
        final double dy = a.y - b.y;
        return Math.hypot(dx, dy);
    }

    private record Npc(int id, int x, int y, int z) {
    }
}

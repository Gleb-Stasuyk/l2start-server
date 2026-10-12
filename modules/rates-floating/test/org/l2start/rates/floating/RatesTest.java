package org.l2start.rates.floating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

/** docs/RATES_PLAN.md §0: curves, F, chance by extra rolls, adena exactly x5, amounts x1..x3, exactly 1. */
class RatesTest {
    private static final Rules R = Rules.DEFAULT;
    private static final int ADENA = 57, SWORD = 100, ORE = 200, HERB = 300;
    private static final IntFunction<Kind> KIND = id -> switch (id) {
        case ADENA -> Kind.ADENA;
        case SWORD -> Kind.EXACT;
        case HERB -> Kind.STOCK;
        default -> Kind.STACK;
    };

    @Test
    void curvesOfTheSpec() {
        assertEquals(1.5, R.levelCurve().at(10), 1e-9);
        assertEquals(1.5, R.levelCurve().at(20), 1e-9);
        assertEquals(1.35, R.levelCurve().at(30), 1e-9);
        assertEquals(1.2, R.levelCurve().at(40), 1e-9);
        assertEquals(1.0, R.levelCurve().at(52), 1e-9);
        assertEquals(0.85, R.levelCurve().at(61), 1e-9);
        assertEquals(0.7, R.levelCurve().at(76), 1e-9);
        assertEquals(0.7, R.levelCurve().at(85), 1e-9);
        assertEquals(1.0, R.diffCurve().at(0), 1e-9);
        assertEquals(1.25, R.diffCurve().at(5), 1e-9);
        assertEquals(0.4, R.diffCurve().at(-12), 1e-9);
        assertEquals(1.5, R.f(15, 22), 1e-9); // 1.5 x 1.3 clamped
        assertEquals(0.35, R.f(78, 60), 1e-9); // 0.7 x 0.4 = 0.28 clamped
        assertEquals(1.0, R.f(52, 52), 1e-9);
        assertEquals(3.0, R.chance(false, 1.0), 1e-9);
        assertEquals(5.0 / 3, R.meanAmount(), 1e-9);
    }

    @Test
    void adenaExactlyX5StackFloatsExactStaysOne() {
        final Random rnd = new Random(7);
        final Supplier<List<ItemHolder>> roll = () -> List.of(new ItemHolder(ADENA, 100), new ItemHolder(SWORD, 1), new ItemHolder(ORE, 2), new ItemHolder(HERB, 1));
        double amount = 0;
        int lines = 0;
        int rolls = 0;
        final int n = 100_000;
        for (int i = 0; i < n; i++) {
            final DropFloat.Result res = DropFloat.build(roll, KIND, 3.0, 4, 5, true, R, rnd);
            rolls += res.rolls();
            amount += res.amountSum();
            lines += res.stackLines();
            for (ItemHolder h : res.items()) {
                switch (h.getId()) {
                    case ADENA -> assertEquals(500, h.getCount());
                    case SWORD -> assertEquals(1, h.getCount());
                    case HERB -> assertEquals(1, h.getCount());
                    default -> assertTrue((h.getCount() >= 2 * res.rolls()) && (h.getCount() <= 6 * res.rolls()));
                }
            }
        }
        assertEquals(3.0, rolls / (double) n, 1e-9); // C = 3: exactly 2 extra rolls
        assertEquals(5.0 / 3, amount / lines, 0.01);
    }

    @Test
    void fractionalChanceIsAnExtraRollWithThatChance() {
        final Random rnd = new Random(3);
        int rolls = 0;
        final int n = 100_000;
        for (int i = 0; i < n; i++) {
            rolls += DropFloat.build(List::of, KIND, 2.4, 4, 5, true, R, rnd).rolls();
        }
        assertEquals(2.4, rolls / (double) n, 0.01);
        assertEquals(5, DropFloat.build(List::of, KIND, 9, 4, 5, true, R, rnd).rolls()); // capped
    }

    @Test
    void rareEquipmentChanceAboutTripled() {
        final Random rnd = new Random(11);
        final double p = 0.01;
        final Supplier<List<ItemHolder>> roll = () -> (rnd.nextDouble() < p) ? List.of(new ItemHolder(SWORD, 1)) : List.of();
        int got = 0;
        final int n = 200_000;
        for (int i = 0; i < n; i++) {
            if (!DropFloat.build(roll, KIND, 3.0, 4, 5, true, R, rnd).items().isEmpty()) {
                got++;
            }
        }
        assertEquals(1 - Math.pow(1 - p, 3), got / (double) n, 0.002);
    }

    @Test
    void adenaAndHerbsOnlyFromTheStockRollAndRaidAmountsStock() {
        final Random rnd = new Random(5);
        final List<List<ItemHolder>> seq = new ArrayList<>(List.of(List.of(new ItemHolder(ORE, 3)), List.of(new ItemHolder(ADENA, 10), new ItemHolder(HERB, 1), new ItemHolder(ORE, 3))));
        final DropFloat.Result res = DropFloat.build(() -> seq.remove(0), KIND, 2.0, 4, 5, false, R, rnd);
        assertEquals(List.of(ORE), res.items().stream().map(ItemHolder::getId).toList());
        assertEquals(6, res.items().get(0).getCount());
    }

    @Test
    void exceptionsFile() throws Exception {
        final ItemRules r = ItemRules.load(Path.of("modules/rates-floating/data/exact-items.xml"));
        assertTrue(r.exactName("Top-Grade Life Stone: level 76"));
        assertTrue(r.exactName("Red Soul Crystal"));
        assertTrue(r.questX1.contains(1458) && r.questX1.contains(7562));
        assertTrue(!r.exactName("Crystal: D-Grade"));
    }
}

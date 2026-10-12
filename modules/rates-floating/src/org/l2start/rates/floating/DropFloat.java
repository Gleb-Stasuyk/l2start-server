package org.l2start.rates.floating;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

/**
 * Builds one drop (or spoil) out of stock rolls (docs/RATES_PLAN.md §0). Pure: the stock roll, the item kinds and the
 * random numbers come from outside.
 * <ol>
 * <li>Chance C: C >= 1 = the stock roll plus floor(C - 1) more stock rolls and one with the chance of the fraction (at
 * most maxExtraRolls); C < 1 = each item of the stock roll stays with chance C (adena and stock kinds always).</li>
 * <li>Adena and STOCK kinds come only from the first (stock) roll; adena x adenaRate.</li>
 * <li>EXACT: one piece per item id, however many rolls gave it.</li>
 * <li>STACK: each rolled line x A (amountFloats) and summed per item id.</li>
 * </ol>
 */
public final class DropFloat {
    /** What one call did, for the statistics. */
    public record Result(List<ItemHolder> items, int rolls, long stockAdena, long adena, int stackLines, double amountSum) {
    }

    private DropFloat() {
    }

    public static Result build(Supplier<List<ItemHolder>> roll, IntFunction<Kind> kind, double chance, int maxExtraRolls, double adenaRate, boolean amountFloats, Rules rules,
        Random rnd) {
        int extra = 0;
        if (chance > 1) {
            final double more = chance - 1;
            extra = (int) Math.floor(more) + ((rnd.nextDouble() < (more - Math.floor(more))) ? 1 : 0);
            extra = Math.min(Math.max(0, maxExtraRolls), extra);
        }
        final Map<Integer, Long> out = new LinkedHashMap<>();
        long stockAdena = 0;
        long adena = 0;
        int stackLines = 0;
        double amountSum = 0;
        for (int r = 0; r <= extra; r++) {
            final List<ItemHolder> got = roll.get();
            if (got == null) {
                continue;
            }
            for (ItemHolder h : got) {
                if ((h == null) || (h.getCount() <= 0)) {
                    continue;
                }
                final Kind k = kind.apply(h.getId());
                switch (k) {
                    case ADENA -> {
                        if (r == 0) {
                            stockAdena += h.getCount();
                            final long a = Math.round(h.getCount() * adenaRate);
                            adena += a;
                            out.merge(h.getId(), a, Long::sum);
                        }
                    }
                    case STOCK -> {
                        if (r == 0) {
                            out.merge(h.getId(), (long) h.getCount(), Long::sum);
                        }
                    }
                    case EXACT -> {
                        if ((chance >= 1) || (rnd.nextDouble() < chance)) {
                            out.put(h.getId(), 1L);
                        }
                    }
                    case STACK -> {
                        if ((chance < 1) && (rnd.nextDouble() >= chance)) {
                            break;
                        }
                        final double a = amountFloats ? rules.amount(rnd.nextDouble()) : 1;
                        stackLines++;
                        amountSum += a;
                        out.merge(h.getId(), Math.max(1, Math.round(h.getCount() * a)), Long::sum);
                    }
                }
            }
        }
        final List<ItemHolder> items = new ArrayList<>(out.size());
        for (Map.Entry<Integer, Long> e : out.entrySet()) {
            items.add(new ItemHolder(e.getKey(), (int) Math.min(Integer.MAX_VALUE, e.getValue())));
        }
        return new Result(items, extra + 1, stockAdena, adena, stackLines, amountSum);
    }
}

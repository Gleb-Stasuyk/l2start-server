package org.l2start.economy.market.model;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.l2start.economy.market.lw.StorePricing;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * What a bot trader puts in its store (phase 5b/5c): the lines of its profile (data/trader-profiles.xml) from the market
 * catalogue, within the server stage, with amounts by LW bulkAmountRange and prices by the market model (sellers by
 * greed, buyers by stinginess, buyers always below the cheapest seller). Pure: the caller gives the random source.
 */
public final class Assortment {
    public record Profile(String name, boolean buy, int minLines, int maxLines, String itemKind, Pattern match, Pattern skip) {
        boolean takes(MarketItem i) {
            return (itemKind.isEmpty() || itemKind.equalsIgnoreCase(i.kind())) && ((match == null) || match.matcher(i.name()).find()) && ((skip == null) || !skip.matcher(i.name()).find());
        }
    }

    /** One line of a store: count pieces of item at price a piece. */
    public record Line(int itemId, String name, long count, long price) {
    }

    private final Map<String, Profile> profiles;

    private Assortment(Map<String, Profile> profiles) {
        this.profiles = profiles;
    }

    public static Assortment load(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in);
        }
    }

    public static Assortment parse(InputStream in) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final NodeList nodes = f.newDocumentBuilder().parse(in).getElementsByTagName("profile");
        final Map<String, Profile> m = new LinkedHashMap<>();
        for (int k = 0; k < nodes.getLength(); k++) {
            final Element e = (Element) nodes.item(k);
            final String[] lines = e.getAttribute("lines").split(",");
            final int lo = Integer.parseInt(lines[0].trim());
            final int hi = (lines.length > 1) ? Integer.parseInt(lines[1].trim()) : lo;
            m.put(e.getAttribute("name"), new Profile(e.getAttribute("name"), "BUY".equalsIgnoreCase(e.getAttribute("kind")), Math.max(1, lo), Math.max(lo, hi), e.getAttribute("itemKind"),
                regex(e.getAttribute("match")), regex(e.getAttribute("skip"))));
        }
        return new Assortment(Collections.unmodifiableMap(m));
    }

    private static Pattern regex(String s) {
        return ((s == null) || s.isEmpty()) ? null : Pattern.compile(s);
    }

    public Profile profile(String name) {
        return profiles.get(name);
    }

    public Map<String, Profile> profiles() {
        return profiles;
    }

    /** Items a profile may carry at a stage. */
    public List<MarketItem> candidates(Profile p, MarketItems items, int stage) {
        final List<MarketItem> out = new ArrayList<>();
        for (MarketItem i : items.all()) {
            if ((i.price() > 0) && Stage.available(i, stage) && p.takes(i)) { // no reference price: no believable price either
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Weight of an item in the draw: items of the current stage are the bulk, earlier stages still common; the top grade of
     * a stage (A at 4, S at 5) is rare, like a single S piece in the whole town.
     */
    static double weight(MarketItem i, int stage) {
        double w = (i.stage() == stage) ? 1.0 : ((i.stage() == (stage - 1)) ? 0.8 : 0.4);
        if ((i.grade() == MarketItem.Grade.S) || ((i.grade() == MarketItem.Grade.A) && (stage == 4))) {
            w *= 0.08;
        }
        return w;
    }

    /**
     * The store of one trader. {@code greed} 0..1 (sellers ask more, buyers bid less as it grows). {@code cheapestSeller}
     * gives the cheapest bot sell price of an item in town (0 = none), so a buyer never pays more.
     */
    public List<Line> stock(Profile p, MarketBook book, int stage, double greed, long now, Random rnd, java.util.function.IntToLongFunction cheapestSeller) {
        final List<MarketItem> pool = new ArrayList<>(candidates(p, book.items(), stage));
        final int want = p.minLines() + rnd.nextInt((p.maxLines() - p.minLines()) + 1);
        final List<Line> out = new ArrayList<>();
        while ((out.size() < want) && !pool.isEmpty()) {
            double sum = 0;
            for (MarketItem i : pool) {
                sum += weight(i, stage);
            }
            double r = rnd.nextDouble() * sum;
            int pick = pool.size() - 1;
            for (int k = 0; k < pool.size(); k++) {
                r -= weight(pool.get(k), stage);
                if (r <= 0) {
                    pick = k;
                    break;
                }
            }
            final MarketItem it = pool.remove(pick);
            final MarketBook.Quote q = book.quote(it.id(), stage, now);
            final long price;
            if (p.buy()) {
                final long cheapest = cheapestSeller.applyAsLong(it.id());
                price = book.model().buyPrice(it, q.market(), greed, (cheapest > 0) ? Math.min(cheapest, q.lowSell()) : q.lowSell());
                if (price <= 0) {
                    continue;
                }
            } else {
                price = book.model().sellPrice(it, q.market(), greed);
            }
            final long count;
            if (it.stackable()) {
                final int[] range = StorePricing.bulkAmountRange((int) Math.min(Integer.MAX_VALUE, Math.max(1, it.price())));
                count = range[0] + rnd.nextInt(Math.max(1, (range[1] - range[0]) + 1));
            } else {
                count = 1;
            }
            out.add(new Line(it.id(), it.name(), Math.max(1, count), price));
        }
        return out;
    }
}

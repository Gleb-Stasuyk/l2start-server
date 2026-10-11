package org.l2start.economy.market.model;

import java.util.List;
import java.util.Random;

import org.l2start.economy.market.lw.StorePricing;

/**
 * The sign over a bot store, made from what is really inside (what it says is what you find), in the persona's
 * language: "SSD 12a / BSSD 47a", "C weapons", "Куплю Iron Ore 50a". At most 29 characters (stock limit).
 */
public final class StoreTitle {
    public static final int MAX = 29;

    private StoreTitle() {
    }

    /**
     * @param lang "RU", "EN" or "MIX" (a Russian player who often writes in English)
     */
    public static String of(Assortment.Profile profile, List<Assortment.Line> lines, MarketItems items, String lang, Random rnd) {
        if (lines.isEmpty()) {
            return "";
        }
        final boolean ru = "RU".equalsIgnoreCase(lang) || ("MIX".equalsIgnoreCase(lang) && rnd.nextBoolean());
        final Assortment.Line top = lines.get(0);
        final String g = grade(lines, items);
        final String price = StorePricing.priceText(top.price()) + ((top.price() < 1000) ? (ru ? "а" : "a") : ""); // "120a", "45k", "4.7kk"
        final String name = top.name();
        final String t;
        if (profile.buy()) {
            final String what = (lines.size() == 1) ? (shortName(name) + " " + price) : switch (profile.name()) {
                case "buy-crystals" -> ru ? "кристаллы " + g : "crystals " + g;
                case "buy-parts" -> ru ? "части, ресы" : "parts / mats";
                case "buy-materials" -> ru ? "ресы" : "mats";
                case "buy-scrolls" -> ru ? "заточки" : "enchant scrolls";
                case "buy-dyes" -> ru ? "краски, ЛС" : "dyes / LS";
                case "buy-potions" -> ru ? "банки" : "potions";
                case "buy-books" -> ru ? "рецепты" : "recipes";
                default -> shortName(name) + " " + price;
            };
            t = (ru ? "Куплю " : "BUY ") + what;
        } else {
            t = switch (profile.name()) {
                case "shots" -> shots(lines, ru);
                case "weapons" -> (lines.size() == 1) ? shortName(name) + " " + price : (ru ? "Пушки " + g : g + " weapons" + (rnd.nextBoolean() ? " cheap" : ""));
                case "armor" -> (lines.size() == 1) ? shortName(name) + " " + price : (ru ? "Броня " + g : g + " armor");
                case "jewels" -> ru ? "Бижа " + g : g + " jewels";
                case "materials" -> (ru ? "Ресы: " : "Mats: ") + shortName(name) + " " + price;
                case "recipes" -> (ru ? "Рецепты " : "Recipes ") + g;
                case "scrolls" -> (ru ? "Заточки " : "Enchant ") + g;
                case "potions" -> ru ? "Банки, SoE" : "Potions, SoE";
                default -> shortName(name) + " " + price;
            };
        }
        return fit(t);
    }

    static String shots(List<Assortment.Line> lines, boolean ru) {
        final StringBuilder sb = new StringBuilder();
        for (Assortment.Line l : lines) {
            final String s = StorePricing.shotSign(l.name(), (int) Math.min(Integer.MAX_VALUE, l.price()), true);
            if (s == null) {
                continue;
            }
            final String next = (sb.length() == 0) ? s : sb + " / " + s;
            if (next.length() > MAX) {
                break;
            }
            sb.setLength(0);
            sb.append(next);
        }
        return (sb.length() == 0) ? (ru ? "Соски" : "Shots") : (ru ? sb.toString().replace("a", "а") : sb.toString());
    }

    /** The highest grade in the store ("NG" when none). */
    static String grade(List<Assortment.Line> lines, MarketItems items) {
        MarketItem.Grade best = MarketItem.Grade.NONE;
        for (Assortment.Line l : lines) {
            final MarketItem i = items.get(l.itemId());
            if ((i != null) && (i.grade().ordinal() > best.ordinal())) {
                best = i.grade();
            }
        }
        return (best == MarketItem.Grade.NONE) ? "NG" : best.name();
    }

    /** "Recipe: Blue Wolf Breastplate (60%)" -> "Rec. Blue Wolf Breas"; "Scroll: Enchant Weapon (C)" -> "Enchant Weapon C"; at most 18 characters. */
    static String shortName(String name) {
        String n = (name.startsWith("Recipe: ") ? "Rec. " + name.substring(8) : name).replace("(", "").replace(")", "").replace("Scroll: ", "").trim();
        if (n.length() > 18) {
            n = n.substring(0, 18).trim();
        }
        return n;
    }

    static String fit(String t) {
        return (t.length() <= MAX) ? t : t.substring(0, MAX).trim();
    }
}

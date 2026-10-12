package org.l2start.economy.market.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import org.l2start.economy.market.lw.ChatParsing;
import org.l2start.economy.market.lw.StorePricing;

/**
 * Step 5f: the words of the trade chat. Pure: a player's WTS/WTB line (RU or EN, LW parser) to an ad with an item of
 * the market, the bot ads and whispers from templates (RU/EN, no LLM; phase 10 may replace them), the player's
 * yes/no, and rate limits.
 */
public final class TradeTalk {
    /** A player's ad: he sells (we buy) or he buys (we sell); item candidates (1 = clear, 2+ = ask which); pieces (0 = not said); unit price (0 = not said). */
    public record Ad(boolean playerSells, List<MarketItem> items, int count, int price) {
    }

    private static final Pattern RU_SELL = Pattern.compile("(?iu)(?<![\\p{L}])(продам|продаю|продаём|продаем|прод|отдам)(?![\\p{L}])");
    private static final Pattern RU_BUY = Pattern.compile("(?iu)(?<![\\p{L}])(куплю|покупаю|скуплю|скупаю|возьму|ищу)(?![\\p{L}])");
    private static final Pattern RU_PRICE = Pattern.compile("(?iu)(\\d)\\s*(?:к|кк)(?![\\p{L}])");
    private static final Pattern RU_EACH = Pattern.compile("(?iu)(?<![\\p{L}])(по|за шт|шт|штук|штуки|адены|аден)(?![\\p{L}])");

    /** Shorthand players use, to the words of the item name. */
    static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("bsoe", "blessed scroll escape"), Map.entry("soe", "scroll escape"), Map.entry("bsps", "bss"), Map.entry("ssng", "soulshot no grade"),
        Map.entry("ewd", "enchant weapon d"), Map.entry("ead", "enchant armor d"), Map.entry("ewc", "enchant weapon c"), Map.entry("eac", "enchant armor c"), Map.entry("ewb", "enchant weapon b"),
        Map.entry("eab", "enchant armor b"), Map.entry("ghp", "greater healing potion"), Map.entry("hp", "healing potion"), Map.entry("qhp", "quick healing potion"));

    private TradeTalk() {
    }

    /** RU markers and price shorthand to what the LW parser reads ("продам ссд 5к по 20" -> "wts ssd 5k @20"). */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String t = RU_SELL.matcher(text).replaceAll("wts");
        t = RU_BUY.matcher(t).replaceAll("wtb");
        t = RU_PRICE.matcher(t).replaceAll("$1k");
        t = t.replaceAll("(?iu)(?<![\\p{L}])по\\s+(\\d)", "@$1");
        t = RU_EACH.matcher(t).replaceAll(" ");
        // Russian keyboard shorthand of the shots: ссд, ссц, бсс
        t = t.replaceAll("(?iu)(?<![\\p{L}])ссд(?![\\p{L}])", "ssd").replaceAll("(?iu)(?<![\\p{L}])ссц(?![\\p{L}])", "ssc").replaceAll("(?iu)(?<![\\p{L}])ссб(?![\\p{L}])", "ssb");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** A player's trade chat line to an ad (null = not a WTS/WTB line, or no market item named). */
    public static Ad parse(String text, Collection<MarketItem> items, int stage) {
        final ChatParsing.TradeAd ad = ChatParsing.parseTradeAd(normalize(text));
        if ((ad == null) || ad.phrase.isBlank()) {
            return null;
        }
        final List<String> pieces = ChatParsing.splitTradeItems(ad.phrase, 3);
        final String phrase = pieces.isEmpty() ? ad.phrase : pieces.get(0); // the first item of the line
        final int price = ChatParsing.parseTradeUnitPrice(phrase);
        final List<MarketItem> found = match(ChatParsing.stripStatedPrices(phrase), items, stage);
        if (found.isEmpty()) {
            return null;
        }
        final int count = ChatParsing.parseTradeQuantity(ChatParsing.stripStatedPrices(phrase), found.get(0).stackable());
        return new Ad(ad.selling, found, count, price);
    }

    /** Market items (at or below the stage) the words name best; several = ask which (at most 2). */
    public static List<MarketItem> match(String phrase, Collection<MarketItem> items, int stage) {
        final List<String> wanted = new ArrayList<>();
        for (String w : StorePricing.matchTokens(phrase)) {
            if (w.matches("\\d+(k|kk|m)?")) {
                continue;
            }
            final String alias = ALIASES.get(w);
            if (alias != null) {
                wanted.addAll(List.of(alias.split(" ")));
            } else {
                wanted.add(w);
            }
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        int best = Integer.MAX_VALUE;
        final List<MarketItem> out = new ArrayList<>();
        for (boolean fuzzy : new boolean[] { false, true }) {
            for (MarketItem it : items) {
                if ((it.stage() > stage) || (it.price() <= 0)) {
                    continue;
                }
                final List<String> have = new ArrayList<>(StorePricing.matchTokens(it.name()));
                final String abbr = StorePricing.shotAbbrev(it.name());
                if (abbr != null) {
                    have.add(abbr.toLowerCase(Locale.ROOT).replace(" ", ""));
                }
                final int cost = StorePricing.tokenMatchCost(have, wanted, fuzzy);
                if (cost < 0) {
                    continue;
                }
                final int score = (cost * 100) + (have.size() - wanted.size()); // fewer typos, then the shortest name
                if (score < best) {
                    best = score;
                    out.clear();
                }
                if (score == best) {
                    out.add(it);
                }
            }
            if (!out.isEmpty()) {
                break;
            }
        }
        return out.size() > 2 ? List.of() : out; // too vague: no answer (LW asks only between two)
    }

    /** Which of two the player meant ("1", "first", a word of the name), -1 = unclear. */
    public static int pick(String answer, List<MarketItem> options) {
        final List<String> names = new ArrayList<>();
        for (MarketItem it : options) {
            names.add(it.name());
        }
        return ChatParsing.pickClarifiedOption(answer, names);
    }

    static final Set<String> RU_YES = Set.of("ок", "окей", "оке", "да", "давай", "беру", "го", "идет", "идёт", "согласен", "норм", "договорились", "+", "ok", "ок!", "lf");
    static final Set<String> RU_NO = Set.of("нет", "неа", "отмена", "передумал", "дорого", "не");

    public static boolean yes(String text) {
        if (text == null) {
            return false;
        }
        if (no(text)) {
            return false;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}+]+")) {
            if (RU_YES.contains(w)) {
                return true;
            }
        }
        return text.trim().equals("+") || ChatParsing.isDealAccept(text);
    }

    public static boolean no(String text) {
        if (text == null) {
            return false;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (RU_NO.contains(w)) {
                return true;
            }
        }
        return ChatParsing.isDealCancel(text);
    }

    // ---- templates

    /** The short name players use: shots by their sign (SSD, BSSB), else the name. */
    public static String shortName(String name) {
        final String a = StorePricing.shotAbbrev(name);
        return (a != null) ? a : name;
    }

    static boolean ru(String lang, Random rnd) {
        return "RU".equalsIgnoreCase(lang) || ("MIX".equalsIgnoreCase(lang) && rnd.nextBoolean());
    }

    public static String price(long p) {
        return StorePricing.priceText(p);
    }

    /** A bot ad for the trade chat. */
    public static String ad(boolean sell, String item, long price, long count, String lang, Random rnd) {
        final String n = shortName(item);
        final String pr = price(price);
        final boolean many = count > 1;
        if (ru(lang, rnd)) {
            final String[] t = sell ? new String[] { "WTS " + n + " " + pr + ", Гиран", "Продам " + n + " по " + pr + ", Гиран рынок", "WTS " + n + (many ? " " + StorePricing.amount((int) Math.min(Integer.MAX_VALUE, count)) : "") + " по " + pr + ", лавка в Гиране",
                "продаю " + n + " " + pr + " пм" } : new String[] { "WTB " + n + " " + pr + ", Гиран", "Куплю " + n + " по " + pr + ", скупка в Гиране", "WTB " + n + " по " + pr + " пм", "скупаю " + n + " " + pr + ", Гиран" };
            return t[rnd.nextInt(t.length)];
        }
        final String[] t = sell ? new String[] { "WTS " + n + " " + pr + "a, Giran", "selling " + n + " @" + pr + ", Giran market", "S> " + n + " " + pr + " each, Giran", "WTS " + n + (many ? " " + StorePricing.amount((int) Math.min(Integer.MAX_VALUE, count)) : "") + " " + pr + " pm" }
            : new String[] { "WTB " + n + " " + pr + "a, Giran", "buying " + n + " @" + pr + ", Giran", "B> " + n + " " + pr + " ea pm", "WTB " + n + " " + pr + ", my store in Giran" };
        return t[rnd.nextInt(t.length)];
    }

    /** Whisper: an offer to the player (we sell when he buys, and the other way). */
    public static String offer(boolean weSell, String item, long count, long price, String lang, Random rnd) {
        final String n = shortName(item);
        final String c = (count > 1) ? StorePricing.amount((int) Math.min(Integer.MAX_VALUE, count)) + " " : "";
        if (ru(lang, rnd)) {
            return weSell ? "есть " + n + ", " + c + (c.isEmpty() ? "" : "шт ") + "по " + price(price) + ". Я в Гиране на рынке. Напиши «ок» — выставлю в лавку на 3 мин"
                : "возьму " + n + ", " + c + (c.isEmpty() ? "" : "шт ") + "по " + price(price) + ". Скупка в Гиране на рынке. «ок» — открою под тебя на 3 мин";
        }
        return weSell ? "got " + n + ", " + c + (c.isEmpty() ? "" : "pcs ") + "@" + price(price) + ". I'm at Giran market, say ok and I'll put it in my store for 3 min"
            : "I'll buy " + n + ", " + c + (c.isEmpty() ? "" : "pcs ") + "@" + price(price) + ". my buy store is in Giran market, say ok and it's yours for 3 min";
    }

    public static String which(MarketItem a, MarketItem b, String lang, Random rnd) {
        return ru(lang, rnd) ? "какой из двух: " + a.name() + " или " + b.name() + "?" : "which one: " + a.name() + " or " + b.name() + "?";
    }

    public static String held(boolean weSell, String item, long price, String lang, Random rnd) {
        final String n = shortName(item);
        if (ru(lang, rnd)) {
            return weSell ? "выставил " + n + " по " + price(price) + ", лавка открыта, жду 3 мин" : "открыл скупку " + n + " по " + price(price) + ", жду 3 мин";
        }
        return weSell ? "done, " + n + " @" + price(price) + " is in my store, 3 min" : "buy store is open for your " + n + " @" + price(price) + ", 3 min";
    }

    public static String declined(String lang, Random rnd) {
        return ru(lang, rnd) ? (rnd.nextBoolean() ? "ок, как скажешь" : "ну ладно") : (rnd.nextBoolean() ? "np" : "ok, nvm");
    }

    public static String expired(String lang, Random rnd) {
        return ru(lang, rnd) ? "не дождался, снял с лавки" : "nvm, took it off my store";
    }

    public static String thanks(String lang, Random rnd) {
        return ru(lang, rnd) ? (rnd.nextBoolean() ? "спс!" : "удачи)") : (rnd.nextBoolean() ? "ty!" : "gl hf");
    }

    // ---- limits

    /** At most {@code max} events in any minute. */
    public static final class Limit {
        private final Deque<Long> at = new ArrayDeque<>();
        private final int max;

        public Limit(int max) {
            this.max = max;
        }

        public synchronized boolean allow(long now) {
            while (!at.isEmpty() && ((now - at.peekFirst()) >= 60_000L)) {
                at.pollFirst();
            }
            if (at.size() >= max) {
                return false;
            }
            at.addLast(now);
            return true;
        }
    }
}

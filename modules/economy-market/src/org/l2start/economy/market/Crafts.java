package org.l2start.economy.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.recipe.ManufactureItem;
import org.l2jmobius.gameserver.entity.item.recipe.RecipeList;
import org.l2jmobius.gameserver.mechanics.skill.CommonSkill;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2start.economy.market.lw.StorePricing;
import org.l2start.economy.market.model.Assortment.Line;
import org.l2start.economy.market.model.MarketBook;
import org.l2start.economy.market.model.MarketItem;
import org.l2start.economy.market.model.StoreTitle;

/**
 * Phase 5e: dwarf crafters. A crafter knows the dwarven recipes of its Create Item level whose product the market
 * knows at the server stage, and opens a stock MANUFACTURE store: the player brings the materials and pays a fee of
 * {@code CraftFee} of the product's market price; the stock craft code decides success by the recipe chance.
 */
final class Crafts {
    static final int MIN_FEE = 100;

    private final EconomyMarketModule m;

    Crafts(EconomyMarketModule m) {
        this.m = m;
    }

    /** Create Item level of a character level (the dwarf skill tree of Interlude). */
    static int craftLevel(int level) {
        final int[] at = { 5, 20, 28, 36, 43, 49, 55, 62, 70 };
        int n = 0;
        for (int l : at) {
            if (level >= l) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /** World thread: recipes into the book, the store list, full MP. @return the number of recipes shown. */
    int open(Player p, Traders.Store s, String lang) {
        int skill = p.getSkillLevel(CommonSkill.CREATE_DWARVEN.getId());
        final int want = Math.min(craftLevel(p.getLevel()), SkillData.getInstance().getMaxLevel(CommonSkill.CREATE_DWARVEN.getId()));
        if (skill < want) {
            final Skill sk = SkillData.getInstance().getSkill(CommonSkill.CREATE_DWARVEN.getId(), want);
            if (sk != null) {
                p.addSkill(sk, false);
                skill = want;
            }
        }
        p.getDwarvenRecipeBook().clear();
        p.getManufactureItems().clear();
        final List<Line> lines = new ArrayList<>();
        final long now = System.currentTimeMillis();
        final MarketBook book = m.book();
        final int stage = m.stage();
        final double feeShare = m.ctx().config().getDouble("CraftFee");
        final List<RecipeList> pick = choose(skill, s.row.botId(), stage);
        for (RecipeList r : pick) {
            final MarketItem product = book.items().get(r.getItemId());
            final long market = book.quote(r.getItemId(), stage, now).market() * Math.max(1, r.getCount());
            final int fee = StorePricing.naturalizePrice((int) Math.min(Integer.MAX_VALUE, Math.max(MIN_FEE, Math.round(market * feeShare))));
            p.registerDwarvenRecipeList(r, false);
            p.getManufactureItems().put(r.getId(), new ManufactureItem(r.getId(), fee));
            lines.add(new Line(r.getId(), product.name(), 1, fee));
        }
        s.lines = lines;
        if (!lines.isEmpty()) {
            final boolean ru = "RU".equals(lang);
            final String title = title(ru, lines.get(0).name(), lines.size() > 1);
            s.row = new MarketRepository.StoreRow(s.row.botId(), s.row.town(), s.row.spot(), "CRAFT", s.row.profile(), title, s.row.openedAt(), now, lines.size());
        }
        p.setCurrentMp(p.getMaxMp());
        return lines.size();
    }

    /** "Крафт: Heavy Doom Ham и др." - the suffix always fits the 29 signs of a store title. */
    static String title(boolean ru, String product, boolean more) {
        final String head = ru ? "Крафт: " : "Craft: ";
        final String tail = more ? (ru ? " и др." : " etc.") : "";
        String top = StoreTitle.shortName(product);
        final int room = StoreTitle.MAX - head.length() - tail.length();
        if (top.length() > room) {
            top = top.substring(0, room).trim();
        }
        return head + top + tail;
    }

    /** Leaving: the recipes and the store list go (memory only: nothing of it is in the database). */
    void close(Player p, Traders.Store s) {
        p.getManufactureItems().clear();
        p.getDwarvenRecipeBook().clear();
    }

    /** 6-10 recipes of the skill level (the higher levels first), fixed per crafter and stage. */
    List<RecipeList> choose(int skill, int botId, int stage) {
        final List<RecipeList> fit = new ArrayList<>();
        final RecipeData data = RecipeData.getInstance();
        for (int itemId : data.getAllItemIds()) {
            final RecipeList r = data.getRecipeByItemId(itemId);
            if ((r == null) || !r.isDwarvenRecipe() || (r.getLevel() > skill) || (r.getRecipes().length == 0)) {
                continue;
            }
            final MarketItem product = m.book().items().get(r.getItemId());
            if ((product == null) || (product.stage() > stage)) {
                continue;
            }
            fit.add(r);
        }
        fit.sort(Comparator.comparingInt(RecipeList::getId));
        final Random rnd = new Random((botId * 31L) + stage);
        final List<RecipeList> top = new ArrayList<>();
        for (RecipeList r : fit) {
            if (r.getLevel() >= (skill - 1)) {
                top.add(r);
            }
        }
        final int count = 6 + rnd.nextInt(5);
        final List<RecipeList> out = new ArrayList<>();
        java.util.Collections.shuffle(top, rnd);
        for (RecipeList r : top) {
            if (out.size() < count) {
                out.add(r);
            }
        }
        final List<RecipeList> rest = new ArrayList<>(fit);
        rest.removeAll(out);
        java.util.Collections.shuffle(rest, rnd);
        for (RecipeList r : rest) {
            if (out.size() < count) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingInt(RecipeList::getLevel).reversed());
        return out;
    }
}

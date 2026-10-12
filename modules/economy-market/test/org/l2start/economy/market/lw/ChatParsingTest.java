/*
 * Taken from Living Worlds (tests/java/TradeAdParserTest.java, MIT like L2jMobius): the WTS/WTB parser checks, run by JUnit.
 */
package org.l2start.economy.market.lw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.l2start.economy.market.lw.ChatParsing.TradeAd;

/**
 * Standalone harness for the WTS/WTB parser v2 (parseTradeAd, item links, multi-item split, clarify pick, enchant
 * pricing, haggle limit). Exit code 0 when every check passes.
 */
public class ChatParsingTest
{
	private static int checks = 0;
	private static int failures = 0;

	@org.junit.jupiter.api.Test
	public void lwTradeAdChecks()
	{
		testAdTable();
		testNotAds();
		testLinks();
		testSplit();
		testClarifyPick();
		testEnchantMultiplier();
		testCounterLimit();
		testReviewFixes();
		System.out.println(checks + " checks, " + failures + " failures");
		org.junit.jupiter.api.Assertions.assertEquals(0, failures, failures + " LW trade ad checks failed");
	}

	private static void testAdTable()
	{
		final String[] items = { "ssd", "soulshot d", "bsoe", "spirit ore", "Mithril Alloy", "adena", "sps b grade", "Tallum Plate Armor", "scroll of escape", "Q" };
		final String[] amounts = { "", " 5k", " 2", " 1kk" };
		final String[] sellMarkers = { "wts", "WTS", "selling", "S>", "[WTS]", "+WTS", "wts:" };
		final String[] buyMarkers = { "wtb", "WTB", "buying", "B>", "[WTB]", "+WTB", "wtb:" };
		int rows = 0;
		for (String item : items)
		{
			for (String amount : amounts)
			{
				for (String marker : sellMarkers)
				{
					// marker first, and marker last
					check(marker + " " + item + amount, true, item);
					check(item + amount + " " + marker, true, item);
					rows += 2;
				}
				for (String marker : buyMarkers)
				{
					check(marker + " " + item + amount, false, item);
					check(item + amount + " " + marker, false, item);
					rows += 2;
				}
			}
		}
		truth(rows >= 100, "table has 100+ ads (" + rows + ")");
		// Prices and counts stay in the phrase for the quantity/price parsers.
		final TradeAd priced = ChatParsing.parseTradeAd("WTB Soulshot D 5k @300");
		eq("Soulshot D 5k @300", priced.phrase, "price phrase kept");
		eq(300, ChatParsing.parseTradeUnitPrice(priced.phrase), "price read from phrase");
		eq(5000, ChatParsing.parseTradeQuantity(ChatParsing.stripStatedPrices(priced.phrase), true), "qty read from phrase");
		// First marker wins when an ad carries two.
		final TradeAd two = ChatParsing.parseTradeAd("wts ssd wtb adena");
		truth(two.selling, "first marker wins");
		eq("ssd", two.phrase, "second ad dropped");
	}

	private static void check(String text, boolean selling, String item)
	{
		final TradeAd ad = ChatParsing.parseTradeAd(text);
		checks++;
		if ((ad == null) || (ad.selling != selling))
		{
			failures++;
			System.out.println("FAIL: [" + text + "] -> " + (ad == null ? "null" : ("selling=" + ad.selling)));
			return;
		}
		truth(ad.phrase.toLowerCase().contains(item.toLowerCase().split(" ")[0]), "[" + text + "] phrase keeps item, got [" + ad.phrase + "]");
	}

	private static void testNotAds()
	{
		for (String text : new String[] { "hello", "anyone want to party", "unselling", "wtsfoo", "abuying", "crabs>", "how are you today" })
		{
			truth(ChatParsing.parseTradeAd(text) == null, "not an ad: " + text);
		}
		truth(ChatParsing.parseTradeAd(null) == null, "null");
		truth(ChatParsing.parseTradeAd("") == null, "empty");
	}

	private static void testLinks()
	{
		final char c = (char) 8;
		final String link = c + "Type=1 \tID=268439123 \tColor=0 \tUnderline=0 \tTitle=\u001BSoulshot: D-grade\u001B" + c;
		final String text = "wts " + link + " 5k";
		eq(Arrays.asList(268439123), ChatParsing.extractLinkedObjectIds(text), "link id");
		final TradeAd ad = ChatParsing.parseTradeAd(text);
		truth(ad != null && ad.selling, "ad with link parses");
		eq(1, ad.linkedObjectIds.size(), "one linked id");
		eq("5k", ad.phrase, "link text removed from phrase");
		eq("wts 5k", ChatParsing.stripItemLinks(text), "strip links");
		final String two = "wtb " + link + " and " + link.replace("268439123", "7");
		eq(Arrays.asList(268439123, 7), ChatParsing.extractLinkedObjectIds(two), "two links in order");
		eq(0, ChatParsing.extractLinkedObjectIds("wts ssd").size(), "no links");
	}

	private static void testSplit()
	{
		eq(Arrays.asList("ssd 5k"), ChatParsing.splitTradeItems("ssd 5k", 3), "single");
		eq(Arrays.asList("ssd 5k", "bsoe 2", "spirit ore"), ChatParsing.splitTradeItems("ssd 5k, bsoe 2 and spirit ore", 3), "three items");
		eq(Arrays.asList("ssd 5k", "bsoe"), ChatParsing.splitTradeItems("ssd 5k, bsoe, spirit ore", 2), "capped at 2");
		eq(Arrays.asList("ssd 5k 300 adena"), ChatParsing.splitTradeItems("ssd 5k, 300 adena", 3), "price piece stays attached");
		eq(Arrays.asList("ssd 5k @300", "bsoe"), ChatParsing.splitTradeItems("ssd 5k @300 & bsoe", 3), "ampersand");
		truth(ChatParsing.splitTradeItems("", 3).isEmpty(), "empty phrase");
		truth(ChatParsing.splitTradeItems(null, 3).isEmpty(), "null phrase");
	}

	private static void testClarifyPick()
	{
		final List<String> opts = new ArrayList<>(Arrays.asList("Mithril Alloy", "Mithril Shirt", "Mithril Gaiters"));
		eq(0, ChatParsing.pickClarifiedOption("1", opts), "number 1");
		eq(1, ChatParsing.pickClarifiedOption("the second one", opts), "ordinal");
		eq(2, ChatParsing.pickClarifiedOption("gaiters pls", opts), "unique word");
		eq(0, ChatParsing.pickClarifiedOption("alloy", opts), "unique word alloy");
		eq(-1, ChatParsing.pickClarifiedOption("mithril", opts), "shared word is not a pick");
		eq(-1, ChatParsing.pickClarifiedOption("how much", opts), "no pick");
		eq(-1, ChatParsing.pickClarifiedOption("alloy or shirt", opts), "two picks");
		eq(-1, ChatParsing.pickClarifiedOption(null, opts), "null reply");
	}

	private static void testEnchantMultiplier()
	{
		eq(1.0, StorePricing.enchantMultiplier(3, 0), "+0 is 1.0");
		eq(1.0, StorePricing.enchantMultiplier(3, -2), "negative is 1.0");
		for (int grade = 0; grade <= 5; grade++)
		{
			double last = 1.0;
			for (int e = 1; e <= 20; e++)
			{
				final double m = StorePricing.enchantMultiplier(grade, e);
				truth(m >= last, "monotonic grade " + grade + " +" + e);
				last = m;
			}
			eq(StorePricing.enchantMultiplier(grade, 16), StorePricing.enchantMultiplier(grade, 30), "capped at +16, grade " + grade);
		}
		truth(StorePricing.enchantMultiplier(5, 10) > StorePricing.enchantMultiplier(1, 10), "S gains more than D");
		truth(StorePricing.enchantMultiplier(3, 3) < 1.3, "safe enchant is a small bump");
		truth(StorePricing.enchantMultiplier(3, 10) > 3.0, "+10 B is worth several times base");
		truth(StorePricing.enchantMultiplier(99, 5) > 1.0, "out of range grade is clamped");
	}

	private static void testCounterLimit()
	{
		eq(85, ChatParsing.counterLimit(100, true), "seller floor is 15% under ask");
		eq(115, ChatParsing.counterLimit(100, false), "buyer ceiling is 15% over");
		eq(0, ChatParsing.counterLimit(0, true), "no ask, no limit");
		truth(ChatParsing.acceptsCounter(85, 100, true) && !ChatParsing.acceptsCounter(84, 100, true), "limit matches acceptsCounter (sell)");
		truth(ChatParsing.acceptsCounter(115, 100, false) && !ChatParsing.acceptsCounter(116, 100, false), "limit matches acceptsCounter (buy)");
	}

	private static void testReviewFixes()
	{
		// FPC-194: compact S>/B> forms.
		final TradeAd compactSell = ChatParsing.parseTradeAd("S>SSD 5k");
		truth((compactSell != null) && compactSell.selling, "S>SSD is a sell ad");
		eq("SSD 5k", (compactSell == null) ? null : compactSell.phrase, "S>SSD phrase");
		final TradeAd compactBuy = ChatParsing.parseTradeAd("B>bsoe");
		truth((compactBuy != null) && !compactBuy.selling, "B>bsoe is a buy ad");
		truth(ChatParsing.parseTradeAd("crabs>ssd") == null, "s> inside a word is not a marker");
		// FPC-189: +N enchant survives the split and is read, then stripped.
		eq(Arrays.asList("+5 Sword of Revolution"), ChatParsing.splitTradeItems("+5 Sword of Revolution", 3), "+5 is not a separator");
		eq(Arrays.asList("+5 Sword of Revolution", "bsoe"), ChatParsing.splitTradeItems("+5 Sword of Revolution + bsoe", 3), "plain + still splits");
		eq(5, ChatParsing.parseEnchant("+5 Sword of Revolution"), "enchant read");
		eq(12, ChatParsing.parseEnchant("Sword of Revolution +12 300k"), "enchant after the name");
		eq(0, ChatParsing.parseEnchant("ssd 5k"), "no enchant");
		eq(0, ChatParsing.parseEnchant("a+5"), "+ inside a word is not an enchant");
		eq("Sword of Revolution 300k", ChatParsing.stripEnchant("+5 Sword of Revolution 300k"), "enchant stripped");
		eq(0, ChatParsing.parseTradeQuantity(ChatParsing.stripStatedPrices(ChatParsing.stripEnchant("+5 Sword of Revolution")), false), "+5 is not read as an amount (0 = none stated)");
		// FPC-192: amounts and prices name no item, real words do.
		truth(!ChatParsing.hasItemWord("5k @300"), "amount and price are not an item");
		truth(ChatParsing.hasItemWord("bsoe"), "bsoe is an item word");
		// FPC-191: the pick words leave the clarify answer, amount and price stay.
		eq("@150k", ChatParsing.stripClarifyPick("second one @150k"), "pick words stripped");
		eq("5k", ChatParsing.stripClarifyPick("the 1st pls 5k"), "pick words stripped, amount kept");
		// FPC-188: the stated limit is always inside the band.
		eq(85, ChatParsing.executableCounterLimit(100, true, 50, 200), "limit inside the band is unchanged");
		eq(95, ChatParsing.executableCounterLimit(100, true, 95, 200), "seller limit raised to the band floor");
		eq(105, ChatParsing.executableCounterLimit(100, false, 50, 105), "buyer limit lowered to the band ceiling");
		eq(0, ChatParsing.executableCounterLimit(0, true, 1, 10), "no ask, no limit");
	}

	private static void eq(Object expected, Object actual, String what)
	{
		checks++;
		if ((expected == null) ? (actual != null) : !expected.equals(actual))
		{
			failures++;
			System.out.println("FAIL: " + what + " -> expected [" + expected + "] but got [" + actual + "]");
		}
	}

	private static void truth(boolean condition, String what)
	{
		checks++;
		if (!condition)
		{
			failures++;
			System.out.println("FAIL: " + what);
		}
	}
}

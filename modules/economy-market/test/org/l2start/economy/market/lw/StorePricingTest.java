/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2start.economy.market.lw;


import java.util.Arrays;
import java.util.List;


/**
 * Standalone (no JUnit, no game server) regression harness for {@link StorePricing}.<br>
 * These lock down the money-safety and formatting logic of the fake-player store: the negotiated-price
 * sanity band (the guard against a trade-chat prompt injection selling rares for 1 adena or overpaying
 * billions), deal-quantity clamping, bulk-stack sizing bands, sign formatting, and item-phrase tokenising.
 *
 * <p>Run from the project root ("L2J_Mobius_CT_0_Interlude github"):
 * <pre>
 *   javac -d build/test-classes \
 *         "java/org/l2jmobius/gameserver/managers/StorePricing.java" \
 *         "tests/java/StorePricingTest.java"
 *   java -cp build/test-classes StorePricingTest
 * </pre>
 * Exit code is 0 when every check passes, 1 otherwise.
 */
public class StorePricingTest
{
	private static int checks = 0;
	private static int failures = 0;

	@org.junit.jupiter.api.Test
	public void lwChecks()
	{
		testEffectiveReference();
		testClampDealPrice();
		testNormalizedDealCount();
		testBulkAmountRange();
		testAmount();
		testNaturalizePrice();
		testPriceText();
		testShotAbbrev();
		testShotSign();
		testMatchTokens();

		System.out.println();
		System.out.println("Ran " + checks + " checks, " + failures + " failure(s).");
		if (failures > 0)
		{
			throw new AssertionError(failures + " of " + checks + " checks failed");
		}
		System.out.println("OK");
	}

	private static void testEffectiveReference()
	{
		// Multiplier of 1 (or the price already <= 0) is an identity: today's retail-like behaviour is unchanged.
		eq(1000, StorePricing.effectiveReference(1000, 1.0), "1x multiplier leaves the price unchanged");
		eq(1, StorePricing.effectiveReference(0, 4.0), "zero reference price -> at least 1");
		// A raised multiplier scales the whole reference up (this is the //rates 'storeprice' knob).
		eq(4000, StorePricing.effectiveReference(1000, 4.0), "4x multiplier scales the reference");
		eq(2500, StorePricing.effectiveReference(1000, 2.5), "fractional multiplier scales the reference");
		eq(333, StorePricing.effectiveReference(1000, 0.333), "sub-1 multiplier discounts (rounded)");
		eq(3, StorePricing.effectiveReference(10, 0.25), "rounding: 10 * 0.25 -> 3 (round half up)");
		// A non-positive multiplier is treated as 1 so a mis-set config can never zero out or invert prices.
		eq(1000, StorePricing.effectiveReference(1000, 0.0), "zero multiplier treated as 1x");
		eq(1000, StorePricing.effectiveReference(1000, -3.0), "negative multiplier treated as 1x");
		// A huge multiplier can never overflow the int the store line stores.
		eq(Integer.MAX_VALUE, StorePricing.effectiveReference(200_000_000, 1000.0), "overflow clamped to Integer.MAX_VALUE");
	}

	private static void testClampDealPrice()
	{
		// Selling (ref 1000): band is [500, 3000]. Absurd lows/highs get pinned; a fair haggle passes through.
		eq(500, StorePricing.clampDealPrice(1, 1000, true), "sell: 1 adena pinned up to the 0.5x floor");
		eq(3000, StorePricing.clampDealPrice(9_999_999, 1000, true), "sell: absurd high pinned to the 3x ceiling");
		eq(1500, StorePricing.clampDealPrice(1500, 1000, true), "sell: fair haggle within band passes through");
		// Buying (ref 1000): band is [100, 1500] - tighter ceiling so the bot never overpays.
		eq(100, StorePricing.clampDealPrice(1, 1000, false), "buy: 1 adena pinned up to the 0.1x floor");
		eq(1500, StorePricing.clampDealPrice(9_999_999, 1000, false), "buy: overpay pinned to the 1.5x ceiling");
		// Reference price unknown: fall back to at-least-1, never below.
		eq(5, StorePricing.clampDealPrice(5, 0, true), "no reference price -> keep the agreed price");
		eq(1, StorePricing.clampDealPrice(0, 0, true), "no reference price, zero agreed -> at least 1");
	}

	private static void testNormalizedDealCount()
	{
		eq(1, StorePricing.normalizedDealCount(false, 5000, 42), "non-stackable is always a single piece");
		eq(5000, StorePricing.normalizedDealCount(true, 5000, 42), "requested count is used when stackable");
		eq(2_000_000, StorePricing.normalizedDealCount(true, 9_000_000, 42), "requested count clamped to 2,000,000");
		eq(42, StorePricing.normalizedDealCount(true, 0, 42), "no request -> auto-sized fallback");
		eq(1, StorePricing.normalizedDealCount(true, 0, 0), "fallback is at least 1");
	}

	private static void testBulkAmountRange()
	{
		range(2000, 15000, StorePricing.bulkAmountRange(10), "<=10 is the cheap/bulk band");
		range(300, 3000, StorePricing.bulkAmountRange(11), "just over 10 steps to the next band");
		range(300, 3000, StorePricing.bulkAmountRange(60), "<=60 band");
		range(30, 400, StorePricing.bulkAmountRange(600), "<=600 band");
		range(3, 40, StorePricing.bulkAmountRange(6000), "<=6000 band");
		range(1, 8, StorePricing.bulkAmountRange(6001), "expensive goods -> single digits");
	}

	private static void testAmount()
	{
		eq("15k", StorePricing.amount(15000), "15000 -> 15k");
		eq("1k", StorePricing.amount(1000), "1000 -> 1k");
		eq("1.5k", StorePricing.amount(1500), "1500 -> 1.5k");
		eq("2.5k", StorePricing.amount(2500), "2500 -> 2.5k");
		eq("800", StorePricing.amount(800), "under 1000 stays plain");
		eq("999", StorePricing.amount(999), "999 stays plain");
	}

	private static void testNaturalizePrice()
	{
		// >= 1000 rounds to two significant figures (the ugly random markup becomes a number a player would say).
		eq(45000, StorePricing.naturalizePrice(45320), "45320 -> 45000 (2 sig figs)");
		eq(1200, StorePricing.naturalizePrice(1234), "1234 -> 1200");
		eq(470000, StorePricing.naturalizePrice(467000), "467000 -> 470000 (rounds up)");
		eq(45000000, StorePricing.naturalizePrice(45320000), "45320000 -> 45,000,000");
		eq(1000, StorePricing.naturalizePrice(999), "999 -> 1000 (nearest 10 crosses over)");
		// 100-999 rounds to the nearest 10; under 100 stays exact so shot prices ("3a each") survive.
		eq(450, StorePricing.naturalizePrice(452), "452 -> 450 (nearest 10)");
		eq(3, StorePricing.naturalizePrice(3), "3 stays exact");
		eq(50, StorePricing.naturalizePrice(50), "50 stays exact");
		// Guards: never returns below 1 for a positive/zero/negative input.
		eq(1, StorePricing.naturalizePrice(0), "0 -> 1");
		eq(1, StorePricing.naturalizePrice(-5), "negative -> 1");
	}

	private static void testPriceText()
	{
		eq("45k", StorePricing.priceText(45000), "45000 -> 45k");
		eq("470k", StorePricing.priceText(470000), "470000 -> 470k");
		eq("1.2k", StorePricing.priceText(1200), "1200 -> 1.2k");
		eq("1k", StorePricing.priceText(1000), "1000 -> 1k");
		eq("45kk", StorePricing.priceText(45000000), "45,000,000 -> 45kk");
		eq("1.5kk", StorePricing.priceText(1500000), "1,500,000 -> 1.5kk");
		eq("300", StorePricing.priceText(300), "under 1000 stays plain");
		eq("999", StorePricing.priceText(999), "999 stays plain");
	}

	private static void testShotAbbrev()
	{
		// Soulshot -> SS, Spiritshot -> SPS, Blessed Spiritshot -> BSS; grade appended (No Grade -> " NG").
		eq("SSD", StorePricing.shotAbbrev("Soulshot: D-Grade"), "soulshot D -> SSD");
		eq("SSC", StorePricing.shotAbbrev("Soulshot: C-grade"), "soulshot C (lowercase form) -> SSC");
		eq("SSS", StorePricing.shotAbbrev("Soulshot: S Grade"), "soulshot S (space form) -> SSS");
		eq("SS NG", StorePricing.shotAbbrev("Soulshot: No Grade"), "soulshot no grade -> SS NG");
		eq("SPSA", StorePricing.shotAbbrev("Spiritshot: A-Grade"), "spiritshot A -> SPSA");
		eq("BSSD", StorePricing.shotAbbrev("Blessed Spiritshot: D-Grade"), "blessed spiritshot D -> BSSD");
		eq("BSS NG", StorePricing.shotAbbrev("Blessed Spiritshot: No Grade for Beginners"), "blessed spiritshot no grade -> BSS NG");
		eq(null, StorePricing.shotAbbrev("Iron Ore"), "non-shot -> null");
		eq(null, StorePricing.shotAbbrev(null), "null name -> null");
	}

	private static void testShotSign()
	{
		eq("SSD 100a", StorePricing.shotSign("Soulshot: D-Grade", 100, true), "selling shot shows per-unit adena");
		eq("SSD 1.5ka", StorePricing.shotSign("Soulshot: D-Grade", 1500, true), "large per-unit price uses k suffix");
		eq("SSD", StorePricing.shotSign("Soulshot: D-Grade", 100, false), "buy sign omits the price");
		eq(null, StorePricing.shotSign("Iron Ore", 100, true), "non-shot -> null sign");
	}

	private static void testMatchTokens()
	{
		// Filler ("grade") dropped, punctuation stripped, plural folded, single-letter grade kept.
		eq(Arrays.asList("soulshot", "d"), StorePricing.matchTokens("Soulshots: D-grade"), "shot phrase tokenises to [soulshot, d]");
		eq(Arrays.asList("iron", "ore"), StorePricing.matchTokens("iron ore"), "plain two-word item");
		eq(Arrays.asList("arrow"), StorePricing.matchTokens("arrows"), "plural folded to singular");
		// FPC-198: a small typo per word is accepted only in fuzzy mode, and never for short words.
		final java.util.List<String> homunkulus = StorePricing.matchTokens("Homunkulus's Sword");
		eq(-1, StorePricing.tokenMatchCost(homunkulus, StorePricing.matchTokens("Homunculus Sword"), false), "exact mode rejects the c/k typo");
		eq(1, StorePricing.tokenMatchCost(homunkulus, StorePricing.matchTokens("Homunculus Sword"), true), "fuzzy mode accepts one typo");
		eq(0, StorePricing.tokenMatchCost(homunkulus, StorePricing.matchTokens("homunkulus sword"), true), "exact words cost nothing");
		eq(-1, StorePricing.tokenMatchCost(homunkulus, StorePricing.matchTokens("homo sword"), true), "a short nickname is not a typo");
		eq(-1, StorePricing.tokenMatchCost(StorePricing.matchTokens("Soulshot: D-grade"), StorePricing.matchTokens("soulshot c"), true), "a grade letter never fuzzes");
		eq(Arrays.asList("bss"), StorePricing.matchTokens("cheap bss pls pm"), "stopwords cheap/pls/pm dropped");
		eq(0, StorePricing.matchTokens("   ").size(), "blank phrase -> no tokens");
		eq(0, StorePricing.matchTokens(null).size(), "null phrase -> no tokens");
	}

	// ===== tiny assertion helpers =====

	private static void range(int min, int max, int[] actual, String what)
	{
		eq(2, actual.length, what + " (two-element range)");
		eq(min, actual[0], what + " (min)");
		eq(max, actual[1], what + " (max)");
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

	private static void eq(List<String> expected, List<String> actual, String what)
	{
		checks++;
		if (!expected.equals(actual))
		{
			failures++;
			System.out.println("FAIL: " + what + " -> expected " + expected + " but got " + actual);
		}
	}
}

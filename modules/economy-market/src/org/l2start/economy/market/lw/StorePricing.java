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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, side-effect-free pricing / quantity / text helpers extracted from {@link FakePlayerStoreFactory}.<br>
 * These are the money-safety and formatting decisions of the fake-player store system - clamping a
 * whisper-negotiated price into a sane band, clamping deal quantities, sizing bulk stacks, formatting a
 * store sign, and tokenising an item phrase. They touch no game state or randomness, so they can be
 * unit-tested with a plain JDK - see {@code tests/java/StorePricingTest.java}. The factory
 * delegates to them so the tested logic is the logic that actually runs (no drifting copy).
 * <p>Ported from Living Worlds ({@code FakePlayer*}); kept as is apart from the package and class names and the two
 * typo helpers of LW {@code FakePlayerChatParsing} (fuzzyBudget, editDistance) copied in at the end.
 */
public final class StorePricing
{
	// Filler words to ignore when matching a trade-ad phrase to an item name.
	private static final Set<String> MATCH_STOPWORDS = Set.of("grade", "gr", "the", "a", "an", "of", "for", "pls", "plz", "pm", "cheap", "each", "ea", "some", "any", "my", "g", "lvl");

	private StorePricing()
	{
		// Utility holder - not instantiable.
	}

	/**
	 * Scales an item's reference price by the configurable store-price multiplier to yield the
	 * <i>effective</i> reference used for all fake-player store pricing. Feeding this into the markup and
	 * clamp logic keeps generated stores, negotiated deals and the anti-injection band all coherent, so a
	 * server on an inflated adena rate can raise store prices to match with a single knob. A multiplier of
	 * {@code <= 0} (or exactly 1) leaves the price unchanged; the result is clamped to {@code [1, Integer.MAX_VALUE]}.
	 * @param referencePrice the item reference price
	 * @param multiplier the store-price multiplier (retail-like = 1)
	 * @return the scaled reference price, at least 1 and never overflowing an int
	 */
	public static int effectiveReference(int referencePrice, double multiplier)
	{
		if ((multiplier <= 0) || (multiplier == 1) || (referencePrice <= 0))
		{
			return Math.max(1, referencePrice);
		}
		final long scaled = Math.round(referencePrice * multiplier);
		return (int) Math.max(1L, Math.min(scaled, Integer.MAX_VALUE));
	}

	/**
	 * Price multiplier for an enchanted item. Safe enchants (+1..+3) add a little; beyond +3 (the risky range) each
	 * level compounds, because the odds of getting there fall. The effect scales with grade (an enchanted D weapon is
	 * worth far less than an enchanted S one). +0 and non-positive levels are always 1.0; the result is capped at +16.
	 * @param gradeLevel the CrystalType level (0 none, 1 D, 2 C, 3 B, 4 A, 5 S)
	 * @param enchant the enchant level
	 * @return a multiplier of at least 1.0
	 */
	public static double enchantMultiplier(int gradeLevel, int enchant)
	{
		if (enchant <= 0)
		{
			return 1.0;
		}
		final int e = Math.min(enchant, 16);
		final double base = (e <= 3) ? (0.08 * e) : (0.24 + (0.24 * (Math.pow(1.35, e - 3) - 1.0) / 0.35));
		final double[] gradeWeight = { 0.4, 0.6, 0.8, 1.0, 1.0, 1.1 };
		final double weight = gradeWeight[Math.max(0, Math.min(gradeLevel, gradeWeight.length - 1))];
		return 1.0 + (base * weight);
	}

	/**
	 * Clamp a whisper-negotiated unit price into a sane band around the item reference price. The agreed
	 * price is trust-based on the LLM, so without this a player (or a trade-chat prompt injection) could talk
	 * a bot into selling a rare item for 1 adena or buying junk for billions. Haggling still works within the
	 * band; only absurd values get pinned. When the bot is selling, the floor matters most (don't sell cheap);
	 * when buying, the ceiling matters most (don't overpay).
	 * @param unitPrice the agreed adena per unit (already {@code > 0})
	 * @param referencePrice the item reference price
	 * @param selling {@code true} if the bot sells to the player, {@code false} if it buys from the player
	 * @return the clamped unit price, at least 1
	 */
	public static int clampDealPrice(int unitPrice, int referencePrice, boolean selling)
	{
		if (referencePrice <= 0)
		{
			return Math.max(1, unitPrice);
		}
		final double lowFactor = selling ? 0.5 : 0.1;
		final double highFactor = selling ? 3.0 : 1.5;
		final long low = Math.max(1L, Math.round(referencePrice * lowFactor));
		final long high = Math.max(low, Math.round(referencePrice * highFactor));
		return (int) Math.max(low, Math.min(high, unitPrice));
	}

	/**
	 * Clamp a deal's stack count. Non-stackable items are always a single piece; a requested count is pinned
	 * to [1, 2,000,000] so a negotiated/injected quantity can never overflow or drain a stack; otherwise the
	 * caller's auto-sized fallback is used (at least 1).
	 * @param stackable whether the item stacks
	 * @param requestedCount the requested stack count, or 0 to auto-size
	 * @param fallbackCount the auto-sized count to use when nothing was requested
	 * @return the normalised count, at least 1
	 */
	public static int normalizedDealCount(boolean stackable, int requestedCount, int fallbackCount)
	{
		if (!stackable)
		{
			return 1;
		}
		if (requestedCount > 0)
		{
			return Math.max(1, Math.min(requestedCount, 2_000_000));
		}
		return Math.max(1, fallbackCount);
	}

	/**
	 * The believable stack-size band for a stackable good, scaled inversely to its unit value (cheap shots in
	 * the thousands, pricey mats in single digits). The caller rolls a random amount within the band.
	 * @param referencePrice the item reference price
	 * @return a two-element {@code {min, max}} range
	 */
	public static int[] bulkAmountRange(int referencePrice)
	{
		if (referencePrice <= 10)
		{
			return new int[]
			{
				2000,
				15000
			}; // shots, arrows, cheap mats
		}
		if (referencePrice <= 60)
		{
			return new int[]
			{
				300,
				3000
			};
		}
		if (referencePrice <= 600)
		{
			return new int[]
			{
				30,
				400
			};
		}
		if (referencePrice <= 6000)
		{
			return new int[]
			{
				3,
				40
			};
		}
		return new int[]
		{
			1,
			8
		}; // pricey mats / scrolls
	}

	/**
	 * Compact shop-sign abbreviation for a soulshot/spiritshot item name, or {@code null} if the name is not a
	 * shot. Soulshot -&gt; "SS", Spiritshot -&gt; "SPS", Blessed Spiritshot -&gt; "BSS", with the grade appended
	 * ("SSD", "BSSD", or "SS NG" for No Grade) - the shorthand L2 players actually use on shop signs.
	 * Case-insensitive and tolerant of the datapack's ": D-Grade" / " D grade" spellings.
	 * @param name the item name
	 * @return the abbreviation, or {@code null} when the name is not a soulshot/spiritshot
	 */
	public static String shotAbbrev(String name)
	{
		if (name == null)
		{
			return null;
		}
		final String lower = name.toLowerCase(Locale.US);
		final boolean spirit = lower.contains("spiritshot");
		final boolean soul = lower.contains("soulshot");
		if (!spirit && !soul)
		{
			return null;
		}
		final String base = spirit ? (lower.contains("blessed") ? "BSS" : "SPS") : "SS";
		return base + shotGradeTag(lower);
	}

	/** The grade suffix for a shot name: "NG" (spaced) for No Grade, else the bare grade letter, else empty. */
	private static String shotGradeTag(String lower)
	{
		if (lower.contains("no grade"))
		{
			return " NG";
		}
		for (String grade : new String[]
		{
			"d",
			"c",
			"b",
			"a",
			"s"
		})
		{
			if (lower.contains(grade + "-grade") || lower.contains(grade + " grade"))
			{
				return grade.toUpperCase(Locale.US);
			}
		}
		return "";
	}

	/**
	 * The shop-sign label for a shot line: {@link #shotAbbrev} plus, when {@code withPrice}, the per-unit price in
	 * adena ("SSD 100a"). This is the classic L2 vendor convention for advertising shots.
	 * @param name the item name
	 * @param unitPrice the per-unit price in adena
	 * @param withPrice whether to append the per-unit price (sellers advertise it; buy signs omit it)
	 * @return the sign label, or {@code null} when the name is not a shot
	 */
	public static String shotSign(String name, int unitPrice, boolean withPrice)
	{
		final String abbr = shotAbbrev(name);
		if (abbr == null)
		{
			return null;
		}
		return withPrice ? (abbr + " " + amount(unitPrice) + "a") : abbr;
	}

	/** 15000 -&gt; "15k", 1500 -&gt; "1.5k", 800 -&gt; "800". */
	public static String amount(int count)
	{
		if (count >= 1000)
		{
			final double thousands = count / 1000.0;
			return (thousands == Math.floor(thousands) ? String.valueOf((int) thousands) : String.format(Locale.US, "%.1f", thousands)) + "k";
		}
		return String.valueOf(count);
	}

	/**
	 * Round an auto-generated price to a natural, round value a real player would actually say, instead of the
	 * ugly exact number the randomised markup produces (45,320 -&gt; 45,000). Small per-unit prices (shots, arrows)
	 * stay exact so "3a each" is preserved; 100-999 rounds to the nearest 10; anything from 1000 up rounds to two
	 * significant figures. This is only for auto-pricing - a price the player explicitly stated is honored as-is
	 * (only the anti-injection clamp applies), so their number is never second-guessed.
	 * @param price the raw price to round
	 * @return the rounded price, at least 1 for any positive input
	 */
	public static int naturalizePrice(int price)
	{
		if (price <= 0)
		{
			return Math.max(1, price);
		}
		if (price < 100)
		{
			return price; // small per-unit prices stay exact ("3a each")
		}
		if (price < 1000)
		{
			return (int) (Math.round(price / 10.0) * 10); // nearest 10
		}
		// Two significant figures for anything >= 1000.
		final int digits = (int) Math.floor(Math.log10(price)) + 1;
		final long step = (long) Math.pow(10, digits - 2);
		final long rounded = Math.round(price / (double) step) * step;
		return (int) Math.max(1L, Math.min(rounded, Integer.MAX_VALUE));
	}

	/**
	 * Format an adena amount the way an Interlude player types it in chat: k for thousands, kk for millions
	 * (45,000 -&gt; "45k", 470,000 -&gt; "470k", 1,200 -&gt; "1.2k", 45,000,000 -&gt; "45kk", 300 -&gt; "300"). Used for
	 * spoken prices and stack sizes so bots quote "45k adena" instead of reading out the full digit string.
	 * @param value the amount in adena (or a stack count)
	 * @return the shorthand text
	 */
	public static String priceText(long value)
	{
		if (value >= 1_000_000L)
		{
			return trimShorthand(value / 1_000_000.0) + "kk";
		}
		if (value >= 1000L)
		{
			return trimShorthand(value / 1000.0) + "k";
		}
		return String.valueOf(value);
	}

	/** A shorthand magnitude: a whole number drops the decimal ("45"), otherwise one decimal place ("1.2"). */
	private static String trimShorthand(double value)
	{
		return (value == Math.floor(value)) ? String.valueOf((long) value) : String.format(Locale.US, "%.1f", value);
	}

	/**
	 * How well an item name's tokens cover the words the player typed (FPC-198).
	 * @param have the item name's tokens
	 * @param wanted the player's tokens
	 * @param fuzzy {@code false} for exact words only; {@code true} to also accept a small typo per word (one letter
	 *            for words up to 7 letters, two for longer ones, none for words of 3 letters or less), so the brain's
	 *            "Homunculus Sword" still finds "Homunkulus's Sword"
	 * @return -1 when some wanted word has no match, else the total number of typos (0 = every word exact)
	 */
	public static int tokenMatchCost(List<String> have, List<String> wanted, boolean fuzzy)
	{
		int cost = 0;
		for (String word : wanted)
		{
			if (have.contains(word))
			{
				continue;
			}
			if (!fuzzy)
			{
				return -1;
			}
			final int budget = fuzzyBudget(word.length());
			int best = Integer.MAX_VALUE;
			for (String candidate : have)
			{
				if (fuzzyBudget(candidate.length()) > 0)
				{
					best = Math.min(best, editDistance(word, candidate));
				}
			}
			if ((budget == 0) || (best > budget))
			{
				return -1;
			}
			cost += best;
		}
		return cost;
	}

	/** Normalises a name/phrase to lowercase word tokens: strips punctuation, drops filler, folds plurals. */
	public static List<String> matchTokens(String text)
	{
		final List<String> tokens = new ArrayList<>();
		if (text == null)
		{
			return tokens;
		}
		for (String word : text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim().split(" "))
		{
			if (word.isEmpty() || MATCH_STOPWORDS.contains(word))
			{
				continue;
			}
			if ((word.length() > 3) && word.endsWith("s"))
			{
				word = word.substring(0, word.length() - 1); // crude singularize: soulshots -> soulshot
			}
			tokens.add(word);
		}
		return tokens;
	}

	/** LW FakePlayerChatParsing.fuzzyBudget: typos allowed in a word (none up to 3 letters, one up to 7, two beyond). */
	static int fuzzyBudget(int length)
	{
		return (length <= 3) ? 0 : ((length <= 7) ? 1 : 2);
	}

	/** LW FakePlayerChatParsing.editDistance: Levenshtein distance. */
	static int editDistance(String a, String b)
	{
		final int[] prev = new int[b.length() + 1];
		final int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++)
		{
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++)
		{
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++)
			{
				final int sub = prev[j - 1] + ((a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1);
				cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
			}
			System.arraycopy(cur, 0, prev, 0, cur.length);
		}
		return prev[b.length()];
	}
}

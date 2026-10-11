
/** Standalone regression harness for the curated FakePlayer market allow-list. */
package org.l2start.economy.market.lw;

public class StoreEligibilityTest
{
	private static int checks;
	private static int failures;

	@org.junit.jupiter.api.Test
	public void lwChecks()
	{
		eq(2440, StoreEligibility.size(), "curated pool size");

		allowed(126, "Artisan's Sword (D-grade base weapon, added by request)");
		allowed(1463, "D-grade Soulshot");
		allowed(3948, "D-grade Blessed Spiritshot");
		allowed(5575, "Ancient Adena");
		allowed(6569, "Blessed Enchant Weapon D");
		allowed(6578, "Blessed Enchant Armor S");

		blocked(1, "ordinary NPC retail weapon");
		blocked(57, "base Adena");
		blocked(1835, "NPC retail No-grade Soulshot");
		blocked(3947, "NPC retail No-grade Blessed Spiritshot");
		blocked(6645, "specialist Beast Soulshot");

		if (failures > 0)
		{
			throw new AssertionError(failures + " of " + checks + " eligibility checks failed");
		}
		System.out.println("PASS: " + checks + " FakePlayer store eligibility checks");
	}

	private static void allowed(int itemId, String what)
	{
		eq(true, StoreEligibility.isAllowed(itemId), what + " is included");
	}

	private static void blocked(int itemId, String what)
	{
		eq(false, StoreEligibility.isAllowed(itemId), what + " is excluded");
	}

	private static void eq(Object expected, Object actual, String what)
	{
		checks++;
		if (!expected.equals(actual))
		{
			failures++;
			System.out.println("FAIL: " + what + " - expected [" + expected + "] but got [" + actual + "]");
		}
	}
}

package org.l2start.rates.floating;

import java.util.Locale;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;

/** //rates: the floating rates on the GM's level (and against his target), and the statistics since start. */
final class AdminRates implements IAdminCommandHandler {
    private final RatesFloatingModule m;

    AdminRates(RatesFloatingModule m) {
        this.m = m;
    }

    @Override
    public String[] getCommandList() {
        return new String[] { "admin_rates" };
    }

    @Override
    public boolean onCommand(String command, Player gm) {
        final Rules r = m.rules();
        final int lvl = RatesFloatingModule.refLevel(gm);
        gm.sendMessage("rates-floating " + (m.enabled() ? "ON" : "OFF") + String.format(Locale.ROOT, ": adena x%.1f, amount x1..x%.1f (mean %.2f), raid chance x%.1f, epic x1", r.adenaRate(),
            r.amountMax(), r.meanAmount(), r.raidChance()));
        final WorldObject t = gm.getTarget();
        final int mob = ((t != null) && t.isMonster()) ? t.asMonster().getLevel() : lvl;
        final double f = r.f(lvl, mob);
        gm.sendMessage(String.format(Locale.ROOT, "Level %d vs mob %d: F=%.2f (level %.2f x diff %.2f), drop chance x%.2f, spoil x%.2f", lvl, mob, f, r.levelCurve().at(lvl),
            r.diffCurve().at(mob - lvl), r.chance(false, f), r.chance(true, f)));
        gm.sendMessage(m.stats());
        return true;
    }
}

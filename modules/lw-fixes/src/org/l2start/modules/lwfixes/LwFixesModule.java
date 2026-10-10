package org.l2start.modules.lwfixes;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Fixes from L2-Living-Worlds that need no core change.
 * <p>
 * Cursed weapon (Zariche / Akamanah): when the owner dies, stock {@code CursedWeapon.dropIt} drops the weapon (or it
 * vanishes) but never resends the item list, so the owner's client keeps showing it equipped until relog.
 * {@code ON_CREATURE_DEATH} fires before that drop ({@code Player.doDie} calls {@code super.doDie} first), so we remember
 * the owner and refresh his client a moment later, once the weapon is gone.
 */
public final class LwFixesModule implements GameModule {
    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.events().<OnCreatureDeath> onPlayers(EventType.ON_CREATURE_DEATH, e -> onDeath(ctx, e.getTarget()));
        ctx.log().info("enabled: CursedWeaponRefresh=" + ctx.config().getBoolean("CursedWeaponRefresh") + ", delay " + ctx.config().getDuration("CursedWeaponRefreshDelay").toMillis() + " ms");
    }

    private static void onDeath(ModuleContext ctx, Creature target) {
        if (!ctx.config().getBoolean("CursedWeaponRefresh") || (target == null) || !target.isPlayer()) {
            return;
        }
        final Player player = target.asPlayer();
        if (!player.isCursedWeaponEquipped()) {
            return;
        }
        final int itemId = player.getCursedWeaponEquippedId();
        ctx.scheduler().schedule("cursed-weapon-refresh", ctx.config().getDuration("CursedWeaponRefreshDelay"), () -> {
            if (!CursedRefresh.needed(player.isOnline(), player.isCursedWeaponEquipped())) {
                return;
            }
            player.sendItemList(false);
            player.broadcastUserInfo();
            ctx.metrics().counter("cursedWeaponRefresh").inc();
            ctx.log().info("cursed weapon " + itemId + " left " + player.getName() + ": client refreshed");
        });
    }
}

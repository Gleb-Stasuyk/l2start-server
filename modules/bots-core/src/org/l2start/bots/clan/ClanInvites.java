package org.l2start.bots.clan;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.network.serverpackets.AskJoinPledge;

/**
 * Phase 7e/7f: stock clan invite sequence (same path as {@code RequestJoinPledge}).
 */
public final class ClanInvites {
    private ClanInvites() {
    }

    /**
     * @return true when AskJoinPledge was sent
     */
    public static boolean invite(Player from, Player target, int pledgeType) {
        if ((from == null) || (target == null)) {
            return false;
        }
        final Clan clan = from.getClan();
        if ((clan == null) || (target.getClient() == null) || target.getClient().isDetached() || (target.getClan() != null)
            || !clan.checkClanJoinCondition(from, target, pledgeType)) {
            return false;
        }
        final org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge packet = newJoinPledgePacket(target.getObjectId(), pledgeType);
        if ((packet == null) || !from.getRequest().setRequest(target, packet)) {
            return false;
        }
        final String sub = clan.getSubPledge(pledgeType) != null ? clan.getSubPledge(pledgeType).getName() : null;
        target.sendPacket(new AskJoinPledge(from.getObjectId(), sub, pledgeType, clan.getName()));
        return true;
    }

    public static boolean inviteByName(Player from, String targetName, int pledgeType) {
        final Player target = targetName == null ? null : World.getPlayer(targetName);
        return invite(from, target, pledgeType);
    }

    private static org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge newJoinPledgePacket(int targetOid, int pledgeType) {
        try {
            final var p = org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge.class.getDeclaredConstructor().newInstance();
            final var t = org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge.class.getDeclaredField("_target");
            final var typ = org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge.class.getDeclaredField("_pledgeType");
            t.setAccessible(true);
            typ.setAccessible(true);
            t.setInt(p, targetOid);
            typ.setInt(p, pledgeType);
            return p;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}

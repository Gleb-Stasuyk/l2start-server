/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.network.clientpackets;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanAccess;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.network.serverpackets.PledgeReceivePowerInfo;

/**
 * Format: (ch) Sd
 * @author -Wooden-
 */
public class RequestPledgeSetMemberPowerGrade extends ClientPacket
{
	private String _member;
	private int _powerGrade;
	
	@Override
	protected void readImpl()
	{
		_member = readString();
		_powerGrade = readInt();
	}
	
	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if (player == null)
		{
			return;
		}
		
		final Clan clan = player.getClan();
		if (clan == null)
		{
			return;
		}
		
		if (!player.hasAccess(ClanAccess.MODIFY_RANKS))
		{
			return;
		}
		
		final ClanMember member = clan.getClanMember(_member);
		if (member == null)
		{
			return;
		}
		
		if (member.getObjectId() == clan.getLeaderId())
		{
			return;
		}
		
		if (member.getPledgeType() == Clan.SUBUNIT_ACADEMY)
		{
			// Also checked from client side.
			player.sendMessage("You cannot change academy member grade");
			return;
		}
		
		member.setPowerGrade(_powerGrade);
		
		// An online member keeps the privileges of the old rank until refreshed.
		final Player memberPlayer = member.getPlayer();
		if (memberPlayer != null)
		{
			memberPlayer.setClanPrivileges(clan.getRankPrivs(_powerGrade).clone());
			memberPlayer.updateUserInfo();
		}
		
		clan.broadcastClanStatus();
		player.sendPacket(new PledgeReceivePowerInfo(member));
	}
}

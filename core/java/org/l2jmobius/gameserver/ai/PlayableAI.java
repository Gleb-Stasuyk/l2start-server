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
package org.l2jmobius.gameserver.ai;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Playable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.SystemMessageId;

/**
 * This class manages AI of Playable.<br>
 * PlayableAI:
 * <li>SummonAI</li>
 * <li>PlayerAI</li>
 * @author JIV, Mobius
 */
public abstract class PlayableAI extends CreatureAI
{
	protected PlayableAI(Playable playable)
	{
		super(playable);
	}
	
	@Override
	public void setIntentionAttack(WorldObject target)
	{
		if ((target != null) && target.isPlayable())
		{
			final Player player = _actor.asPlayer();
			final Player targetPlayer = target.asPlayer();
			if ((player != null) && (targetPlayer != null))
			{
				if (targetPlayer.isProtectionBlessingAffected() && ((player.getLevel() - targetPlayer.getLevel()) >= 10) && (player.getKarma() > 0) && !(target.isInsideZone(ZoneId.PVP)))
				{
					// If attacker have karma and have level >= 10 than his target and target have Newbie Protection Buff.
					player.sendPacket(SystemMessageId.THAT_IS_THE_INCORRECT_TARGET);
					clientActionFailed();
					return;
				}
				
				if (player.isProtectionBlessingAffected() && ((targetPlayer.getLevel() - player.getLevel()) >= 10) && (targetPlayer.getKarma() > 0) && !(target.isInsideZone(ZoneId.PVP)))
				{
					// If target have karma and have level >= 10 than his target and actor have Newbie Protection Buff.
					player.sendPacket(SystemMessageId.THAT_IS_THE_INCORRECT_TARGET);
					clientActionFailed();
					return;
				}
				
				if (targetPlayer.isCursedWeaponEquipped() && (player.getLevel() <= 20))
				{
					player.sendPacket(SystemMessageId.THAT_IS_THE_INCORRECT_TARGET);
					clientActionFailed();
					return;
				}
				
				if (player.isCursedWeaponEquipped() && (targetPlayer.getLevel() <= 20))
				{
					player.sendPacket(SystemMessageId.THAT_IS_THE_INCORRECT_TARGET);
					clientActionFailed();
					return;
				}
			}
		}
		
		super.setIntentionAttack(target);
	}
	
	@Override
	public void setIntentionCast(Skill skill, WorldObject target)
	{
		if ((target != null) && (target.isPlayable()) && (skill != null) && skill.hasNegativeEffect())
		{
			final Player player = _actor.asPlayer();
			final Player targetPlayer = target.asPlayer();
			if ((player != null) && (targetPlayer != null))
			{
				if (targetPlayer.isProtectionBlessingAffected() && ((player.getLevel() - targetPlayer.getLevel()) >= 10) && (player.getKarma() > 0) && !target.isInsideZone(ZoneId.PVP))
				{
					// If attacker have karma and have level >= 10 than his target and target have Newbie Protection Buff.
					player.sendPacket(SystemMessageId.THAT_IS_THE_INCORRECT_TARGET);
					clientActionFailed();
					return;
				}
				
				if (player.isProtectionBlessingAffected() && ((targetPlayer.getLevel() - player.getLevel()) >= 10) && (targetPlayer.getKarma() > 0) && !target.isInsideZone(ZoneId.PVP))
				{
					// If target have karma and have level >= 10 than his target and actor have Newbie Protection Buff.
					player.sendPacket(SystemMessageId.THAT_IS_THE_INCORRECT_TARGET);
					clientActionFailed();
					return;
				}
				
				if (targetPlayer.isCursedWeaponEquipped() && ((player.getLevel() <= 20) || (targetPlayer.getLevel() <= 20)))
				{
					player.sendPacket(SystemMessageId.THAT_IS_THE_INCORRECT_TARGET);
					clientActionFailed();
					return;
				}
			}
		}
		
		super.setIntentionCast(skill, target);
	}
}

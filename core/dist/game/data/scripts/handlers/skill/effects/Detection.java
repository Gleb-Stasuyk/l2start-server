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
package handlers.skill.effects;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.conditions.Condition;
import org.l2jmobius.gameserver.mechanics.effects.AbstractEffect;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * Detection effect implementation.
 * @author UnAfraid
 */
public class Detection extends AbstractEffect
{
	public Detection(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
	}
	
	@Override
	public boolean isInstant()
	{
		return true;
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		if (!effector.isPlayer() || !effected.isPlayer())
		{
			return;
		}
		
		final Player player = effector.asPlayer();
		final Player target = effected.asPlayer();
		if (target.isInvisible())
		{
			if (player.isInPartyWith(target))
			{
				return;
			}
			
			if (player.isInClanWith(target))
			{
				return;
			}
			
			if (player.isInAllyWith(target))
			{
				return;
			}
			
			// Remove Hide.
			target.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, AbnormalType.HIDE);
		}
	}
}

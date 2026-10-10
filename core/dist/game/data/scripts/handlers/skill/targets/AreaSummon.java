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
package handlers.skill.targets;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.ITargetTypeHandler;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;

/**
 * @author UnAfraid
 */
public class AreaSummon implements ITargetTypeHandler
{
	@Override
	public List<WorldObject> getTargetList(Skill skill, Creature creature, boolean onlyFirst, Creature target)
	{
		final List<WorldObject> targetList = new ArrayList<>();
		final Creature targetCreature = creature.isPlayer() ? creature.asPlayer().getSummon() : null;
		if ((targetCreature == null) || !targetCreature.isServitor() || targetCreature.isDead())
		{
			return targetList;
		}
		
		if (onlyFirst)
		{
			targetList.add(targetCreature);
			return targetList;
		}
		
		final boolean srcInArena = (creature.isInsideZone(ZoneId.PVP) && !creature.isInsideZone(ZoneId.SIEGE));
		final int maxTargets = skill.getAffectLimit();
		World.forEachVisibleObjectInRange(targetCreature, Creature.class, skill.getAffectRange(), obj ->
		{
			if (obj == creature)
			{
				return;
			}
			
			if (!(obj.isAttackable() || obj.isPlayable()))
			{
				return;
			}
			
			if (!Skill.checkForAreaOffensiveSkills(creature, obj, skill, srcInArena))
			{
				return;
			}
			
			if ((maxTargets > 0) && (targetList.size() >= maxTargets))
			{
				return;
			}
			
			targetList.add(obj);
		});
		
		return targetList;
	}
	
	@Override
	public Enum<TargetType> getTargetType()
	{
		return TargetType.AREA_SUMMON;
	}
}

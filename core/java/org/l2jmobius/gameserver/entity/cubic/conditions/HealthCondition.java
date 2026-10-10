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
package org.l2jmobius.gameserver.entity.cubic.conditions;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.instance.Cubic;

/**
 * Validates target HP percentage inside a configured range.
 * @author UnAfraid
 */
public class HealthCondition implements ICubicCondition
{
	private final int _min;
	private final int _max;
	
	public HealthCondition(int min, int max)
	{
		_min = min;
		_max = max;
	}
	
	@Override
	public boolean test(Cubic cubic, Creature owner, WorldObject target)
	{
		if ((target == null) || (!target.isCreature() && !target.isDoor()))
		{
			return false;
		}
		
		final double hpPercent = (target.isDoor() ? target.asDoor() : target.asCreature()).getCurrentHpPercent();
		return (hpPercent > _min) && (hpPercent < _max);
	}
}

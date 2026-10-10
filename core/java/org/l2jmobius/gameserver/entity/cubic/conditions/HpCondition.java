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
 * Validates target HP percentage against a configured threshold.
 * @author UnAfraid
 */
public class HpCondition implements ICubicCondition
{
	private final HpConditionType _type;
	private final int _hpPercent;
	
	public HpCondition(HpConditionType type, int hpPercent)
	{
		_type = type;
		_hpPercent = hpPercent;
	}
	
	@Override
	public boolean test(Cubic cubic, Creature owner, WorldObject target)
	{
		if ((target == null) || (!target.isCreature() && !target.isDoor()))
		{
			return false;
		}
		
		final double hpPercent = (target.isDoor() ? target.asDoor() : target.asCreature()).getCurrentHpPercent();
		switch (_type)
		{
			case GREATER:
			{
				return hpPercent > _hpPercent;
			}
			case LESSER:
			{
				return hpPercent < _hpPercent;
			}
		}
		
		return false;
	}
	
	public enum HpConditionType
	{
		GREATER,
		LESSER;
	}
}

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
package org.l2jmobius.gameserver.entity.cubic;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.instance.Cubic;
import org.l2jmobius.gameserver.entity.cubic.conditions.ICubicCondition;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * Cubic skill data loaded from XML.<br>
 * Stores target routing and optional execution conditions for a cubic skill.
 * @author UnAfraid
 */
public class CubicSkill extends SkillHolder implements ICubicConditionHolder
{
	private final int _triggerRate;
	private final CubicTargetType _targetType;
	private final boolean _targetDebuff;
	private final boolean _priority;
	private final List<ICubicCondition> _conditions = new ArrayList<>();
	
	public CubicSkill(StatSet set)
	{
		super(set.getInt("id"), set.getInt("level"));
		_triggerRate = set.getInt("triggerRate", 100);
		_targetType = set.getEnum("target", CubicTargetType.class, CubicTargetType.TARGET);
		_targetDebuff = set.getBoolean("targetDebuff", false);
		_priority = set.getBoolean("priority", false);
	}
	
	public int getTriggerRate()
	{
		return _triggerRate;
	}
	
	public CubicTargetType getTargetType()
	{
		return _targetType;
	}
	
	public boolean isPriority()
	{
		return _priority;
	}
	
	@Override
	public boolean validateConditions(Cubic cubic, Creature owner, WorldObject target)
	{
		if (target == null)
		{
			return false;
		}
		
		if (_targetDebuff && !hasDispellableDebuff(target))
		{
			return false;
		}
		
		if (_conditions.isEmpty())
		{
			return true;
		}
		
		for (ICubicCondition condition : _conditions)
		{
			if (!condition.test(cubic, owner, target))
			{
				return false;
			}
		}
		
		return true;
	}
	
	private boolean hasDispellableDebuff(WorldObject target)
	{
		if (!target.isCreature())
		{
			return false;
		}
		
		for (BuffInfo info : target.asCreature().getEffectList().getDebuffs())
		{
			if (info.getSkill().canBeDispeled())
			{
				return true;
			}
		}
		
		return false;
	}
	
	@Override
	public void addCondition(ICubicCondition condition)
	{
		_conditions.add(condition);
	}
}

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
package org.l2jmobius.gameserver.entity.actor.tasks.cubics;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.instance.Cubic;
import org.l2jmobius.gameserver.entity.cubic.CubicSkill;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;

/**
 * Executes heal cubic actions.<br>
 * Tracks successful heal usage so consumable heal cubics respect template limits.
 * @author UnAfraid, Mobius
 */
public class CubicHeal implements Runnable
{
	private static final Logger LOGGER = Logger.getLogger(CubicHeal.class.getName());
	
	private final Cubic _cubic;
	private final AtomicInteger _currentCount = new AtomicInteger();
	
	public CubicHeal(Cubic cubic)
	{
		_cubic = cubic;
	}
	
	@Override
	public void run()
	{
		if (_cubic == null)
		{
			return;
		}
		
		if (_cubic.getOwner().isDead() || !_cubic.getOwner().isOnline())
		{
			_cubic.getOwner().removeCubic(_cubic.getId());
			_cubic.getOwner().broadcastUserInfo();
			return;
		}
		
		try
		{
			if ((_cubic.getCubicMaxCount() > -1) && (_currentCount.get() >= _cubic.getCubicMaxCount()))
			{
				_cubic.stopAction();
				if ((_cubic.getTemplate() != null) && (_cubic.getTemplate().getUseUp() > 0))
				{
					_cubic.getOwner().removeCubic(_cubic.getId());
					_cubic.getOwner().broadcastUserInfo();
				}
				return;
			}
			
			_cubic.cubicTargetForHeal();
			final Creature target = _cubic.getTarget();
			if ((target == null) || target.isDead())
			{
				return;
			}
			
			final CubicSkill cubicSkill = getHealSkill(target);
			if (cubicSkill == null)
			{
				return;
			}
			
			final Skill skill = cubicSkill.getSkill();
			if ((skill == null) || ((target.getMaxHp() - target.getCurrentHp()) <= skill.getPower()))
			{
				return;
			}
			
			skill.activateSkill(_cubic, Collections.singletonList(target));
			_cubic.getOwner().broadcastPacket(new MagicSkillUse(_cubic.getOwner(), target, skill.getId(), skill.getLevel(), 0, 0));
			_currentCount.incrementAndGet();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Cubic heal failed. cubicId: " + _cubic.getId() + " ownerId: " + _cubic.getOwner().getObjectId(), e);
		}
	}
	
	private CubicSkill getHealSkill(Creature target)
	{
		if ((_cubic.getTemplate() != null) && !_cubic.getTemplate().validateConditions(_cubic, _cubic.getOwner(), target))
		{
			return null;
		}
		
		for (CubicSkill cubicSkill : _cubic.getCubicSkills())
		{
			final Skill skill = cubicSkill.getSkill();
			if ((skill != null) && (skill.getId() == Cubic.SKILL_CUBIC_HEAL) && cubicSkill.validateConditions(_cubic, _cubic.getOwner(), target))
			{
				return cubicSkill;
			}
		}
		
		return null;
	}
}

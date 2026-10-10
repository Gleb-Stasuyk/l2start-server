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

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.instance.Defender;
import org.l2jmobius.gameserver.entity.actor.instance.Door;
import org.l2jmobius.gameserver.interfaces.ILocational;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * @author mkizub, Mobius
 */
public class DoorAI extends CreatureAI
{
	public DoorAI(Door door)
	{
		super(door);
	}
	
	@Override
	public void setIntentionIdle()
	{
	}
	
	@Override
	public void setIntentionActive()
	{
	}
	
	@Override
	public void setIntentionRest()
	{
	}
	
	@Override
	public void setIntentionAttack(WorldObject target)
	{
	}
	
	@Override
	public void setIntentionCast(Skill skill, WorldObject target)
	{
	}
	
	@Override
	public void setIntentionMoveTo(ILocational destination)
	{
	}
	
	@Override
	public void setIntentionFollow(WorldObject target)
	{
	}
	
	@Override
	public void setIntentionPickUp(WorldObject item)
	{
	}
	
	@Override
	public void setIntentionInteract(WorldObject object)
	{
	}
	
	@Override
	public void notifyActionThink()
	{
	}
	
	@Override
	public void notifyActionAttacked(WorldObject attacker)
	{
		if (attacker == null)
		{
			return;
		}
		
		final Creature attackerCreature = attacker.asCreature();
		if (attackerCreature == null)
		{
			return;
		}
		
		ThreadPool.execute(() -> World.forEachVisibleObject(_actor.asDoor(), Defender.class, guard ->
		{
			if (_actor.isInsideRadius3D(guard, guard.getTemplate().getClanHelpRange()))
			{
				guard.getAI().notifyActionAggression(attackerCreature, 15);
			}
		}));
	}
	
	@Override
	public void notifyActionAggression(WorldObject target, int aggro)
	{
	}
	
	@Override
	public void notifyActionStunned()
	{
	}
	
	@Override
	public void notifyActionSleeping()
	{
	}
	
	@Override
	public void notifyActionRooted()
	{
	}
	
	@Override
	public void notifyActionReadyToAct()
	{
	}
	
	@Override
	public void notifyActionUserCmd(Object arg0, Object arg1)
	{
	}
	
	@Override
	public void notifyActionArrived()
	{
	}
	
	@Override
	public void notifyActionArrivedRevalidate()
	{
	}
	
	@Override
	public void notifyActionArrivedBlocked(Location blockedAtLoc)
	{
	}
	
	@Override
	public void notifyActionForgetObject(WorldObject object)
	{
	}
	
	@Override
	public void notifyActionCancel()
	{
	}
	
	@Override
	public void notifyActionDeath()
	{
	}
}

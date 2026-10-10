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

import java.util.concurrent.Future;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

public class SummonAI extends PlayableAI implements Runnable
{
	private static final int AVOID_RADIUS = 70;
	
	private volatile boolean _thinking; // To prevent recursive thinking.
	private volatile boolean _startFollow = _actor.asSummon().getFollowStatus();
	private Creature _lastAttack = null;
	
	private volatile boolean _startAvoid = false;
	private Future<?> _avoidTask = null;
	
	public SummonAI(Summon creature)
	{
		super(creature);
	}
	
	@Override
	public Intention getNextIntention()
	{
		return _lastAttack != null ? Intention.ATTACK : null;
	}
	
	@Override
	public void setIntentionAttack(WorldObject target)
	{
		if (target == null)
		{
			return;
		}
		
		final Creature creatureTarget = target.asCreature();
		if (creatureTarget == null)
		{
			return;
		}
		
		if ((GeoEngineConfig.PATHFINDING > 0) && (PathFinding.getInstance().findPath(_actor.getX(), _actor.getY(), _actor.getZ(), creatureTarget.getX(), creatureTarget.getY(), creatureTarget.getZ(), _actor.getInstanceId(), false) == null))
		{
			return;
		}
		
		stopAvoidTask();
		super.setIntentionAttack(target);
	}
	
	@Override
	public void setIntentionIdle()
	{
		stopFollow();
		_startFollow = false;
		stopAvoidTask();
		setIntentionActive();
	}
	
	@Override
	public void setIntentionActive()
	{
		if (_startFollow)
		{
			startAvoidTask();
			setIntentionFollow(_actor.asSummon().getOwner());
		}
		else
		{
			startAvoidTask();
			super.setIntentionActive();
		}
	}
	
	@Override
	public void setIntentionFollow(WorldObject target)
	{
		if (target == null)
		{
			clientActionFailed();
			return;
		}
		
		final Creature creatureTarget = target.asCreature();
		if (creatureTarget == null)
		{
			clientActionFailed();
			return;
		}
		
		if ((GeoEngineConfig.PATHFINDING > 0) && (PathFinding.getInstance().findPath(_actor.getX(), _actor.getY(), _actor.getZ(), creatureTarget.getX(), creatureTarget.getY(), creatureTarget.getZ(), _actor.getInstanceId(), false) == null))
		{
			clientActionFailed();
			return;
		}
		
		startAvoidTask();
		super.setIntentionFollow(target);
	}
	
	private void thinkAttack()
	{
		if (checkTargetLostOrDead(getAttackTarget()))
		{
			setAttackTarget(null);
			return;
		}
		
		if (maybeMoveToPawn(getAttackTarget(), _actor.getPhysicalAttackRange()))
		{
			return;
		}
		
		clientStopMoving(null);
		_actor.doAttack(getAttackTarget());
	}
	
	private void thinkCast()
	{
		if (checkTargetLost(getCastTarget()))
		{
			setCastTarget(null);
			return;
		}
		
		final boolean val = _startFollow;
		if (maybeMoveToPawn(getCastTarget(), _actor.getMagicalAttackRange(_skill)))
		{
			return;
		}
		
		clientStopMoving(null);
		final Summon summon = _actor.asSummon();
		summon.setFollowStatus(false);
		setIntentionIdle();
		_startFollow = val;
		_actor.doCast(_skill);
	}
	
	private void thinkPickUp()
	{
		if (checkTargetLost(getTarget()) || maybeMoveToPawn(getTarget(), 36))
		{
			return;
		}
		
		setIntentionIdle();
		_actor.asSummon().doPickupItem(getTarget());
	}
	
	private void thinkInteract()
	{
		if (checkTargetLost(getTarget()) || maybeMoveToPawn(getTarget(), 36))
		{
			return;
		}
		
		setIntentionIdle();
	}
	
	@Override
	public void notifyActionThink()
	{
		if (_thinking || _actor.isCastingNow() || _actor.isAllSkillsDisabled())
		{
			return;
		}
		
		_thinking = true;
		
		try
		{
			switch (getIntention())
			{
				case ATTACK:
				{
					thinkAttack();
					break;
				}
				case CAST:
				{
					thinkCast();
					break;
				}
				case PICK_UP:
				{
					thinkPickUp();
					break;
				}
				case INTERACT:
				{
					thinkInteract();
					break;
				}
			}
		}
		finally
		{
			_thinking = false;
		}
	}
	
	@Override
	public void notifyActionFinishCasting()
	{
		if (_lastAttack == null)
		{
			_actor.asSummon().setFollowStatus(_startFollow);
		}
		else
		{
			final Creature replayTarget = _lastAttack;
			_lastAttack = null;
			setIntentionAttack(replayTarget);
		}
		
		super.notifyActionFinishCasting();
	}
	
	@Override
	public void notifyActionAttacked(WorldObject attacker)
	{
		super.notifyActionAttacked(attacker);
		
		if (attacker != null)
		{
			final Creature attackerCreature = attacker.asCreature();
			if (attackerCreature != null)
			{
				avoidAttack(attackerCreature);
			}
		}
	}
	
	@Override
	public void notifyActionEvaded(WorldObject attacker)
	{
		super.notifyActionEvaded(attacker);
		
		if (attacker != null)
		{
			final Creature attackerCreature = attacker.asCreature();
			if (attackerCreature != null)
			{
				avoidAttack(attackerCreature);
			}
		}
	}
	
	private void avoidAttack(Creature attacker)
	{
		// Trying to avoid if summon near owner.
		if ((_actor.asSummon().getOwner() != null) && (_actor.asSummon().getOwner() != attacker) && _actor.asSummon().getOwner().isInsideRadius3D(_actor, 2 * AVOID_RADIUS))
		{
			_startAvoid = true;
		}
	}
	
	@Override
	public void run()
	{
		if (!_startAvoid)
		{
			return;
		}
		
		_startAvoid = false;
		
		if (_actor.isMoving() || _actor.isDead() || _actor.isMovementDisabled())
		{
			return;
		}
		
		final int ownerX = _actor.asSummon().getOwner().getX();
		final int ownerY = _actor.asSummon().getOwner().getY();
		final double angle = Math.toRadians(Rnd.get(-90, 90)) + Math.atan2(ownerY - _actor.getY(), ownerX - _actor.getX());
		final int targetX = ownerX + (int) (AVOID_RADIUS * Math.cos(angle));
		final int targetY = ownerY + (int) (AVOID_RADIUS * Math.sin(angle));
		if (GeoEngine.getInstance().canMoveToTarget(_actor.getX(), _actor.getY(), _actor.getZ(), targetX, targetY, _actor.getZ(), _actor.getInstanceId()))
		{
			moveTo(targetX, targetY, _actor.getZ());
		}
	}
	
	public void notifyFollowStatusChange()
	{
		_startFollow = !_startFollow;
		switch (getIntention())
		{
			case ACTIVE:
			case FOLLOW:
			case IDLE:
			case MOVE_TO:
			case PICK_UP:
			{
				_actor.asSummon().setFollowStatus(_startFollow);
			}
		}
	}
	
	public void setStartFollowController(boolean value)
	{
		_startFollow = value;
	}
	
	@Override
	public void setIntentionCast(Skill skill, WorldObject target)
	{
		if (getIntention() == Intention.ATTACK)
		{
			_lastAttack = getAttackTarget();
		}
		else
		{
			_lastAttack = null;
		}
		
		stopAvoidTask();
		super.setIntentionCast(skill, target);
	}
	
	private void startAvoidTask()
	{
		if (_avoidTask == null)
		{
			_avoidTask = ThreadPool.scheduleAtFixedRate(this, 100, 100);
		}
	}
	
	private void stopAvoidTask()
	{
		if (_avoidTask != null)
		{
			_avoidTask.cancel(false);
			_avoidTask = null;
		}
	}
	
	@Override
	public void stopAITask()
	{
		stopAvoidTask();
		super.stopAITask();
	}
}

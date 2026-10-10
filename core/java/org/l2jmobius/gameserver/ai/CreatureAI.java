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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.WorldRegion;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.Weapon;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.interfaces.ILocational;
import org.l2jmobius.gameserver.managers.WalkingManager;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.events.EventDispatcher;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnNpcMoveFinished;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.network.serverpackets.AutoAttackStop;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * This class manages AI of Creature.<br>
 * CreatureAI:
 * <ul>
 * <li>AttackableAI</li>
 * <li>DoorAI</li>
 * <li>PlayerAI</li>
 * <li>SummonAI</li>
 * </ul>
 */
public class CreatureAI extends AbstractAI
{
	private OnNpcMoveFinished _onNpcMoveFinished = null;
	
	protected static final int FEAR_RANGE = 500;
	
	/**
	 * Constructor of CreatureAI.
	 * @param creature the creature
	 */
	public CreatureAI(Creature creature)
	{
		super(creature);
	}
	
	/**
	 * Helper used internally to check whether the AI may handle the next action.
	 * @return {@code true} if the actor is spawned (or teleporting) and currently has an AI bound to it.
	 */
	private boolean canHandleAction()
	{
		return (_actor.isSpawned() || _actor.isTeleporting()) && _actor.hasAI();
	}
	
	@Override
	public void notifyActionAttacked(WorldObject attacker)
	{
		if (!canHandleAction())
		{
			return;
		}
		
		clientStartAutoAttack();
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.ATTACKED))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Manage the Idle Intention : Stop Attack, Movement and Stand Up the actor.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Set the AI Intention to IDLE</li>
	 * <li>Init cast and attack target</li>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * <li>Stand up the actor server side AND client side by sending Server->Client packet ChangeWaitType (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void setIntentionIdle()
	{
		stopFollow();
		// Set the AI Intention to IDLE.
		_intention = Intention.IDLE;
		
		// Init cast and attack target.
		setCastTarget(null);
		setAttackTarget(null);
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(null);
		
		// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
		clientStopAutoAttack();
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isRemovedBy(Intention.IDLE))
		{
			setNextAction(null);
		}
	}
	
	/**
	 * Manage the Active Intention : Stop Attack, Movement and Launch Think Action.<br>
	 * <br>
	 * <b><u>Actions</u> : <i>if the Intention is not already Active</i></b>
	 * <ul>
	 * <li>Set the AI Intention to ACTIVE</li>
	 * <li>Init cast and attack target</li>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * <li>Launch the Think Action</li>
	 * </ul>
	 */
	@Override
	public void setIntentionActive()
	{
		stopFollow();
		try
		{
			// Check if the Intention is not already Active.
			if (getIntention() == Intention.ACTIVE)
			{
				return;
			}
			
			// Set the AI Intention to ACTIVE.
			_intention = Intention.ACTIVE;
			
			// Check if region and its neighbors are active.
			final WorldRegion region = _actor.getWorldRegion();
			if ((region == null) || !region.areNeighborsActive())
			{
				return;
			}
			
			// Init cast and attack target.
			setCastTarget(null);
			setAttackTarget(null);
			
			// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
			clientStopMoving(null);
			
			// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
			clientStopAutoAttack();
			
			// Launch the Think Action.
			notifyActionThink();
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isRemovedBy(Intention.ACTIVE))
			{
				setNextAction(null);
			}
		}
	}
	
	/**
	 * Manage the Rest Intention.<br>
	 * <br>
	 * <b><u>Actions</u> : </b>
	 * <ul>
	 * <li>Set the AI Intention to IDLE</li>
	 * </ul>
	 */
	@Override
	public void setIntentionRest()
	{
		stopFollow();
		// Set the AI Intention to IDLE.
		setIntentionIdle();
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isRemovedBy(Intention.REST))
		{
			setNextAction(null);
		}
	}
	
	/**
	 * Manage the Attack Intention : Stop current Attack (if necessary), Start a new Attack and Launch Think Action.<br>
	 * <br>
	 * <b><u>Actions</u> : </b>
	 * <ul>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Set the Intention of this AI to ATTACK</li>
	 * <li>Set or change the AI attack target</li>
	 * <li>Start the actor Auto Attack client side by sending Server->Client packet AutoAttackStart (broadcast)</li>
	 * <li>Launch the Think Action</li>
	 * </ul>
	 * <br>
	 * <b><u>Overridden in</u>:</b>
	 * <ul>
	 * <li>AttackableAI : Calculate attack timeout</li>
	 * </ul>
	 */
	@Override
	public void setIntentionAttack(WorldObject target)
	{
		if (target == null)
		{
			clientActionFailed();
			return;
		}
		
		final Creature attackTarget = target.asCreature();
		try
		{
			if ((attackTarget == null) || (getIntention() == Intention.REST) || _actor.isAllSkillsDisabled() || _actor.isCastingNow() || _actor.isControlBlocked())
			{
				// Cancel action client side by sending Server->Client packet ActionFailed to the Player actor.
				clientActionFailed();
				return;
			}
			
			// Check if the Intention is already ATTACK.
			if (getIntention() == Intention.ATTACK)
			{
				// Check if the AI already targets the Creature.
				if (getAttackTarget() != attackTarget)
				{
					// Set the AI attack target (change target).
					setAttackTarget(attackTarget);
					
					stopFollow();
					
					// Launch the Think Action.
					notifyActionThink();
				}
				else
				{
					// A repeated attack on the same target revalidates a move that ended without arriving.
					//  Sending only ActionFailed leaves the intention waiting on an event that never comes.
					stopFollow();
					notifyActionThink();
				}
			}
			else
			{
				// Set the Intention of this AbstractAI to ATTACK.
				_intention = Intention.ATTACK;
				
				// Set the AI attack target.
				setAttackTarget(attackTarget);
				
				stopFollow();
				
				// Launch the Think Action.
				notifyActionThink();
			}
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isRemovedBy(Intention.ATTACK))
			{
				setNextAction(null);
			}
		}
	}
	
	/**
	 * Manage the Cast Intention : Stop current Attack, Init the AI in order to cast and Launch Think Action.<br>
	 * <br>
	 * <b><u>Actions</u> : </b>
	 * <ul>
	 * <li>Set the AI cast target</li>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Cancel action client side by sending Server->Client packet ActionFailed to the Player actor</li>
	 * <li>Set the AI skill used by INTENTION_CAST</li>
	 * <li>Set the Intention of this AI to CAST</li>
	 * <li>Launch the Think Action</li>
	 * </ul>
	 */
	@Override
	public void setIntentionCast(Skill skill, WorldObject target)
	{
		stopFollow();
		try
		{
			if ((getIntention() == Intention.REST) && skill.isMagic())
			{
				clientActionFailed();
				_actor.setCastingNow(false);
				return;
			}
			
			final int gameTime = GameTimeTaskManager.getInstance().getGameTicks();
			final int bowAttackEndTime = _actor.getBowAttackEndTime();
			final long currentTime = System.nanoTime();
			final long attackEndTime = _actor.getAttackEndTime();
			
			// Wait for the current attack to finish, so its pending hit does not land during the cast.
			long delay = 0;
			if (bowAttackEndTime > gameTime)
			{
				delay = (bowAttackEndTime - gameTime) * GameTimeTaskManager.MILLIS_IN_TICK;
			}
			else if (attackEndTime > currentTime)
			{
				delay = TimeUnit.NANOSECONDS.toMillis(attackEndTime - currentTime);
			}
			
			if (delay > 0)
			{
				ThreadPool.schedule(() ->
				{
					if (_actor.isAttackingNow())
					{
						_actor.abortAttack();
					}
					changeIntentionToCast(skill, target);
				}, delay);
			}
			else
			{
				changeIntentionToCast(skill, target);
			}
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isRemovedBy(Intention.CAST))
			{
				setNextAction(null);
			}
		}
	}
	
	protected void changeIntentionToCast(Skill skill, WorldObject target)
	{
		// Set the AI cast target.
		setCastTarget(target == null ? null : target.asCreature());
		
		// Set the AI skill used by INTENTION_CAST.
		_skill = skill;
		
		// Change the Intention of this AbstractAI to CAST.
		_intention = Intention.CAST;
		
		// Launch the Think Action.
		notifyActionThink();
	}
	
	/**
	 * Manage the Move To Intention : Stop current Attack and Launch a Move to Location Task.<br>
	 * <br>
	 * <b><u>Actions</u> : </b>
	 * <ul>
	 * <li>Stop the actor auto-attack server side AND client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Set the Intention of this AI to MOVE_TO</li>
	 * <li>Move the actor to Location (x,y,z) server side AND client side by sending Server->Client packet MoveToLocation (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void setIntentionMoveTo(ILocational loc)
	{
		stopFollow();
		try
		{
			if ((getIntention() == Intention.REST) || _actor.isAllSkillsDisabled() || _actor.isCastingNow())
			{
				// Cancel action client side by sending Server->Client packet ActionFailed to the Player actor.
				clientActionFailed();
				return;
			}
			
			// Set the Intention of this AbstractAI to MOVE_TO.
			_intention = Intention.MOVE_TO;
			
			// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
			clientStopAutoAttack();
			
			// Abort the attack of the Creature and send Server->Client ActionFailed packet.
			_actor.abortAttack();
			
			// Move the actor to Location (x,y,z) server side AND client side by sending Server->Client packet MoveToLocation (broadcast).
			moveTo(loc.getX(), loc.getY(), loc.getZ());
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isRemovedBy(Intention.MOVE_TO))
			{
				setNextAction(null);
			}
		}
	}
	
	/**
	 * Manage the Follow Intention : Stop current Attack and Launch a Follow Task.<br>
	 * <br>
	 * <b><u>Actions</u> : </b>
	 * <ul>
	 * <li>Stop the actor auto-attack server side AND client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Set the Intention of this AI to FOLLOW</li>
	 * <li>Create and Launch an AI Follow Task to execute every 1s</li>
	 * </ul>
	 */
	@Override
	public void setIntentionFollow(WorldObject target)
	{
		if (target == null)
		{
			clientActionFailed();
			return;
		}
		
		final Creature followTarget = target.asCreature();
		try
		{
			if ((followTarget == null) || (getIntention() == Intention.REST) || _actor.isAllSkillsDisabled() || _actor.isCastingNow() || _actor.isMovementDisabled() || _actor.isDead() || (_actor == followTarget))
			{
				clientActionFailed();
				return;
			}
			
			// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
			clientStopAutoAttack();
			
			// Set the Intention of this AbstractAI to FOLLOW.
			_intention = Intention.FOLLOW;
			
			// Create and Launch an AI Follow Task to execute every 1s.
			startFollow(followTarget);
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isRemovedBy(Intention.FOLLOW))
			{
				setNextAction(null);
			}
		}
	}
	
	/**
	 * Manage the PickUp Intention : Set the pick up target and Launch a Move To Pawn Task (offset=20).<br>
	 * <br>
	 * <b><u>Actions</u> : </b>
	 * <ul>
	 * <li>Set the AI pick up target</li>
	 * <li>Set the Intention of this AI to PICK_UP</li>
	 * <li>Move the actor to Pawn server side AND client side by sending Server->Client packet MoveToPawn (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void setIntentionPickUp(WorldObject object)
	{
		stopFollow();
		try
		{
			if ((object == null) || (getIntention() == Intention.REST) || _actor.isAllSkillsDisabled() || _actor.isCastingNow())
			{
				// Cancel action client side by sending Server->Client packet ActionFailed to the Player actor.
				clientActionFailed();
				return;
			}
			
			// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
			clientStopAutoAttack();
			
			if (object.isItem() && (((Item) object).getItemLocation() != ItemLocation.VOID))
			{
				return;
			}
			
			// Set the Intention of this AbstractAI to PICK_UP.
			_intention = Intention.PICK_UP;
			
			// Set the AI pick up target.
			setTarget(object);
			
			if ((object.getX() == 0) && (object.getY() == 0))
			{
				// LOGGER.warning("Object in coords 0,0 - using a temporary fix");
				object.setXYZ(getActor().getX(), getActor().getY(), getActor().getZ() + 5);
			}
			
			// Move the actor to Pawn server side AND client side by sending Server->Client packet MoveToPawn (broadcast).
			moveToPawn(object, 20);
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isRemovedBy(Intention.PICK_UP))
			{
				setNextAction(null);
			}
		}
	}
	
	/**
	 * Manage the Interact Intention : Set the interact target and Launch a Move To Pawn Task (offset=60).<br>
	 * <br>
	 * <b><u>Actions</u> : </b>
	 * <ul>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Set the AI interact target</li>
	 * <li>Set the Intention of this AI to INTERACT</li>
	 * <li>Move the actor to Pawn server side AND client side by sending Server->Client packet MoveToPawn (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void setIntentionInteract(WorldObject object)
	{
		stopFollow();
		try
		{
			if ((object == null) || (getIntention() == Intention.REST) || _actor.isAllSkillsDisabled() || _actor.isCastingNow())
			{
				// Cancel action client side by sending Server->Client packet ActionFailed to the Player actor.
				clientActionFailed();
				return;
			}
			
			// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
			clientStopAutoAttack();
			
			if (getIntention() == Intention.INTERACT)
			{
				return;
			}
			
			// Set the Intention of this AbstractAI to INTERACT.
			_intention = Intention.INTERACT;
			
			// Set the AI interact target.
			setTarget(object);
			
			// Move the actor to Pawn server side AND client side by sending Server->Client packet MoveToPawn (broadcast).
			moveToPawn(object, 60);
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isRemovedBy(Intention.INTERACT))
			{
				setNextAction(null);
			}
		}
	}
	
	/**
	 * Do nothing.
	 */
	@Override
	public void notifyActionThink()
	{
		// Do nothing.
	}
	
	/**
	 * Do nothing.
	 */
	@Override
	public void notifyActionAggression(WorldObject target, int aggro)
	{
		// Do nothing.
	}
	
	/**
	 * Launch actions corresponding to the Action Stunned.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void notifyActionStunned()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
		_actor.broadcastPacket(new AutoAttackStop(_actor.getObjectId()));
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(_actor))
		{
			AttackStanceTaskManager.getInstance().removeAttackStanceTask(_actor);
		}
		
		// Stop Server AutoAttack also.
		setAutoAttacking(false);
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(null);
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.STUNNED))
		{
			nextAction.doAction();
		}
	}
	
	@Override
	public void notifyActionParalyzed()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
		_actor.broadcastPacket(new AutoAttackStop(_actor.getObjectId()));
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(_actor))
		{
			AttackStanceTaskManager.getInstance().removeAttackStanceTask(_actor);
		}
		
		// Stop Server AutoAttack also.
		setAutoAttacking(false);
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(null);
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.PARALYZED))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action Sleeping.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void notifyActionSleeping()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
		_actor.broadcastPacket(new AutoAttackStop(_actor.getObjectId()));
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(_actor))
		{
			AttackStanceTaskManager.getInstance().removeAttackStanceTask(_actor);
		}
		
		// Stop Server AutoAttack also.
		setAutoAttacking(false);
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(null);
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.SLEEPING))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action Rooted.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void notifyActionRooted()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(null);
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.ROOTED))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action Confused.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void notifyActionConfused()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(null);
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.CONFUSED))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action Muted.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Break a cast and send Server->Client ActionFailed packet and a System Message to the Creature</li>
	 * </ul>
	 */
	@Override
	public void notifyActionMuted()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.MUTED))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Do nothing.
	 */
	@Override
	public void notifyActionEvaded(WorldObject attacker)
	{
		// Do nothing.
	}
	
	/**
	 * Launch actions corresponding to the Action ReadyToAct.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Launch actions corresponding to the Action Think</li>
	 * </ul>
	 */
	@Override
	public void notifyActionReadyToAct()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		if (!_actor.isCastingNow() && !_actor.isCastingSimultaneouslyNow())
		{
			// Launch actions corresponding to the Action Think.
			notifyActionThink();
		}
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.READY_TO_ACT))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Do nothing.
	 */
	@Override
	public void notifyActionUserCmd(Object arg0, Object arg1)
	{
		// Do nothing.
	}
	
	/**
	 * Launch actions corresponding to the Action Arrived.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>If the Intention was MOVE_TO, set the Intention to ACTIVE</li>
	 * <li>Launch actions corresponding to the Action Think</li>
	 * </ul>
	 */
	@Override
	public void notifyActionArrived()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		try
		{
			if (_actor.isCastingNow() || _actor.isCastingSimultaneouslyNow())
			{
				return;
			}
			
			_actor.revalidateZone(true);
			
			if (_actor.moveToNextRoutePoint())
			{
				return;
			}
			
			clientStoppedMoving();
			
			if (_actor.isNpc())
			{
				final Npc npc = _actor.asNpc();
				WalkingManager.getInstance().onArrived(npc); // Walking Manager support.
				
				// Notify to scripts
				if (EventDispatcher.getInstance().hasListener(EventType.ON_NPC_MOVE_FINISHED, npc))
				{
					if (_onNpcMoveFinished == null)
					{
						_onNpcMoveFinished = new OnNpcMoveFinished(npc);
					}
					
					EventDispatcher.getInstance().notifyEventAsync(_onNpcMoveFinished, npc);
				}
			}
			
			// If the Intention was MOVE_TO, set the Intention to ACTIVE.
			if (getIntention() == Intention.MOVE_TO)
			{
				setIntentionActive();
			}
			
			// Launch actions corresponding to the Action Think.
			notifyActionThink();
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isTriggeredBy(Action.ARRIVED))
			{
				setNextAction(null);
				nextAction.doAction();
			}
		}
	}
	
	/**
	 * Launch actions corresponding to the Action ArrivedRevalidate.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Launch actions corresponding to the Action Think</li>
	 * </ul>
	 */
	@Override
	public void notifyActionArrivedRevalidate()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// This is disregarded if the char is not moving any more.
		if (_actor.isMoving())
		{
			// Launch actions corresponding to the Action Think.
			notifyActionThink();
		}
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.ARRIVED_REVALIDATE))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action ArrivedBlocked.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * <li>If the Intention was MOVE_TO, set the Intention to ACTIVE</li>
	 * <li>Launch actions corresponding to the Action Think</li>
	 * </ul>
	 */
	@Override
	public void notifyActionArrivedBlocked(Location location)
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// If the Intention was MOVE_TO, set the Intention to ACTIVE.
		if ((getIntention() == Intention.MOVE_TO) || (getIntention() == Intention.CAST))
		{
			setIntentionActive();
		}
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(location);
		
		// Launch actions corresponding to the Action Think.
		notifyActionThink();
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.ARRIVED_BLOCKED))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action ForgetObject.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>If the object was targeted and the Intention was INTERACT or PICK_UP, set the Intention to ACTIVE</li>
	 * <li>If the object was targeted to attack, stop the auto-attack, cancel target and set the Intention to ACTIVE</li>
	 * <li>If the object was targeted to cast, cancel target and set the Intention to ACTIVE</li>
	 * <li>If the object was targeted to follow, stop the movement, cancel AI Follow Task and set the Intention to ACTIVE</li>
	 * <li>If the targeted object was the actor , cancel AI target, stop AI Follow Task, stop the movement and set the Intention to IDLE</li>
	 * </ul>
	 */
	@Override
	public void notifyActionForgetObject(WorldObject object)
	{
		if (!canHandleAction())
		{
			return;
		}
		
		try
		{
			_actor.removeSeenCreature(object);
			
			// If the object was targeted and the Intention was INTERACT or PICK_UP, set the Intention to ACTIVE.
			if (getTarget() == object)
			{
				setTarget(null);
				
				if ((getIntention() == Intention.INTERACT) || (getIntention() == Intention.PICK_UP))
				{
					setIntentionActive();
				}
			}
			
			// Check if the object was targeted to attack.
			if (getAttackTarget() == object)
			{
				// Cancel attack target
				setAttackTarget(null);
				
				// Set the Intention of this AbstractAI to ACTIVE.
				if ((object == null) || !object.isCreature() || !object.asCreature().isAlikeDead()) // Fixes stop move from cast target decay.
				{
					setIntentionActive();
				}
			}
			
			// Check if the object was targeted to cast.
			if (getCastTarget() == object)
			{
				// Cancel cast target
				setCastTarget(null);
				
				// Set the Intention of this AbstractAI to ACTIVE.
				if ((object == null) || !object.isCreature() || !object.asCreature().isAlikeDead()) // Fixes stop move from cast target decay.
				{
					setIntentionActive();
				}
			}
			
			// Check if the object was targeted to follow.
			if (getFollowTarget() == object)
			{
				// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
				clientStopMoving(null);
				
				// Stop an AI Follow Task.
				stopFollow();
				
				// Set the Intention of this AbstractAI to ACTIVE.
				setIntentionActive();
			}
			
			// Check if the targeted object was the actor.
			if (_actor != object)
			{
				return;
			}
			
			// Cancel AI target
			setTarget(null);
			setAttackTarget(null);
			setCastTarget(null);
			
			// Stop an AI Follow Task.
			stopFollow();
			
			// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
			clientStopMoving(null);
			
			// Set the Intention of this AbstractAI to IDLE.
			_intention = Intention.IDLE;
		}
		finally
		{
			final NextAction nextAction = getNextAction();
			if ((nextAction != null) && nextAction.isTriggeredBy(Action.FORGET_OBJECT))
			{
				nextAction.doAction();
			}
		}
	}
	
	/**
	 * Launch actions corresponding to the Action Cancel.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop an AI Follow Task</li>
	 * <li>Launch actions corresponding to the Action Think</li>
	 * </ul>
	 */
	@Override
	public void notifyActionCancel()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		_actor.abortCast();
		
		// Stop an AI Follow Task.
		stopFollow();
		
		if (!AttackStanceTaskManager.getInstance().hasAttackStanceTask(_actor))
		{
			_actor.broadcastPacket(new AutoAttackStop(_actor.getObjectId()));
		}
		
		// Launch actions corresponding to the Action Think.
		notifyActionThink();
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.CANCEL))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action Dead.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop an AI Follow Task</li>
	 * <li>Kill the actor client side by sending Server->Client packet AutoAttackStop, StopMove/StopRotation, Die (broadcast)</li>
	 * </ul>
	 */
	@Override
	public void notifyActionDeath()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// Stop an AI Tasks.
		stopAITask();
		
		// Kill the actor client side by sending Server->Client packet AutoAttackStop, StopMove/StopRotation, Die (broadcast).
		clientNotifyDead();
		
		if (!_actor.isPlayable() && !_actor.isFakePlayer())
		{
			_actor.setWalking();
		}
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.DEATH))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Launch actions corresponding to the Action Fake Death.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Stop an AI Follow Task</li>
	 * </ul>
	 */
	@Override
	public void notifyActionFakeDeath()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		// Stop an AI Follow Task.
		stopFollow();
		
		// Stop the actor movement and send Server->Client packet StopMove/StopRotation (broadcast).
		clientStopMoving(null);
		
		// Init AI
		_intention = Intention.IDLE;
		setTarget(null);
		setCastTarget(null);
		setAttackTarget(null);
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.FAKE_DEATH))
		{
			nextAction.doAction();
		}
	}
	
	/**
	 * Do nothing.
	 */
	@Override
	public void notifyActionFinishCasting()
	{
		if (!canHandleAction())
		{
			return;
		}
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.FINISH_CASTING))
		{
			nextAction.doAction();
		}
	}
	
	@Override
	public void notifyActionAfraid(WorldObject effector, boolean start)
	{
		if (!canHandleAction() || (effector == null))
		{
			return;
		}
		
		final Creature effectorCreature = effector.asCreature();
		final double radians = Math.toRadians(start && (effectorCreature != null) ? LocationUtil.calculateAngleFrom(effectorCreature, _actor) : LocationUtil.convertHeadingToDegree(_actor.getHeading()));
		final int posX = (int) (_actor.getX() + (FEAR_RANGE * Math.cos(radians)));
		final int posY = (int) (_actor.getY() + (FEAR_RANGE * Math.sin(radians)));
		final int posZ = _actor.getZ();
		if (!_actor.isPet())
		{
			_actor.setRunning();
		}
		
		// If pathfinding enabled the creature will go to the destination or it will go to the nearest obstacle.
		setIntentionMoveTo(GeoEngineConfig.PATHFINDING > 0 ? GeoEngine.getInstance().getValidLocation(_actor.getX(), _actor.getY(), _actor.getZ(), posX, posY, posZ, _actor.getInstanceId()) : new Location(posX, posY, posZ));
		
		final NextAction nextAction = getNextAction();
		if ((nextAction != null) && nextAction.isTriggeredBy(Action.AFRAID))
		{
			nextAction.doAction();
		}
	}
	
	protected boolean maybeMoveToPosition(ILocational worldPosition, int offset)
	{
		if (worldPosition == null)
		{
			// LOGGER.warning("maybeMoveToPosition: worldPosition == NULL!");
			return false;
		}
		
		if (offset < 0)
		{
			return false; // skill radius -1
		}
		
		if (!_actor.isInsideRadius2D(worldPosition, offset + _actor.getTemplate().getCollisionRadius()))
		{
			if (_actor.isMovementDisabled())
			{
				return true;
			}
			
			if (!_actor.isRunning() && !(this instanceof PlayerAI) && !(this instanceof SummonAI))
			{
				_actor.setRunning();
			}
			
			stopFollow();
			
			int x = _actor.getX();
			int y = _actor.getY();
			
			final double dx = worldPosition.getX() - x;
			final double dy = worldPosition.getY() - y;
			double dist = Math.sqrt((dx * dx) + (dy * dy));
			
			final double sin = dy / dist;
			final double cos = dx / dist;
			dist -= offset - 5;
			x += (int) (dist * cos);
			y += (int) (dist * sin);
			moveTo(x, y, worldPosition.getZ());
			return true;
		}
		
		if (isFollowing())
		{
			stopFollow();
		}
		
		return false;
	}
	
	/**
	 * Manage the Move to Pawn action in function of the distance and of the Interact area.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Get the distance between the current position of the Creature and the target (x,y)</li>
	 * <li>If the distance > offset+20, move the actor (by running) to Pawn server side AND client side by sending Server->Client packet MoveToPawn (broadcast)</li>
	 * <li>If the distance <= offset+20, Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * </ul>
	 * <br>
	 * <b><u>Example of use</u>:</b>
	 * <ul>
	 * <li>PLayerAI, SummonAI</li>
	 * </ul>
	 * @param target The targeted WorldObject
	 * @param offsetValue The Interact area radius
	 * @return True if a movement must be done
	 */
	protected boolean maybeMoveToPawn(WorldObject target, int offsetValue)
	{
		// Get the distance between the current position of the Creature and the target (x,y).
		if (target == null)
		{
			// LOGGER.warning("maybeMoveToPawn: target == NULL!");
			return false;
		}
		
		if (offsetValue < 0)
		{
			return false; // skill radius -1
		}
		
		int offsetWithCollision = offsetValue + _actor.getTemplate().getCollisionRadius();
		if (target.isCreature())
		{
			offsetWithCollision += target.asCreature().getTemplate().getCollisionRadius();
		}
		
		if (!_actor.isInsideRadius2D(target, offsetWithCollision))
		{
			// Caller should be Playable and thinkAttack/thinkCast/thinkInteract/thinkPickUp.
			if (isFollowing())
			{
				// Allow larger hit range when the target is moving (check is run only once per second).
				if (!_actor.isInsideRadius2D(target, offsetWithCollision + 100))
				{
					return true;
				}
				
				stopFollow();
				return false;
			}
			
			if (_actor.isMovementDisabled() || (_actor.getMoveSpeed() <= 0))
			{
				// If player is trying attack target but he cannot move to attack target.
				// Change his intention to idle.
				if (_actor.getAI().getIntention() == Intention.ATTACK)
				{
					_actor.getAI().setIntentionIdle();
				}
				
				return true;
			}
			
			// If not running, set the Creature movement type to run and send Server->Client packet ChangeMoveType to all others Player.
			if (!_actor.isRunning() && !(this instanceof PlayerAI) && !(this instanceof SummonAI))
			{
				_actor.setRunning();
			}
			
			stopFollow();
			int offset = offsetValue;
			if (target.isCreature() && !target.isDoor())
			{
				if (target.asCreature().isMoving())
				{
					offset -= 100;
				}
				
				if (offset < 5)
				{
					offset = 5;
				}
				
				startFollow(target.asCreature(), offset);
			}
			else
			{
				// Move the actor to Pawn server side AND client side by sending Server->Client packet MoveToPawn (broadcast).
				moveToPawn(target, offset);
			}
			
			return true;
		}
		
		if (isFollowing())
		{
			stopFollow();
		}
		
		// Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast).
		// clientStopMoving(null);
		return false;
	}
	
	/**
	 * Modify current Intention and actions if the target is lost or dead.<br>
	 * <br>
	 * <b><u>Actions</u> : <i>If the target is lost or dead</i></b>
	 * <ul>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * <li>Set the Intention of this AbstractAI to ACTIVE</li>
	 * </ul>
	 * <br>
	 * <b><u>Example of use</u>:</b>
	 * <ul>
	 * <li>PLayerAI, SummonAI</li>
	 * </ul>
	 * @param target The targeted WorldObject
	 * @return True if the target is lost or dead (false if fakedeath)
	 */
	protected boolean checkTargetLostOrDead(Creature target)
	{
		if ((target == null) || target.isDead())
		{
			setIntentionActive();
			return true;
		}
		
		return false;
	}
	
	/**
	 * Modify current Intention and actions if the target is lost.<br>
	 * <br>
	 * <b><u>Actions</u> : <i>If the target is lost</i></b>
	 * <ul>
	 * <li>Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast)</li>
	 * <li>Stop the actor movement server side AND client side by sending Server->Client packet StopMove/StopRotation (broadcast)</li>
	 * <li>Set the Intention of this AbstractAI to ACTIVE</li>
	 * </ul>
	 * <br>
	 * <b><u>Example of use</u>:</b>
	 * <ul>
	 * <li>PlayerAI, SummonAI</li>
	 * </ul>
	 * @param target The targeted WorldObject
	 * @return True if the target is lost
	 */
	protected boolean checkTargetLost(WorldObject target)
	{
		if (target == null)
		{
			setIntentionActive();
			return true;
		}
		
		if (_actor != null)
		{
			if ((_skill != null) && _skill.hasNegativeEffect() && (_skill.getAffectRange() > 0))
			{
				if (_actor.isPlayer() && _actor.isMoving())
				{
					if (!GeoEngine.getInstance().canMoveToTarget(_actor, target))
					{
						setIntentionActive();
						return true;
					}
				}
				else
				{
					if (!GeoEngine.getInstance().canSeeTarget(_actor, target))
					{
						setIntentionActive();
						return true;
					}
				}
			}
			
			if (_actor.isSummon())
			{
				if (GeoEngine.getInstance().canMoveToTarget(_actor, target))
				{
					return false;
				}
				
				setIntentionActive();
				return true;
			}
		}
		
		return false;
	}
	
	protected class SelfAnalysis
	{
		public boolean isMage = false;
		public boolean isBalanced;
		public boolean isArcher = false;
		public boolean isHealer = false;
		public boolean isFighter = false;
		public boolean cannotMoveOnLand = false;
		public List<Skill> generalSkills = new ArrayList<>();
		public List<Skill> buffSkills = new ArrayList<>();
		public int lastBuffTick = 0;
		public List<Skill> debuffSkills = new ArrayList<>();
		public int lastDebuffTick = 0;
		public List<Skill> cancelSkills = new ArrayList<>();
		public List<Skill> healSkills = new ArrayList<>();
		// public List<L2Skill> trickSkills = new ArrayList<>();
		public List<Skill> generalDisablers = new ArrayList<>();
		public List<Skill> sleepSkills = new ArrayList<>();
		public List<Skill> rootSkills = new ArrayList<>();
		public List<Skill> muteSkills = new ArrayList<>();
		public List<Skill> resurrectSkills = new ArrayList<>();
		public boolean hasHealOrResurrect = false;
		public boolean hasLongRangeSkills = false;
		public boolean hasLongRangeDamageSkills = false;
		public int maxCastRange = 0;
		
		public SelfAnalysis()
		{
		}
		
		public void init()
		{
			switch (((NpcTemplate) _actor.getTemplate()).getAIType())
			{
				case FIGHTER:
				{
					isFighter = true;
					break;
				}
				case MAGE:
				{
					isMage = true;
					break;
				}
				case CORPSE:
				case BALANCED:
				{
					isBalanced = true;
					break;
				}
				case ARCHER:
				{
					isArcher = true;
					break;
				}
				case HEALER:
				{
					isHealer = true;
					break;
				}
				default:
				{
					isFighter = true;
					break;
				}
			}
			
			// Water movement analysis.
			if (_actor.isNpc())
			{
				switch (_actor.getId())
				{
					case 20314: // Great White Shark
					case 20849: // Light Worm
					{
						cannotMoveOnLand = true;
						break;
					}
					default:
					{
						cannotMoveOnLand = false;
						break;
					}
				}
			}
			
			// Skill analysis.
			for (Skill sk : _actor.getAllSkills())
			{
				if (sk.isPassive())
				{
					continue;
				}
				
				final int castRange = sk.getCastRange();
				boolean hasLongRangeDamageSkill = false;
				if (sk.isContinuous())
				{
					if (!sk.isDebuff())
					{
						buffSkills.add(sk);
					}
					else
					{
						debuffSkills.add(sk);
					}
					continue;
				}
				
				if (sk.hasEffectType(EffectType.DISPEL, EffectType.DISPEL_BY_SLOT))
				{
					cancelSkills.add(sk);
				}
				else if (sk.hasEffectType(EffectType.HEAL))
				{
					healSkills.add(sk);
					hasHealOrResurrect = true;
				}
				else if (sk.hasEffectType(EffectType.SLEEP))
				{
					sleepSkills.add(sk);
				}
				else if (sk.hasEffectType(EffectType.STUN, EffectType.PARALYZE))
				{
					// Hardcoding petrification until improvements are made to EffectTemplate... petrification is totally different for AI than paralyze.
					switch (sk.getId())
					{
						case 367:
						case 4111:
						case 4383:
						case 4616:
						case 4578:
						{
							sleepSkills.add(sk);
							break;
						}
						default:
						{
							generalDisablers.add(sk);
							break;
						}
					}
				}
				else if (sk.hasEffectType(EffectType.ROOT))
				{
					rootSkills.add(sk);
				}
				else if (sk.hasEffectType(EffectType.FEAR))
				{
					debuffSkills.add(sk);
				}
				else if (sk.hasEffectType(EffectType.MUTE))
				{
					muteSkills.add(sk);
				}
				else if (sk.hasEffectType(EffectType.RESURRECTION))
				{
					resurrectSkills.add(sk);
					hasHealOrResurrect = true;
				}
				else
				{
					generalSkills.add(sk);
					hasLongRangeDamageSkill = true;
				}
				
				if (castRange > 150)
				{
					hasLongRangeSkills = true;
					if (hasLongRangeDamageSkill)
					{
						hasLongRangeDamageSkills = true;
					}
				}
				
				if (castRange > maxCastRange)
				{
					maxCastRange = castRange;
				}
			}
			
			// Because of missing skills, some mages/balanced cannot play like mages.
			if (!hasLongRangeDamageSkills && isMage)
			{
				isBalanced = true;
				isMage = false;
				isFighter = false;
			}
			
			if (!hasLongRangeSkills && (isMage || isBalanced))
			{
				isBalanced = false;
				isMage = false;
				isFighter = true;
			}
			
			if (generalSkills.isEmpty() && isMage)
			{
				isBalanced = true;
				isMage = false;
			}
		}
	}
	
	protected class TargetAnalysis
	{
		public Creature creature;
		public boolean isMage;
		public boolean isBalanced;
		public boolean isArcher;
		public boolean isFighter;
		public boolean isCanceled;
		public boolean isSlower;
		public boolean isMagicResistant;
		
		public TargetAnalysis()
		{
		}
		
		public void update(Creature target)
		{
			// Update status once in 4 seconds.
			if ((target == creature) && (Rnd.get(100) > 25))
			{
				return;
			}
			
			creature = target;
			if (target == null)
			{
				return;
			}
			
			isMage = false;
			isBalanced = false;
			isArcher = false;
			isFighter = false;
			isCanceled = false;
			if (target.getMAtk(null, null) > (1.5 * target.getPAtk(null)))
			{
				isMage = true;
			}
			else if (((target.getPAtk(null) * 0.8) < target.getMAtk(null, null)) || ((target.getMAtk(null, null) * 0.8) > target.getPAtk(null)))
			{
				isBalanced = true;
			}
			else
			{
				final Weapon weapon = target.getActiveWeaponItem();
				if ((weapon != null) && (weapon.getItemType() == WeaponType.BOW))
				{
					isArcher = true;
				}
				else
				{
					isFighter = true;
				}
			}
			
			isSlower = target.getRunSpeed() < (_actor.getRunSpeed() - 3);
			isMagicResistant = (target.getMDef(null, null) * 1.2) > _actor.getMAtk(null, null);
			if (target.getBuffCount() < 4)
			{
				isCanceled = true;
			}
		}
	}
	
	public boolean canAura(Skill sk)
	{
		if ((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA) || (sk.getTargetType() == TargetType.AURA_CORPSE_MOB))
		{
			if (World.getFirstVisibleObjectInRange(_actor, Creature.class, sk.getAffectRange(), target -> target == getAttackTarget()) != null)
			{
				return true;
			}
		}
		
		return false;
	}
	
	public boolean canAOE(Skill sk)
	{
		if (sk.hasEffectType(EffectType.DISPEL, EffectType.DISPEL_BY_SLOT))
		{
			if ((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA) || (sk.getTargetType() == TargetType.AURA_CORPSE_MOB))
			{
				final boolean cancast = World.getFirstVisibleObjectInRange(_actor, Creature.class, sk.getAffectRange(), target -> GeoEngine.getInstance().canSeeTarget(_actor, target) && !(target.isAttackable() && !_actor.asNpc().isChaos()) && target.isAffectedBySkill(sk.getId())) == null;
				
				if (cancast)
				{
					return true;
				}
			}
			else if ((sk.getTargetType() == TargetType.AREA) || (sk.getTargetType() == TargetType.BEHIND_AREA) || (sk.getTargetType() == TargetType.FRONT_AREA))
			{
				boolean cancast = true;
				for (Creature target : World.getVisibleObjectsInRange(getAttackTarget(), Creature.class, sk.getAffectRange()))
				{
					if (!GeoEngine.getInstance().canSeeTarget(_actor, target) || (target == null) || (target.isAttackable() && !_actor.asNpc().isChaos()))
					{
						continue;
					}
					
					if (!target.getEffectList().isEmpty())
					{
						cancast = true;
					}
				}
				
				if (cancast)
				{
					return true;
				}
			}
		}
		else if ((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA) || (sk.getTargetType() == TargetType.AURA_CORPSE_MOB))
		{
			final boolean cancast = World.getFirstVisibleObjectInRange(_actor, Creature.class, sk.getAffectRange(), target -> GeoEngine.getInstance().canSeeTarget(_actor, target) && !(target.isAttackable() && !_actor.asNpc().isChaos()) && !target.getEffectList().isEmpty()) != null;
			
			if (cancast)
			{
				return true;
			}
		}
		else if ((sk.getTargetType() == TargetType.AREA) || (sk.getTargetType() == TargetType.BEHIND_AREA) || (sk.getTargetType() == TargetType.FRONT_AREA))
		{
			final boolean cancast = World.getFirstVisibleObjectInRange(getAttackTarget(), Creature.class, sk.getAffectRange(), target -> GeoEngine.getInstance().canSeeTarget(_actor, target) && !(target.isAttackable() && !_actor.asNpc().isChaos()) && target.isAffectedBySkill(sk.getId())) == null;
			
			if (cancast)
			{
				return true;
			}
		}
		
		return false;
	}
	
	public boolean canParty(Skill sk)
	{
		if (isParty(sk))
		{
			int count = 0;
			int ccount = 0;
			for (Attackable target : World.getVisibleObjectsInRange(_actor, Attackable.class, sk.getAffectRange()))
			{
				if (!GeoEngine.getInstance().canSeeTarget(_actor, target))
				{
					continue;
				}
				
				if (target.isInMyClan(_actor.asNpc()))
				{
					count++;
					if (target.isAffectedBySkill(sk.getId()))
					{
						ccount++;
					}
				}
			}
			
			if (ccount < count)
			{
				return true;
			}
		}
		
		return false;
	}
	
	public boolean isParty(Skill sk)
	{
		return sk.getTargetType() == TargetType.PARTY;
	}
}

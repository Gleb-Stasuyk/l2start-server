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
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.player.Duel;
import org.l2jmobius.gameserver.entity.actor.instance.StaticObject;
import org.l2jmobius.gameserver.interfaces.ILocational;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

public class PlayerAI extends PlayableAI
{
	private boolean _thinking; // To prevent recursive thinking.
	
	// Saved intention to replay after a CAST completes (typed fields — no IntentionCommand).
	private Intention _savedIntention = null;
	private WorldObject _savedAttackTarget = null;
	private ILocational _savedMoveTo = null;
	private WorldObject _savedFollowTarget = null;
	private WorldObject _savedPickUpTarget = null;
	private WorldObject _savedInteractTarget = null;
	private Skill _savedCastSkill = null;
	private WorldObject _savedCastTarget = null;
	
	public PlayerAI(Player player)
	{
		super(player);
	}
	
	@Override
	public Intention getNextIntention()
	{
		return _savedIntention;
	}
	
	private void clearSavedIntention()
	{
		_savedIntention = null;
		_savedAttackTarget = null;
		_savedMoveTo = null;
		_savedFollowTarget = null;
		_savedPickUpTarget = null;
		_savedInteractTarget = null;
		_savedCastSkill = null;
		_savedCastTarget = null;
	}
	
	/**
	 * Saves the current intention so it can be replayed once the upcoming CAST resolves.
	 */
	private void saveCurrentIntentionForCast()
	{
		_savedIntention = _intention;
		_savedAttackTarget = getAttackTarget();
		_savedCastSkill = _skill;
		_savedCastTarget = getCastTarget();
		// Other typed targets are restored on replay via the same getters when possible.
		_savedMoveTo = null;
		_savedFollowTarget = getFollowTarget();
		_savedPickUpTarget = null;
		_savedInteractTarget = null;
	}
	
	private void replaySavedIntention()
	{
		if (_savedIntention == null)
		{
			return;
		}
		
		final Intention intention = _savedIntention;
		final WorldObject attackTarget = _savedAttackTarget;
		final ILocational moveToLoc = _savedMoveTo;
		final WorldObject followTarget = _savedFollowTarget;
		final WorldObject pickUpTarget = _savedPickUpTarget;
		final WorldObject interactTarget = _savedInteractTarget;
		final Skill castSkill = _savedCastSkill;
		final WorldObject castTarget = _savedCastTarget;
		clearSavedIntention();
		
		switch (intention)
		{
			case IDLE:
			{
				setIntentionIdle();
				break;
			}
			case ACTIVE:
			{
				setIntentionActive();
				break;
			}
			case REST:
			{
				setIntentionRest();
				break;
			}
			case ATTACK:
			{
				setIntentionAttack(attackTarget);
				break;
			}
			case CAST:
			{
				if (castSkill != null)
				{
					setIntentionCast(castSkill, castTarget);
				}
				break;
			}
			case MOVE_TO:
			{
				if (moveToLoc != null)
				{
					setIntentionMoveTo(moveToLoc);
				}
				break;
			}
			case FOLLOW:
			{
				setIntentionFollow(followTarget);
				break;
			}
			case PICK_UP:
			{
				setIntentionPickUp(pickUpTarget);
				break;
			}
			case INTERACT:
			{
				setIntentionInteract(interactTarget);
				break;
			}
		}
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
		// Replay any saved intention from before a CAST.
		if (_savedIntention != null)
		{
			replaySavedIntention();
		}
		
		super.notifyActionReadyToAct();
	}
	
	@Override
	public void notifyActionForgetObject(WorldObject object)
	{
		if ((object != null) && object.isPlayer())
		{
			getActor().getKnownRelations().remove(object.getObjectId());
		}
		
		super.notifyActionForgetObject(object);
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
		clearSavedIntention();
		super.notifyActionCancel();
	}
	
	/**
	 * Finalize the casting of a skill. This method overrides CreatureAI method.<br>
	 * <b>What it does:</b><br>
	 * Check if actual intention is set to CAST and, if so, retrieves latest intention before the actual CAST and set it as the current intention for the player.
	 */
	@Override
	public void notifyActionFinishCasting()
	{
		if (getIntention() == Intention.CAST)
		{
			// Run interrupted or next intention.
			if (_savedIntention != null)
			{
				if (_savedIntention != Intention.CAST)
				{
					replaySavedIntention();
				}
				else
				{
					clearSavedIntention();
					setIntentionIdle();
				}
			}
			else
			{
				// Set intention to idle if skill doesn't change intention.
				setIntentionIdle();
			}
		}
		
		super.notifyActionFinishCasting();
	}
	
	@Override
	public void setIntentionRest()
	{
		if (getIntention() == Intention.REST)
		{
			return;
		}
		
		clearSavedIntention();
		_intention = Intention.REST;
		setTarget(null);
		if (getAttackTarget() != null)
		{
			setAttackTarget(null);
		}
		
		clientStopMoving(null);
	}
	
	@Override
	public void setIntentionActive()
	{
		setIntentionIdle();
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
		if (getIntention() == Intention.REST)
		{
			// Cancel action client side by sending Server->Client packet ActionFailed to the Player actor.
			clientActionFailed();
			return;
		}
		
		final Player player = _actor.asPlayer();
		if (player.getDuelState() == Duel.DUELSTATE_DEAD)
		{
			clientActionFailed();
			player.sendPacket(new SystemMessage(SystemMessageId.YOU_CANNOT_MOVE_WHILE_FROZEN_PLEASE_WAIT));
			return;
		}
		
		if (_actor.isAllSkillsDisabled() || _actor.isCastingNow() || _actor.isAttackingNow())
		{
			clientActionFailed();
			// Save the move-to as the next intention to replay once ready.
			_savedIntention = Intention.MOVE_TO;
			_savedMoveTo = loc;
			return;
		}
		
		stopFollow();
		
		// Set the Intention of this AbstractAI to MOVE_TO.
		clearSavedIntention();
		_intention = Intention.MOVE_TO;
		
		// Stop the actor auto-attack client side by sending Server->Client packet AutoAttackStop (broadcast).
		clientStopAutoAttack();
		
		// Abort the attack of the Creature and send Server->Client ActionFailed packet.
		_actor.abortAttack();
		
		// Move the actor to Location (x,y,z) server side AND client side by sending Server->Client packet MoveToLocation (broadcast).
		moveTo(loc.getX(), loc.getY(), loc.getZ());
	}
	
	@Override
	public void setIntentionCast(Skill skill, WorldObject target)
	{
		// Forget next if it's not cast or it's cast and skill is toggle.
		if ((skill == null) || !skill.isToggle())
		{
			// New non-toggle cast: clear any stale saved intention and remember the current one if it differs.
			if (_intention != Intention.CAST)
			{
				saveCurrentIntentionForCast();
			}
		}
		
		super.setIntentionCast(skill, target);
	}
	
	@Override
	protected void clientNotifyDead()
	{
		_clientMovingToPawnOffset = 0;
		super.clientNotifyDead();
	}
	
	private void thinkAttack()
	{
		final Creature target = getAttackTarget();
		if (target == null)
		{
			return;
		}
		
		if (checkTargetLostOrDead(target))
		{
			// Notify the target
			setAttackTarget(null);
			return;
		}
		
		if (maybeMoveToPawn(target, _actor.getPhysicalAttackRange()))
		{
			return;
		}
		
		clientStopMoving(null);
		_actor.doAttack(target);
	}
	
	private void thinkCast()
	{
		final Creature target = getCastTarget();
		if ((_skill.getTargetType() == TargetType.GROUND) && _actor.isPlayer())
		{
			if (maybeMoveToPosition(_actor.asPlayer().getCurrentSkillWorldPosition(), _actor.getMagicalAttackRange(_skill)))
			{
				_actor.setCastingNow(false);
				return;
			}
		}
		else
		{
			if (checkTargetLost(target))
			{
				if (_skill.hasNegativeEffect() && (getAttackTarget() != null))
				{
					// Notify the target
					setCastTarget(null);
				}
				
				_actor.setCastingNow(false);
				return;
			}
			
			if ((target != null) && maybeMoveToPawn(target, _actor.getMagicalAttackRange(_skill)))
			{
				_actor.setCastingNow(false);
				return;
			}
		}
		
		if ((_skill.getHitTime() > 50) && !_skill.isSimultaneousCast())
		{
			clientStopMoving(null);
		}
		
		// Check if target has changed.
		final WorldObject currentTarget = _actor.getTarget();
		if ((currentTarget != target) && (currentTarget != null) && (target != null))
		{
			_actor.setTarget(target);
			_actor.doCast(_skill);
			_actor.setTarget(currentTarget);
			return;
		}
		
		_actor.doCast(_skill);
	}
	
	private void thinkPickUp()
	{
		if (_actor.isAllSkillsDisabled() || _actor.isCastingNow())
		{
			return;
		}
		
		final WorldObject target = getTarget();
		if (checkTargetLost(target) || maybeMoveToPawn(target, 36))
		{
			return;
		}
		
		setIntentionIdle();
		_actor.asPlayer().doPickupItem(target);
	}
	
	private void thinkInteract()
	{
		if (_actor.isAllSkillsDisabled() || _actor.isCastingNow())
		{
			return;
		}
		
		final WorldObject target = getTarget();
		if (checkTargetLost(target) || maybeMoveToPawn(target, 36))
		{
			return;
		}
		
		if (!(target instanceof StaticObject))
		{
			_actor.asPlayer().doInteract(target.asCreature());
		}
		
		setIntentionIdle();
	}
	
	@Override
	public void notifyActionThink()
	{
		if (_thinking && (getIntention() != Intention.CAST))
		{
			return;
		}
		
		_thinking = true;
		try
		{
			if (getIntention() == Intention.ATTACK)
			{
				thinkAttack();
			}
			else if (getIntention() == Intention.CAST)
			{
				thinkCast();
			}
			else if (getIntention() == Intention.PICK_UP)
			{
				thinkPickUp();
			}
			else if (getIntention() == Intention.INTERACT)
			{
				thinkInteract();
			}
		}
		finally
		{
			_thinking = false;
		}
	}
}

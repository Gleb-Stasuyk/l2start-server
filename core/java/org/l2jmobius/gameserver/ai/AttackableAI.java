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
import java.util.Set;
import java.util.concurrent.Future;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.NpcConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.FactionSystemConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.WorldRegion;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.npc.AISkillScope;
import org.l2jmobius.gameserver.entity.actor.enums.npc.AIType;
import org.l2jmobius.gameserver.entity.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.entity.actor.instance.FestivalMonster;
import org.l2jmobius.gameserver.entity.actor.instance.FriendlyMob;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.actor.instance.Guard;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.instance.RaidBoss;
import org.l2jmobius.gameserver.entity.actor.instance.RiftInvader;
import org.l2jmobius.gameserver.entity.actor.instance.StaticObject;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.entity.zone.type.BossZone;
import org.l2jmobius.gameserver.entity.zone.type.NpcSpawnTerritory;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.interfaces.ILocational;
import org.l2jmobius.gameserver.managers.DimensionalRiftManager;
import org.l2jmobius.gameserver.managers.ItemsOnGroundManager;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.events.EventDispatcher;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.attackable.OnAttackableFactionCall;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.attackable.OnAttackableHate;
import org.l2jmobius.gameserver.mechanics.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.taskmanagers.AttackableThinkTaskManager;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;
import org.l2jmobius.gameserver.util.LocationUtil;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * This class manages AI of Attackable.
 */
public class AttackableAI extends CreatureAI
{
	protected static final int FEAR_TICKS = 5;
	private static final int RANDOM_WALK_RATE = 30; // confirmed
	private static final int MAX_ATTACK_TIMEOUT = 1200; // int ticks, i.e. 2min
	private static final int WANDER_ATTEMPTS = 3;
	private static final int SKILL_PROBABILITY_SCALE = 10000;
	private static final int MAX_PARAMETERIZED_SKILL_SLOTS = 6;
	private static final int MINION_LEASH_INTERVAL = 120000; // Retail raid minion territory check runs every 120 seconds.
	private static final int MINION_LEASH_OFFSET = 100; // Retail raid minion is placed on a random point around its master.
	
	/** The delay after which the attacked is stopped. */
	private int _attackTimeout;
	/** The Attackable aggro counter. */
	private int _globalAggro;
	/** The flag used to indicate that a thinking action is in progress, to prevent recursive thinking. */
	private boolean _thinking;
	private int _chaosTime = 0;
	/** The time of the next raid minion territory check. */
	private long _minionLeashTime;
	
	// Fear parameters.
	private int _fearTime;
	private Future<?> _fearTask = null;
	
	public AttackableAI(Attackable attackable)
	{
		super(attackable);
		_attackTimeout = Integer.MAX_VALUE;
		rollGlobalAggro();
	}
	
	/**
	 * @param target The targeted WorldObject
	 * @return {@code true} if target can be auto attacked due aggression.
	 */
	private boolean isAggressiveTowards(Creature target)
	{
		if ((target == null) || (getActiveChar() == null))
		{
			return false;
		}
		
		// Check if the target isn't invulnerable.
		if (target.isInvul())
		{
			// However EffectInvincible requires to check GMs specially.
			if (target.isPlayer() && target.isGM())
			{
				return false;
			}
			
			if (target.isSummon() && target.asSummon().getOwner().isGM())
			{
				return false;
			}
		}
		
		// Check if the target isn't a Folk or a Door.
		if (target.isDoor())
		{
			return false;
		}
		
		// Check if the target isn't dead, is in the Aggro range and is at the same height.
		final Attackable me = getActiveChar();
		if (target.isAlikeDead() || (target.isPlayable() && !me.isInsideRadius3D(target, me.getAggroRange())))
		{
			return false;
		}
		
		// Check if the target is a Playable and if the AI isn't a Raid Boss, can see Silent Moving players and the target isn't in silent move mode.
		if (target.isPlayable() && !(me.isRaid()) && !(me.canSeeThroughSilentMove()) && target.asPlayable().isSilentMovingAffected())
		{
			return false;
		}
		
		// Gets the player if there is any.
		final Player player = target.asPlayer();
		if (player != null)
		{
			// Don't take the aggro if the GM has the access level below or equal to GM_DONT_TAKE_AGGRO.
			if (!player.getAccessLevel().canTakeAggro())
			{
				return false;
			}
			
			// Check if the target is within the grace period for JUST getting up from fake death.
			if (player.isRecentFakeDeath())
			{
				return false;
			}
			
			if (FactionSystemConfig.FACTION_SYSTEM_ENABLED && FactionSystemConfig.FACTION_GUARDS_ENABLED && ((player.isGood() && _actor.asNpc().getTemplate().isClan(FactionSystemConfig.FACTION_EVIL_TEAM_NAME)) || (player.isEvil() && _actor.asNpc().getTemplate().isClan(FactionSystemConfig.FACTION_GOOD_TEAM_NAME))))
			{
				return true;
			}
			
			// Dimensional Rift check.
			if ((me instanceof RiftInvader) && player.isInParty())
			{
				final Party party = player.getParty();
				if (party.isInDimensionalRift())
				{
					final byte riftType = party.getDimensionalRift().getType();
					final byte riftRoom = party.getDimensionalRift().getCurrentRoom();
					if (!DimensionalRiftManager.getInstance().getRoom(riftType, riftRoom).checkIfInZone(me.getX(), me.getY(), me.getZ()))
					{
						return false;
					}
				}
			}
		}
		
		// Check if the actor is a GuardInstance.
		if (me instanceof Guard)
		{
			// Check if the Player target has karma (=PK).
			if ((player != null) && (player.getKarma() > 0))
			{
				return GeoEngine.getInstance().canSeeTarget(me, player); // Los Check
			}
			
			// Check if the Monster target is aggressive.
			if (target.isMonster() && NpcConfig.GUARD_ATTACK_AGGRO_MOB)
			{
				return (target.asMonster().isAggressive() && GeoEngine.getInstance().canSeeTarget(me, target));
			}
			
			return false;
		}
		else if (me instanceof FriendlyMob)
		{
			// Check if the target isn't another Npc.
			if (target instanceof Npc)
			{
				return false;
			}
			
			// Check if the Player target has karma (=PK).
			if (target.isPlayer() && (target.asPlayer().getKarma() > 0))
			{
				return GeoEngine.getInstance().canSeeTarget(me, target); // Los Check
			}
			
			return false;
		}
		else
		{
			if (target.isAttackable())
			{
				if (!target.isAutoAttackable(me))
				{
					return false;
				}
				
				if (me.isChaos() && me.isInsideRadius2D(target, me.getAggroRange()))
				{
					if (target.asAttackable().isInMyClan(me))
					{
						return false;
					}
					
					// Los Check
					return GeoEngine.getInstance().canSeeTarget(me, target);
				}
			}
			
			if (target.isAttackable() || (target instanceof Npc))
			{
				return false;
			}
			
			// depending on config, do not allow mobs to attack _new_ players in peacezones,
			// unless they are already following those players from outside the peacezone.
			if (!NpcConfig.ALT_MOB_AGRO_IN_PEACEZONE && target.isInsideZone(ZoneId.PEACE) && target.isInsideZone(ZoneId.NO_PVP))
			{
				return false;
			}
			
			if (me.isChampion() && ChampionMonstersConfig.CHAMPION_PASSIVE)
			{
				return false;
			}
			
			// Check if the actor is Aggressive.
			return me.isAggressive() && GeoEngine.getInstance().canSeeTarget(me, target);
		}
	}
	
	public void startAITask()
	{
		AttackableThinkTaskManager.getInstance().add(getActiveChar());
	}
	
	@Override
	public void stopAITask()
	{
		AttackableThinkTaskManager.getInstance().remove(getActiveChar());
		super.stopAITask();
	}
	
	/**
	 * Helper: apply IDLE/ACTIVE conversion based on whether nearby players or spawn distance warrant ACTIVE mode.<br>
	 * When the result is {@code false} (i.e. truly IDLE), the AI task is stopped and the actor detached.
	 * @return {@code true} if the intention should be promoted to ACTIVE; {@code false} otherwise.
	 */
	private boolean shouldPromoteIdleToActive()
	{
		final Attackable npc = getActiveChar();
		if (npc.isAlikeDead())
		{
			return false;
		}
		
		if ((World.getFirstVisibleObject(npc, Player.class) != null))
		{
			return true;
		}
		
		if ((npc.getSpawn() != null) && ((npc.getSpawn().getSpawnTerritory() != null) ? !npc.getSpawn().getSpawnTerritory().isInsideZone(npc.getX(), npc.getY()) : !npc.isInsideRadius3D(npc.getSpawn().getLocation(), NpcConfig.MAX_DRIFT_RANGE + NpcConfig.MAX_DRIFT_RANGE)))
		{
			return true;
		}
		
		return false;
	}
	
	@Override
	public synchronized void setIntentionIdle()
	{
		if (shouldPromoteIdleToActive())
		{
			setIntentionActive();
			return;
		}
		
		super.setIntentionIdle();
		
		// Stop AI task and detach AI from NPC.
		stopAITask();
		
		// Cancel the AI
		_actor.detachAI();
	}
	
	@Override
	public synchronized void setIntentionActive()
	{
		// Cancel attack timeout.
		_attackTimeout = Integer.MAX_VALUE;
		super.setIntentionActive();
		startAITask();
	}
	
	@Override
	public synchronized void setIntentionRest()
	{
		super.setIntentionRest();
		startAITask();
	}
	
	/**
	 * Manage the Attack Intention : Stop current Attack (if necessary), Calculate attack timeout, Start a new Attack and Launch Think Action.
	 * @param target The Creature to attack
	 */
	@Override
	public synchronized void setIntentionAttack(WorldObject target)
	{
		// Calculate the attack timeout.
		_attackTimeout = MAX_ATTACK_TIMEOUT + GameTimeTaskManager.getInstance().getGameTicks();
		
		// Manage the Attack Intention : Stop current Attack (if necessary), Start a new Attack and Launch Think Action.
		super.setIntentionAttack(target);
		startAITask();
	}
	
	@Override
	public void notifyActionReadyToAct()
	{
		// A landed attack keeps the fight alive, the timeout is only for targets that cannot be reached.
		if (getIntention() == Intention.ATTACK)
		{
			_attackTimeout = MAX_ATTACK_TIMEOUT + GameTimeTaskManager.getInstance().getGameTicks();
		}
		
		super.notifyActionReadyToAct();
	}
	
	@Override
	public void notifyActionFinishCasting()
	{
		// A finished cast keeps the fight alive, the timeout is only for targets that cannot be reached.
		if (getIntention() == Intention.ATTACK)
		{
			_attackTimeout = MAX_ATTACK_TIMEOUT + GameTimeTaskManager.getInstance().getGameTicks();
		}
		
		super.notifyActionFinishCasting();
	}
	
	@Override
	public synchronized void setIntentionCast(Skill skill, WorldObject target)
	{
		if (target != null)
		{
			setTarget(target);
		}
		super.setIntentionCast(skill, target);
		startAITask();
	}
	
	@Override
	public synchronized void setIntentionMoveTo(ILocational destination)
	{
		super.setIntentionMoveTo(destination);
		startAITask();
	}
	
	@Override
	public synchronized void setIntentionFollow(WorldObject target)
	{
		super.setIntentionFollow(target);
		startAITask();
	}
	
	@Override
	public synchronized void setIntentionPickUp(WorldObject item)
	{
		super.setIntentionPickUp(item);
		startAITask();
	}
	
	@Override
	public synchronized void setIntentionInteract(WorldObject object)
	{
		super.setIntentionInteract(object);
		startAITask();
	}
	
	@Override
	public void notifyActionAfraid(WorldObject effector, boolean start)
	{
		if ((_fearTime > 0) && (_fearTask == null))
		{
			_fearTask = ThreadPool.scheduleAtFixedRate(() ->
			{
				if (effector != null)
				{
					final int fearTimeLeft = getFearTime() - FEAR_TICKS;
					setFearTime(fearTimeLeft);
					notifyActionAfraid(effector, start);
				}
			}, 0, FEAR_TICKS * 1000);
			
			_actor.startAbnormalVisualEffect(true, AbnormalVisualEffect.TURN_FLEE);
		}
		else
		{
			super.notifyActionAfraid(effector, start);
			
			if ((_actor.isDead() || (_fearTime <= 0)) && (_fearTask != null))
			{
				_fearTask.cancel(true);
				_fearTask = null;
				_actor.stopAbnormalVisualEffect(true, AbnormalVisualEffect.TURN_FLEE);
				setIntentionIdle();
			}
		}
	}
	
	protected void thinkCast()
	{
		if (checkTargetLost(getCastTarget()))
		{
			setCastTarget(null);
			return;
		}
		
		if (maybeMoveToPawn(getCastTarget(), _actor.getMagicalAttackRange(_skill)))
		{
			return;
		}
		
		clientStopMoving(null);
		setIntentionActive();
		_actor.doCast(_skill);
	}
	
	/**
	 * Manage AI standard thinks of a Attackable (called by onActionThink). <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Update every 1s the _globalAggro counter to come close to 0</li>
	 * <li>If the actor is Aggressive and can attack, add all autoAttackable Creature in its Aggro Range to its _aggroList, chose a target and order to attack it</li>
	 * <li>If the actor is a GuardInstance that can't attack, order to it to return to its home location</li>
	 * <li>If the actor is a Monster that can't attack, order to it to random walk (1/100)</li>
	 * </ul>
	 */
	protected void thinkActive()
	{
		// Check if region and its neighbors are active.
		final WorldRegion region = _actor.getWorldRegion();
		if ((region == null) || !region.areNeighborsActive())
		{
			return;
		}
		
		final Attackable npc = getActiveChar();
		
		// Update every 1s the _globalAggro counter to come close to 0.
		if (_globalAggro != 0)
		{
			if (_globalAggro < 0)
			{
				_globalAggro++;
			}
			else
			{
				_globalAggro--;
			}
		}
		
		// Add all autoAttackable Creature in Attackable Aggro Range to its _aggroList with 0 damage and 1 hate.
		// An Attackable isn't aggressive during 10s after its spawn because _globalAggro is set to -10.
		if (_globalAggro >= 0)
		{
			World.forEachVisibleObject(npc, Creature.class, target ->
			{
				if (target instanceof StaticObject)
				{
					return;
				}
				
				if (npc.isFakePlayer() && npc.isAggressive())
				{
					final List<Item> droppedItems = npc.getFakePlayerDrops();
					if (droppedItems.isEmpty())
					{
						final Creature nearestTarget = World.getNearestVisibleObjectInRange(npc, Creature.class, npc.getAggroRange(), t -> (t != _actor) && !t.isDead() && ((FakePlayersConfig.FAKE_PLAYER_AGGRO_FPC && t.isFakePlayer()) || (FakePlayersConfig.FAKE_PLAYER_AGGRO_MONSTERS && t.isMonster() && !t.isFakePlayer()) || (FakePlayersConfig.FAKE_PLAYER_AGGRO_PLAYERS && t.isPlayer())) && (npc.getHating(t) == 0));
						if (nearestTarget != null)
						{
							npc.addDamageHate(nearestTarget, 0, 1);
						}
					}
					else if (!npc.isInCombat()) // Must pickup items.
					{
						final int itemIndex = npc.getFakePlayerDrops().size() - 1; // Last item dropped - can also use 0 for first item dropped.
						final Item droppedItem = npc.getFakePlayerDrops().get(itemIndex);
						if ((droppedItem != null) && droppedItem.isSpawned())
						{
							if (npc.calculateDistance2D(droppedItem) > 50)
							{
								moveTo(droppedItem);
							}
							else
							{
								npc.getFakePlayerDrops().remove(itemIndex);
								droppedItem.pickupMe(npc);
								if (GeneralConfig.SAVE_DROPPED_ITEM)
								{
									ItemsOnGroundManager.getInstance().removeObject(droppedItem);
								}
								
								if (droppedItem.getTemplate().hasExImmediateEffect())
								{
									for (SkillHolder skillHolder : droppedItem.getTemplate().getSkills())
									{
										npc.doSimultaneousCast(skillHolder.getSkill());
									}
									
									npc.broadcastInfo(); // TODO: Check if this is necessary.
								}
							}
						}
						else
						{
							npc.getFakePlayerDrops().remove(itemIndex);
						}
						
						npc.setRunning();
					}
					return;
				}
				
				/*
				 * Check to see if this is a festival mob spawn. If it is, then check to see if the aggro trigger is a festival participant...if so, move to attack it.
				 */
				if ((npc instanceof FestivalMonster) && target.isPlayer())
				{
					final Player targetPlayer = target.asPlayer();
					if (!(targetPlayer.isFestivalParticipant()))
					{
						return;
					}
				}
				
				// For each Creature check if the target is autoattackable.
				if (isAggressiveTowards(target)) // check aggression
				{
					if (target.isFakePlayer())
					{
						if (!npc.isFakePlayer() || (npc.isFakePlayer() && FakePlayersConfig.FAKE_PLAYER_AGGRO_FPC))
						{
							final long hating = npc.getHating(target);
							if (hating == 0)
							{
								npc.addDamageHate(target, 0, 0);
							}
						}
						return;
					}
					
					if (target.isPlayable() && EventDispatcher.getInstance().hasListener(EventType.ON_NPC_HATE, getActiveChar()))
					{
						final TerminateReturn term = EventDispatcher.getInstance().notifyEvent(new OnAttackableHate(getActiveChar(), target.asPlayer(), target.isSummon()), getActiveChar(), TerminateReturn.class);
						if ((term != null) && term.terminate())
						{
							return;
						}
					}
					
					if (npc.getHating(target) == 0)
					{
						npc.addDamageHate(target, 0, 0);
					}
				}
			});
			
			// Chose a target from its aggroList.
			final Creature hated = npc.isConfused() ? getAttackTarget() : npc.getMostHated();
			
			// Order to the Attackable to attack the target.
			if ((hated != null) && !npc.isCoreAIDisabled())
			{
				// Get the hate level of the Attackable against this Creature target contained in _aggroList.
				final long aggro = npc.getHating(hated);
				if ((aggro + _globalAggro) > 0)
				{
					// Set the Creature movement type to run and send Server->Client packet ChangeMoveType to all others Player.
					if (!npc.isRunning())
					{
						npc.setRunning();
					}
					
					// Set the AI Intention to ATTACK.
					setIntentionAttack(hated);
				}
				
				return;
			}
		}
		
		// Chance to forget attackers after some time.
		if ((npc.getCurrentHp() == npc.getMaxHp()) && (npc.getCurrentMp() == npc.getMaxMp()) && !npc.getAttackByList().isEmpty() && (Rnd.get(500) == 0))
		{
			npc.clearAggroList();
			npc.getAttackByList().clear();
		}
		
		// If this is a festival monster, then it remains in the same location.
		// if (npc instanceof FestivalMonster)
		// {
		// return;
		// }
		
		// Check if the mob should not return to spawn point.
		if (!npc.canReturnToSpawnPoint()
		/* || npc.isReturningToSpawnPoint() */ ) // Commented because sometimes it stops movement.
		{
			return;
		}
		
		// Order this attackable to return to its spawn because there's no target to attack.
		if (!npc.isWalker() && (npc.getSpawn() != null) && ((npc.getSpawn().getSpawnTerritory() != null) ? !npc.getSpawn().getSpawnTerritory().isInsideZone(npc.getX(), npc.getY()) : (npc.calculateDistance2D(npc.getSpawn()) > NpcConfig.MAX_DRIFT_RANGE)) && ((getTarget() == null) || getTarget().isInvisible() || (getTarget().isPlayer() && !NpcConfig.ATTACKABLES_CAMP_PLAYER_CORPSES && getTarget().asPlayer().isAlikeDead())))
		{
			npc.setWalking();
			npc.returnHome();
			return;
		}
		
		// Do not leave dead player.
		if ((getTarget() != null) && getTarget().isPlayer() && getTarget().asPlayer().isAlikeDead())
		{
			return;
		}
		
		// Minions following leader.
		final Creature leader = npc.getLeader();
		if ((leader != null) && !leader.isAlikeDead())
		{
			final int offset;
			final int minRadius = 30;
			if (npc.isRaidMinion())
			{
				offset = 500; // For Raids.
			}
			else
			{
				offset = 200; // For normal minions.
			}
			
			if (leader.isRunning())
			{
				npc.setRunning();
			}
			else
			{
				npc.setWalking();
			}
			
			if (npc.calculateDistance2D(leader) > offset)
			{
				int x1 = Rnd.get(minRadius * 2, offset * 2); // x
				int y1 = Rnd.get(x1, offset * 2); // distance
				y1 = (int) Math.sqrt((y1 * y1) - (x1 * x1)); // y
				x1 = x1 > (offset + minRadius) ? (leader.getX() + x1) - offset : (leader.getX() - x1) + minRadius;
				y1 = y1 > (offset + minRadius) ? (leader.getY() + y1) - offset : (leader.getY() - y1) + minRadius;
				
				// Move the actor to Location (x,y,z) server side AND client side by sending Server->Client packet MoveToLocation (broadcast).
				moveTo(x1, y1, leader.getZ());
				return;
			}
			
			if (Rnd.get(RANDOM_WALK_RATE) == 0)
			{
				for (Skill sk : npc.getTemplate().getAISkills(AISkillScope.BUFF))
				{
					// Skip buffs that are already active.
					if (npc.isAffectedBySkill(sk.getId()))
					{
						continue;
					}
					
					// Only consider skills if we have enough MP.
					if (npc.getCurrentMp() <= sk.getMpConsume())
					{
						continue;
					}
					
					// Healing skills: only cast if HP is not full.
					if (sk.getAbnormalType() == AbnormalType.LIFE_FORCE_OTHERS)
					{
						if (npc.getCurrentHp() >= npc.getMaxHp())
						{
							continue; // Skip healing if at full HP.
						}
					}
					
					npc.setTarget(npc);
					npc.doCast(sk);
					return;
				}
			}
		}
		// Order to the Monster to random walk (1/100).
		else if ((npc.getSpawn() != null) && (Rnd.get(RANDOM_WALK_RATE) == 0) && npc.isRandomWalkingEnabled())
		{
			for (Skill sk : npc.getTemplate().getAISkills(AISkillScope.BUFF))
			{
				// Skip buffs that are already active.
				if (npc.isAffectedBySkill(sk.getId()))
				{
					continue;
				}
				
				// Only consider skills if we have enough MP.
				if (npc.getCurrentMp() <= sk.getMpConsume())
				{
					continue;
				}
				
				// Healing skills: only cast if HP is not full.
				if (sk.getAbnormalType() == AbnormalType.LIFE_FORCE_OTHERS)
				{
					if (npc.getCurrentHp() >= npc.getMaxHp())
					{
						continue; // Skip healing if at full HP.
					}
				}
				
				npc.setTarget(npc);
				npc.doCast(sk);
				return;
			}
			
			// A territory spawn has no fixed home, the Spawn location holds the point rolled for the last NPC of the group.
			final NpcSpawnTerritory spawnTerritory = npc.getSpawn().getSpawnTerritory();
			if (spawnTerritory != null)
			{
				// Near a territory border most offsets fall outside, so give the roll a few attempts before giving up.
				for (int i = 0; i < WANDER_ATTEMPTS; i++)
				{
					final int deltaX = Rnd.get(NpcConfig.MAX_DRIFT_RANGE * 2); // x
					int deltaY = Rnd.get(deltaX, NpcConfig.MAX_DRIFT_RANGE * 2); // distance
					deltaY = (int) Math.sqrt((deltaY * deltaY) - (deltaX * deltaX)); // y
					final int x2 = (deltaX + npc.getX()) - NpcConfig.MAX_DRIFT_RANGE;
					final int y2 = (deltaY + npc.getY()) - NpcConfig.MAX_DRIFT_RANGE;
					if (!spawnTerritory.isInsideZone(x2, y2))
					{
						continue;
					}
					
					// Move the actor to Location (x,y,z) server side AND client side by sending Server->Client packet MoveToLocation (broadcast)
					final Location moveLoc = _actor.isFlying() ? new Location(x2, y2, npc.getZ()) : GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x2, y2, npc.getZ(), npc.getInstanceId());
					if (spawnTerritory.isInsideZone(moveLoc.getX(), moveLoc.getY()))
					{
						moveTo(moveLoc.getX(), moveLoc.getY(), moveLoc.getZ());
						break;
					}
				}
				
				return;
			}
			
			int x1 = npc.getSpawn().getX();
			int y1 = npc.getSpawn().getY();
			int z1 = npc.getSpawn().getZ();
			if (npc.isInsideRadius2D(x1, y1, 0, NpcConfig.MAX_DRIFT_RANGE))
			{
				final int deltaX = Rnd.get(NpcConfig.MAX_DRIFT_RANGE * 2); // x
				int deltaY = Rnd.get(deltaX, NpcConfig.MAX_DRIFT_RANGE * 2); // distance
				deltaY = (int) Math.sqrt((deltaY * deltaY) - (deltaX * deltaX)); // y
				x1 = (deltaX + x1) - NpcConfig.MAX_DRIFT_RANGE;
				y1 = (deltaY + y1) - NpcConfig.MAX_DRIFT_RANGE;
				z1 = npc.getZ();
			}
			
			// Move the actor to Location (x,y,z) server side AND client side by sending Server->Client packet MoveToLocation (broadcast).
			final Location moveLoc = _actor.isFlying() ? new Location(x1, y1, z1) : GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x1, y1, z1, npc.getInstanceId());
			if (LocationUtil.calculateDistance(npc.getSpawn(), moveLoc, false, false) <= NpcConfig.MAX_DRIFT_RANGE)
			{
				moveTo(moveLoc.getX(), moveLoc.getY(), moveLoc.getZ());
			}
		}
	}
	
	/**
	 * A territory spawn keeps a single location that every npc of the group shares, and Spawn.initializeNpc re-rolls
	 * it on each respawn, so it names the sibling that spawned last rather than this npc and cannot anchor a distance check.<br>
	 * Zone membership is the only stable test for those, the same one shouldPromoteIdleToActive already uses.
	 * @param npc the npc to test
	 * @param spawn the spawn the npc belongs to
	 * @return {@code true} if the npc has strayed outside the area it is meant to hold
	 */
	private static boolean hasStrayedFromSpawn(Attackable npc, Spawn spawn)
	{
		final int range = spawn.getChaseRange() > 0 ? Math.max(NpcConfig.MAX_DRIFT_RANGE, spawn.getChaseRange()) : npc.isRaid() ? NpcConfig.AGGRO_DISTANCE_CHECK_RAID_RANGE : NpcConfig.AGGRO_DISTANCE_CHECK_RANGE;
		final boolean outOfRange = npc.calculateDistance2D(spawn.getLocation()) > range;
		final NpcSpawnTerritory territory = spawn.getSpawnTerritory();
		if (territory != null)
		{
			// Leaving the territory alone is not straying, the chase range still applies.
			return outOfRange && !territory.isInsideZone(npc.getX(), npc.getY());
		}
		
		return outOfRange;
	}
	
	/**
	 * Manage AI attack thinks of a Attackable (called by onActionThink).<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Update the attack timeout if actor is running</li>
	 * <li>If target is dead or timeout is expired, stop this attack and set the Intention to ACTIVE</li>
	 * <li>Call all WorldObject of its Faction inside the Faction Range</li>
	 * <li>Chose a target and order to attack it with magic skill or physical attack</li>
	 * </ul>
	 */
	protected void thinkAttack()
	{
		final Attackable npc = getActiveChar();
		if ((npc == null) || npc.isCastingNow())
		{
			return;
		}
		
		if ((npc.isMonster() && !npc.isWalker() && !(npc instanceof GrandBoss)) && (npc.isRaid() ? NpcConfig.AGGRO_DISTANCE_CHECK_RAIDS : NpcConfig.AGGRO_DISTANCE_CHECK_ENABLED))
		{
			final Spawn spawn = npc.getSpawn();
			if ((spawn != null) && hasStrayedFromSpawn(npc, spawn))
			{
				if (NpcConfig.AGGRO_DISTANCE_CHECK_INSTANCES || !npc.isInInstance())
				{
					if (NpcConfig.AGGRO_DISTANCE_CHECK_RESTORE_LIFE)
					{
						npc.setCurrentHp(npc.getMaxHp());
						npc.setCurrentMp(npc.getMaxMp());
					}
					
					npc.abortAttack();
					npc.clearAggroList();
					npc.getAttackByList().clear();
					if (npc.hasAI())
					{
						npc.getAI().setIntentionMoveTo(spawn.getLocation());
					}
					else
					{
						npc.teleToLocation(spawn.getLocation(), true);
					}
					
					// Minions should return as well.
					if (_actor.asMonster().hasMinions())
					{
						for (Monster minion : _actor.asMonster().getMinionList().getSpawnedMinions())
						{
							if (NpcConfig.AGGRO_DISTANCE_CHECK_RESTORE_LIFE)
							{
								minion.setCurrentHp(minion.getMaxHp());
								minion.setCurrentMp(minion.getMaxMp());
							}
							
							minion.abortAttack();
							minion.clearAggroList();
							minion.getAttackByList().clear();
							if (minion.hasAI())
							{
								minion.getAI().setIntentionMoveTo(spawn.getLocation());
							}
							else
							{
								minion.teleToLocation(spawn.getLocation(), true);
							}
						}
					}
					return;
				}
			}
		}
		
		if (npc.isCoreAIDisabled())
		{
			return;
		}
		
		Creature mostHate = npc.getMostHated();
		if (mostHate == null)
		{
			setIntentionActive();
			return;
		}
		
		if (getAttackTarget() != mostHate)
		{
			setAttackTarget(mostHate);
		}
		
		if (getTarget() != mostHate)
		{
			setTarget(mostHate);
		}
		
		// Immobilize condition
		if (npc.isMovementDisabled())
		{
			movementDisable();
			return;
		}
		
		// Check if target is dead or if timeout is expired to stop this attack.
		if (mostHate.isAlikeDead())
		{
			// Stop hating this target after the attack timeout or if target is dead.
			npc.stopHating(mostHate);
			return;
		}
		
		if (_attackTimeout < GameTimeTaskManager.getInstance().getGameTicks())
		{
			// Remember the combat state, the intention change below clears it.
			final boolean wasInCombat = npc.isInCombat();
			
			// Set the AI Intention to ACTIVE.
			setIntentionActive();
			
			// Lose target and walk to spawn.
			setTarget(null);
			npc.clearAggroList();
			npc.getAttackByList().clear();
			
			if (!_actor.isFakePlayer())
			{
				npc.setWalking();
			}
			
			// Monster teleport to spawn.
			if (npc.isMonster() && (npc.getSpawn() != null) && !npc.isInInstance() && (wasInCombat || (World.getFirstVisibleObject(npc, Player.class) == null)))
			{
				npc.teleToLocation(npc.getSpawn(), false);
			}
			return;
		}
		
		// Actor should be able to see target.
		if (!GeoEngine.getInstance().canSeeTarget(_actor, mostHate))
		{
			if (_actor.calculateDistance3D(mostHate) < 6000)
			{
				moveTo(mostHate);
			}
			return;
		}
		
		// Initialize data.
		final NpcTemplate template = npc.getTemplate();
		final List<Skill> aiSuicideSkills = template.getAISkills(AISkillScope.SUICIDE);
		if (!aiSuicideSkills.isEmpty() && ((int) ((npc.getCurrentHp() / npc.getMaxHp()) * 100) < 30))
		{
			final Skill skill = aiSuicideSkills.get(Rnd.get(aiSuicideSkills.size()));
			if (LocationUtil.checkIfInRange(skill.getAffectRange(), npc, mostHate, false) && npc.hasSkillChance() && cast(skill))
			{
				return;
			}
		}
		
		// ------------------------------------------------------
		// In case many mobs are trying to hit from same place, move a bit, circling around the target.
		// Note from Gnacik:
		// On l2js because of that sometimes mobs don't attack player only running around player without any sense, so decrease chance for now.
		final int collision = template.getCollisionRadius();
		final int combinedCollision = collision + mostHate.getTemplate().getCollisionRadius();
		if (!npc.isMovementDisabled() && (Rnd.get(100) <= 3))
		{
			final Creature currentTarget = mostHate;
			if (World.getFirstVisibleObject(npc, Attackable.class, nearby -> npc.isInsideRadius2D(nearby, collision) && (nearby != currentTarget)) != null)
			{
				int newX = combinedCollision + Rnd.get(40);
				newX = Rnd.nextBoolean() ? mostHate.getX() + newX : mostHate.getX() - newX;
				int newY = combinedCollision + Rnd.get(40);
				newY = Rnd.nextBoolean() ? mostHate.getY() + newY : mostHate.getY() - newY;
				if (!npc.isInsideRadius2D(newX, newY, 0, collision))
				{
					final int newZ = npc.getZ() + 30;
					
					// Mobius: Verify destination. Prevents wall collision issues and fixes monsters not avoiding obstacles.
					moveTo(GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), newX, newY, newZ, npc.getInstanceId()));
				}
				return;
			}
		}
		
		// Calculate Archer movement.
		if ((!npc.isMovementDisabled()) && (npc.getAiType() == AIType.ARCHER) && (Rnd.get(100) < 15))
		{
			final double distance = npc.calculateDistance2D(mostHate);
			if (distance <= (60 + combinedCollision))
			{
				int posX = npc.getX();
				int posY = npc.getY();
				final int posZ = npc.getZ() + 30;
				if (mostHate.getX() < posX)
				{
					posX += 300;
				}
				else
				{
					posX -= 300;
				}
				
				if (mostHate.getY() < posY)
				{
					posY += 300;
				}
				else
				{
					posY -= 300;
				}
				
				if (GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), posX, posY, posZ, npc.getInstanceId()))
				{
					setIntentionMoveTo(new Location(posX, posY, posZ));
				}
				return;
			}
		}
		
		// BOSS/Raid Minion Target Reconsider.
		if (npc.isRaid() || npc.isRaidMinion())
		{
			_chaosTime++;
			boolean changeTarget = false;
			if ((npc instanceof RaidBoss) && (_chaosTime > NpcConfig.RAID_CHAOS_TIME))
			{
				final double multiplier = npc.asMonster().hasMinions() ? 200 : 100;
				changeTarget = Rnd.get(100) <= (100 - ((npc.getCurrentHp() * multiplier) / npc.getMaxHp()));
			}
			else if ((npc instanceof GrandBoss) && (_chaosTime > NpcConfig.GRAND_CHAOS_TIME))
			{
				final double chaosRate = 100 - ((npc.getCurrentHp() * 300) / npc.getMaxHp());
				changeTarget = ((chaosRate <= 10) && (Rnd.get(100) <= 10)) || ((chaosRate > 10) && (Rnd.get(100) <= chaosRate));
			}
			else if (_chaosTime > NpcConfig.MINION_CHAOS_TIME)
			{
				changeTarget = Rnd.get(100) <= (100 - ((npc.getCurrentHp() * 200) / npc.getMaxHp()));
			}
			
			if (changeTarget)
			{
				mostHate = targetReconsider(true);
				if (mostHate != null)
				{
					setAttackTarget(mostHate);
					setTarget(mostHate);
					_chaosTime = 0;
					return;
				}
			}
		}
		
		if (mostHate == null)
		{
			mostHate = targetReconsider(false);
			if (mostHate == null)
			{
				return;
			}
			
			setAttackTarget(mostHate);
			setTarget(mostHate);
		}
		
		// Cast skills.
		if (!npc.isMoving() || (npc.getAiType() == AIType.MAGE))
		{
			final List<Skill> generalSkills = template.getAISkills(AISkillScope.GENERAL);
			if (!generalSkills.isEmpty())
			{
				// Heal Condition
				final List<Skill> aiHealSkills = template.getAISkills(AISkillScope.HEAL);
				if (!aiHealSkills.isEmpty())
				{
					if (npc.isMinion())
					{
						final Creature leader = npc.getLeader();
						if ((leader != null) && !leader.isDead() && (Rnd.get(100) > ((leader.getCurrentHp() / leader.getMaxHp()) * 100)))
						{
							for (Skill healSkill : aiHealSkills)
							{
								if (healSkill.getTargetType() == TargetType.SELF)
								{
									continue;
								}
								
								if (!checkSkillCastConditions(npc, healSkill))
								{
									continue;
								}
								
								if (!LocationUtil.checkIfInRange((healSkill.getCastRange() + collision + leader.getTemplate().getCollisionRadius()), npc, leader, false) && !isParty(healSkill) && !npc.isMovementDisabled())
								{
									moveToPawn(leader, healSkill.getCastRange() + collision + leader.getTemplate().getCollisionRadius());
									return;
								}
								
								if (GeoEngine.getInstance().canSeeTarget(npc, leader))
								{
									clientStopMoving(null);
									
									final WorldObject target = npc.getTarget();
									npc.setTarget(leader);
									npc.doCast(healSkill);
									npc.setTarget(target);
									// LOGGER.debug(this + " used heal skill " + healSkill + " on leader " + leader);
									return;
								}
							}
						}
					}
					
					double percentage = (npc.getCurrentHp() / npc.getMaxHp()) * 100;
					if (Rnd.get(100) < ((100 - percentage) / 3))
					{
						for (Skill sk : aiHealSkills)
						{
							if (!checkSkillCastConditions(npc, sk))
							{
								continue;
							}
							
							clientStopMoving(null);
							
							final WorldObject target = npc.getTarget();
							npc.setTarget(npc);
							npc.doCast(sk);
							npc.setTarget(target);
							// LOGGER.debug(this + " used heal skill " + sk + " on itself");
							return;
						}
					}
					
					for (Skill sk : aiHealSkills)
					{
						if (!checkSkillCastConditions(npc, sk))
						{
							continue;
						}
						
						if (sk.getTargetType() == TargetType.ONE)
						{
							final Attackable healTarget = World.getFirstVisibleObjectInRange(npc, Attackable.class, sk.getCastRange() + collision, obj -> obj.isDead() && obj.isInMyClan(npc) && (Rnd.get(100) < ((100 - ((obj.getCurrentHp() / obj.getMaxHp()) * 100)) / 10)) && GeoEngine.getInstance().canSeeTarget(npc, obj));
							if (healTarget != null)
							{
								clientStopMoving(null);
								
								final WorldObject target = npc.getTarget();
								npc.setTarget(healTarget);
								npc.doCast(sk);
								npc.setTarget(target);
								// LOGGER.debug(this + " used heal skill " + sk + " on " + healTarget);
								return;
							}
						}
						
						if (isParty(sk))
						{
							clientStopMoving(null);
							npc.doCast(sk);
							return;
						}
					}
				}
				
				// Res Skill Condition
				final List<Skill> aiResSkills = template.getAISkills(AISkillScope.RES);
				if (!aiResSkills.isEmpty())
				{
					if (npc.isMinion())
					{
						final Creature leader = npc.getLeader();
						if ((leader != null) && leader.isDead())
						{
							for (Skill sk : aiResSkills)
							{
								if (sk.getTargetType() == TargetType.SELF)
								{
									continue;
								}
								
								if (!checkSkillCastConditions(npc, sk))
								{
									continue;
								}
								
								if (!LocationUtil.checkIfInRange((sk.getCastRange() + collision + leader.getTemplate().getCollisionRadius()), npc, leader, false) && !isParty(sk) && !npc.isMovementDisabled())
								{
									moveToPawn(leader, sk.getCastRange() + collision + leader.getTemplate().getCollisionRadius());
									return;
								}
								
								if (GeoEngine.getInstance().canSeeTarget(npc, leader))
								{
									clientStopMoving(null);
									
									final WorldObject target = npc.getTarget();
									npc.setTarget(leader);
									npc.doCast(sk);
									npc.setTarget(target);
									// LOGGER.debug(this + " used resurrection skill " + sk + " on leader " + leader);
									return;
								}
							}
						}
					}
					
					for (Skill sk : aiResSkills)
					{
						if (!checkSkillCastConditions(npc, sk))
						{
							continue;
						}
						
						if (sk.getTargetType() == TargetType.ONE)
						{
							final Attackable resTarget = World.getFirstVisibleObjectInRange(npc, Attackable.class, sk.getCastRange() + collision, obj -> obj.isDead() && npc.isInMyClan(obj) && (Rnd.get(100) < 10) && GeoEngine.getInstance().canSeeTarget(npc, obj));
							if (resTarget != null)
							{
								clientStopMoving(null);
								
								final WorldObject target = npc.getTarget();
								npc.setTarget(resTarget);
								npc.doCast(sk);
								npc.setTarget(target);
								// LOGGER.debug(this + " used heal skill " + sk + " on clan member " + resTarget);
								return;
							}
						}
						
						if (isParty(sk))
						{
							clientStopMoving(null);
							
							final WorldObject target = npc.getTarget();
							npc.setTarget(npc);
							npc.doCast(sk);
							npc.setTarget(target);
							// LOGGER.debug(this + " used heal skill " + sk + " on party");
							return;
						}
					}
				}
			}
			
			// Long/Short Range skill usage.
			final List<Skill> shortRangeSkills = npc.getShortRangeSkills();
			if (!shortRangeSkills.isEmpty() && npc.hasSkillChance() && (npc.calculateDistance2D(mostHate) <= 150))
			{
				final Skill shortRangeSkill = shortRangeSkills.get(Rnd.get(shortRangeSkills.size()));
				final int castRange = shortRangeSkill.getCastRange();
				if (((castRange < 1) || (npc.calculateDistance3D(mostHate) < castRange)) && checkSkillCastConditions(npc, shortRangeSkill) && allowsParameterizedCast(npc, shortRangeSkill, mostHate))
				{
					clientStopMoving(null);
					npc.setTarget(mostHate);
					npc.doCast(shortRangeSkill);
					// LOGGER.debug(this + " used short range skill " + shortRangeSkill + " on " + npc.getTarget());
					return;
				}
			}
			
			final List<Skill> longRangeSkills = npc.getLongRangeSkills();
			if (!longRangeSkills.isEmpty() && npc.hasSkillChance())
			{
				final Skill longRangeSkill = longRangeSkills.get(Rnd.get(longRangeSkills.size()));
				final int castRange = longRangeSkill.getCastRange();
				if (((castRange < 1) || (npc.calculateDistance3D(mostHate) < castRange)) && checkSkillCastConditions(npc, longRangeSkill) && allowsParameterizedCast(npc, longRangeSkill, mostHate))
				{
					clientStopMoving(null);
					npc.setTarget(mostHate);
					npc.doCast(longRangeSkill);
					// LOGGER.debug(this + " used long range skill " + longRangeSkill + " on " + npc.getTarget());
					return;
				}
			}
		}
		
		// Check if target is within range or move.
		int range = npc.getPhysicalAttackRange() + combinedCollision;
		if (npc.isMoving())
		{
			range *= 2;
		}
		
		if (npc.getAiType() == AIType.ARCHER)
		{
			range = 850 + combinedCollision; // Base bow range for NPCs.
		}
		
		if (npc.calculateDistance2D(mostHate) > range)
		{
			if (checkTarget(mostHate))
			{
				moveToPawn(mostHate, range);
				return;
			}
			
			mostHate = targetReconsider(false);
			if (mostHate == null)
			{
				return;
			}
			
			setAttackTarget(mostHate);
			setTarget(mostHate);
		}
		
		// Attacks target
		_actor.doAttack(getAttackTarget());
	}
	
	private boolean checkTarget(WorldObject target)
	{
		if (target == null)
		{
			return false;
		}
		
		final Attackable npc = getActiveChar();
		if (target.isCreature())
		{
			if (target.asCreature().isDead())
			{
				return false;
			}
			
			if (npc.isMovementDisabled())
			{
				if (!npc.isInsideRadius2D(target, npc.getPhysicalAttackRange() + npc.getTemplate().getCollisionRadius() + target.asCreature().getTemplate().getCollisionRadius()))
				{
					return false;
				}
				
				if (!GeoEngine.getInstance().canSeeTarget(npc, target))
				{
					return false;
				}
			}
			
			if (!target.isAutoAttackable(npc))
			{
				return false;
			}
		}
		
		// Fixes monsters not avoiding obstacles.
		return true; // GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), target.getX(), target.getY(), target.getZ(), npc.getInstanceWorld());
	}
	
	private Creature targetReconsider(boolean randomTarget)
	{
		final Attackable npc = getActiveChar();
		if (randomTarget)
		{
			final List<Creature> result = new ArrayList<>();
			for (AggroInfo aggro : npc.getAggroList().values())
			{
				if (checkTarget(aggro.getAttacker()))
				{
					result.add(aggro.getAttacker());
				}
			}
			
			// If npc is aggressive, add characters within aggro range too.
			if (npc.isAggressive())
			{
				World.forEachVisibleObjectInRange(npc, Creature.class, npc.getAggroRange(), creature ->
				{
					if (checkTarget(creature))
					{
						result.add(creature);
					}
				});
			}
			
			if (!result.isEmpty())
			{
				return result.get(Rnd.get(result.size()));
			}
		}
		
		long searchValue = Long.MIN_VALUE;
		Creature creature = null;
		for (AggroInfo aggro : npc.getAggroList().values())
		{
			if (checkTarget(aggro.getAttacker()) && (aggro.getHate() > searchValue))
			{
				searchValue = aggro.getHate();
				creature = aggro.getAttacker();
			}
		}
		
		if ((creature == null) && npc.isAggressive())
		{
			final Creature _match = World.getFirstVisibleObjectInRange(npc, Creature.class, npc.getAggroRange(), nearby -> checkTarget(nearby));
			if (_match != null)
			{
				return _match;
			}
		}
		
		return null;
	}
	
	private boolean cast(Skill sk)
	{
		if (sk == null)
		{
			return false;
		}
		
		final Attackable caster = getActiveChar();
		if (!checkSkillCastConditions(caster, sk))
		{
			return false;
		}
		
		if (!allowsParameterizedCast(caster, sk, getAttackTarget()))
		{
			return false;
		}
		
		if ((getAttackTarget() == null) && (caster.getMostHated() != null))
		{
			setAttackTarget(caster.getMostHated());
		}
		
		final Creature attackTarget = getAttackTarget();
		if (attackTarget == null)
		{
			return false;
		}
		
		final double dist = caster.calculateDistance2D(attackTarget);
		double dist2 = dist - attackTarget.getTemplate().getCollisionRadius();
		final double srange = sk.getCastRange() + caster.getTemplate().getCollisionRadius();
		if (attackTarget.isMoving())
		{
			dist2 -= 30;
		}
		
		if (sk.isContinuous())
		{
			if (!sk.isDebuff())
			{
				if (!caster.isAffectedBySkill(sk.getId()))
				{
					clientStopMoving(null);
					caster.setTarget(caster);
					caster.doCast(sk);
					_actor.setTarget(attackTarget);
					return true;
				}
				
				// If actor already have buff, start looking at others same faction mob to cast.
				if (sk.getTargetType() == TargetType.SELF)
				{
					return false;
				}
				
				if (sk.getTargetType() == TargetType.ONE)
				{
					final Creature target = effectTargetReconsider(sk, true);
					if (target != null)
					{
						clientStopMoving(null);
						caster.setTarget(target);
						caster.doCast(sk);
						caster.setTarget(attackTarget);
						return true;
					}
				}
				
				if (canParty(sk))
				{
					clientStopMoving(null);
					caster.setTarget(caster);
					caster.doCast(sk);
					caster.setTarget(attackTarget);
					return true;
				}
			}
			else
			{
				if (GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !canAOE(sk) && !attackTarget.isDead() && (dist2 <= srange))
				{
					if (!attackTarget.isAffectedBySkill(sk.getId()))
					{
						clientStopMoving(null);
						caster.doCast(sk);
						return true;
					}
				}
				else if (canAOE(sk))
				{
					if ((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA) || (sk.getTargetType() == TargetType.AURA_CORPSE_MOB))
					{
						clientStopMoving(null);
						caster.doCast(sk);
						return true;
					}
					
					if (((sk.getTargetType() == TargetType.AREA) || (sk.getTargetType() == TargetType.BEHIND_AREA) || (sk.getTargetType() == TargetType.FRONT_AREA)) && GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
					{
						clientStopMoving(null);
						caster.doCast(sk);
						return true;
					}
				}
				else if (sk.getTargetType() == TargetType.ONE)
				{
					final Creature target = effectTargetReconsider(sk, false);
					if (target != null)
					{
						clientStopMoving(null);
						caster.doCast(sk);
						return true;
					}
				}
			}
		}
		
		if (sk.hasEffectType(EffectType.DISPEL, EffectType.DISPEL_BY_SLOT))
		{
			if (sk.getTargetType() == TargetType.ONE)
			{
				if ((attackTarget.getEffectList().getFirstEffect(EffectType.BUFF) != null) && GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
				
				final Creature target = effectTargetReconsider(sk, false);
				if (target != null)
				{
					clientStopMoving(null);
					caster.setTarget(target);
					caster.doCast(sk);
					caster.setTarget(attackTarget);
					return true;
				}
			}
			else if (canAOE(sk))
			{
				if (((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA)) && GeoEngine.getInstance().canSeeTarget(caster, attackTarget))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
				else if (((sk.getTargetType() == TargetType.AREA) || (sk.getTargetType() == TargetType.BEHIND_AREA) || (sk.getTargetType() == TargetType.FRONT_AREA)) && GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
		}
		
		if (sk.hasEffectType(EffectType.HEAL))
		{
			if (caster.isMinion() && (sk.getTargetType() != TargetType.SELF))
			{
				final Creature leader = caster.getLeader();
				if ((leader != null) && !leader.isDead() && (Rnd.get(100) > ((leader.getCurrentHp() / leader.getMaxHp()) * 100)))
				{
					if (!LocationUtil.checkIfInRange((sk.getCastRange() + caster.getTemplate().getCollisionRadius() + leader.getTemplate().getCollisionRadius()), caster, leader, false) && !isParty(sk) && !caster.isMovementDisabled())
					{
						moveToPawn(leader, sk.getCastRange() + caster.getTemplate().getCollisionRadius() + leader.getTemplate().getCollisionRadius());
					}
					
					if (GeoEngine.getInstance().canSeeTarget(caster, leader))
					{
						clientStopMoving(null);
						caster.setTarget(leader);
						caster.doCast(sk);
						caster.setTarget(attackTarget);
						return true;
					}
				}
			}
			
			double percentage = (caster.getCurrentHp() / caster.getMaxHp()) * 100;
			if (Rnd.get(100) < ((100 - percentage) / 3))
			{
				clientStopMoving(null);
				caster.setTarget(caster);
				caster.doCast(sk);
				caster.setTarget(attackTarget);
				return true;
			}
			
			if (sk.getTargetType() == TargetType.ONE)
			{
				final Attackable healTarget = World.getFirstVisibleObjectInRange(caster, Attackable.class, sk.getCastRange() + caster.getTemplate().getCollisionRadius(), obj -> !obj.isDead() && caster.isInMyClan(obj) && (Rnd.get(100) < ((100 - ((obj.getCurrentHp() / obj.getMaxHp()) * 100)) / 10)) && GeoEngine.getInstance().canSeeTarget(caster, obj));
				if (healTarget != null)
				{
					clientStopMoving(null);
					caster.setTarget(healTarget);
					caster.doCast(sk);
					caster.setTarget(attackTarget);
					return true;
				}
			}
			
			if (isParty(sk))
			{
				if (World.getFirstVisibleObjectInRange(caster, Attackable.class, sk.getAffectRange() + caster.getTemplate().getCollisionRadius(), obj -> obj.isInMyClan(caster) && (obj.getCurrentHp() < obj.getMaxHp()) && (Rnd.get(100) <= 20)) != null)
				{
					clientStopMoving(null);
					caster.setTarget(caster);
					caster.doCast(sk);
					caster.setTarget(attackTarget);
					return true;
				}
			}
		}
		
		if (sk.hasEffectType(EffectType.PHYSICAL_ATTACK, EffectType.PHYSICAL_ATTACK_HP_LINK, EffectType.MAGICAL_ATTACK, EffectType.DEATH_LINK, EffectType.HP_DRAIN))
		{
			if (!canAura(sk))
			{
				if (GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
				
				final Creature target = skillTargetReconsider(sk);
				if (target != null)
				{
					clientStopMoving(null);
					caster.setTarget(target);
					caster.doCast(sk);
					caster.setTarget(attackTarget);
					return true;
				}
			}
			else
			{
				clientStopMoving(null);
				caster.doCast(sk);
				return true;
			}
		}
		
		if (sk.hasEffectType(EffectType.SLEEP))
		{
			if (sk.getTargetType() == TargetType.ONE)
			{
				final double range = caster.getPhysicalAttackRange() + caster.getTemplate().getCollisionRadius() + attackTarget.getTemplate().getCollisionRadius();
				if (!attackTarget.isDead() && (dist2 <= srange) && ((dist2 > range) || attackTarget.isMoving()) && !attackTarget.isAffectedBySkill(sk.getId()))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
				
				final Creature target = effectTargetReconsider(sk, false);
				if (target != null)
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
			else if (canAOE(sk))
			{
				if ((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
				
				if (((sk.getTargetType() == TargetType.AREA) || (sk.getTargetType() == TargetType.BEHIND_AREA) || (sk.getTargetType() == TargetType.FRONT_AREA)) && GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
		}
		
		if (sk.hasEffectType(EffectType.STUN, EffectType.ROOT, EffectType.PARALYZE, EffectType.MUTE, EffectType.FEAR))
		{
			if (GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !canAOE(sk) && (dist2 <= srange))
			{
				if (!attackTarget.isAffectedBySkill(sk.getId()))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
			else if (canAOE(sk))
			{
				if ((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
				
				if (((sk.getTargetType() == TargetType.AREA) || (sk.getTargetType() == TargetType.BEHIND_AREA) || (sk.getTargetType() == TargetType.FRONT_AREA)) && GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
			else if (sk.getTargetType() == TargetType.ONE)
			{
				final Creature target = effectTargetReconsider(sk, false);
				if (target != null)
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
		}
		
		if (sk.hasEffectType(EffectType.DMG_OVER_TIME, EffectType.DMG_OVER_TIME_PERCENT))
		{
			if (GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !canAOE(sk) && !attackTarget.isDead() && (dist2 <= srange))
			{
				if (!attackTarget.isAffectedBySkill(sk.getId()))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
			else if (canAOE(sk))
			{
				if ((sk.getTargetType() == TargetType.AURA) || (sk.getTargetType() == TargetType.BEHIND_AURA) || (sk.getTargetType() == TargetType.FRONT_AURA) || (sk.getTargetType() == TargetType.AURA_CORPSE_MOB))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
				
				if (((sk.getTargetType() == TargetType.AREA) || (sk.getTargetType() == TargetType.BEHIND_AREA) || (sk.getTargetType() == TargetType.FRONT_AREA)) && GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
			else if (sk.getTargetType() == TargetType.ONE)
			{
				final Creature target = effectTargetReconsider(sk, false);
				if (target != null)
				{
					clientStopMoving(null);
					caster.doCast(sk);
					return true;
				}
			}
		}
		
		if (sk.hasEffectType(EffectType.RESURRECTION))
		{
			if (!isParty(sk))
			{
				if (caster.isMinion() && (sk.getTargetType() != TargetType.SELF))
				{
					final Creature leader = caster.getLeader();
					if (leader != null)
					{
						if (leader.isDead() && !LocationUtil.checkIfInRange((sk.getCastRange() + caster.getTemplate().getCollisionRadius() + leader.getTemplate().getCollisionRadius()), caster, leader, false) && !isParty(sk) && !caster.isMovementDisabled())
						{
							moveToPawn(leader, sk.getCastRange() + caster.getTemplate().getCollisionRadius() + leader.getTemplate().getCollisionRadius());
						}
						
						if (GeoEngine.getInstance().canSeeTarget(caster, leader))
						{
							clientStopMoving(null);
							caster.setTarget(leader);
							caster.doCast(sk);
							caster.setTarget(attackTarget);
							return true;
						}
					}
				}
				
				final Attackable resTarget = World.getFirstVisibleObjectInRange(caster, Attackable.class, sk.getCastRange() + caster.getTemplate().getCollisionRadius(), obj -> obj.isDead() && caster.isInMyClan(obj) && (Rnd.get(100) < 10) && GeoEngine.getInstance().canSeeTarget(caster, obj));
				if (resTarget != null)
				{
					clientStopMoving(null);
					caster.setTarget(resTarget);
					caster.doCast(sk);
					caster.setTarget(attackTarget);
					return true;
				}
			}
			else if (isParty(sk))
			{
				if (World.getFirstVisibleObjectInRange(caster, Npc.class, sk.getAffectRange() + caster.getTemplate().getCollisionRadius(), obj -> caster.isInMyClan(obj) && (obj.getCurrentHp() < obj.getMaxHp()) && (Rnd.get(100) <= 20)) != null)
				{
					clientStopMoving(null);
					caster.setTarget(caster);
					caster.doCast(sk);
					caster.setTarget(attackTarget);
					return true;
				}
			}
		}
		
		if (!canAura(sk))
		{
			if (GeoEngine.getInstance().canSeeTarget(caster, attackTarget) && !attackTarget.isDead() && (dist2 <= srange))
			{
				clientStopMoving(null);
				caster.doCast(sk);
				return true;
			}
			
			final Creature target = skillTargetReconsider(sk);
			if (target != null)
			{
				clientStopMoving(null);
				caster.setTarget(target);
				caster.doCast(sk);
				caster.setTarget(attackTarget);
				return true;
			}
		}
		else
		{
			clientStopMoving(null);
			caster.doCast(sk);
			return true;
		}
		
		return false;
	}
	
	private void movementDisable()
	{
		final Creature target = getAttackTarget();
		if (target == null)
		{
			return;
		}
		
		final Attackable npc = getActiveChar();
		if (npc.getTarget() == null)
		{
			npc.setTarget(target);
		}
		
		final double dist = npc.calculateDistance2D(target);
		
		// TODO(Zoey76): Review this "magic changes".
		final int random = Rnd.get(100);
		if (!target.isImmobilized() && (random < 15) && tryCast(npc, target, AISkillScope.IMMOBILIZE, dist))
		{
			return;
		}
		
		if ((random < 20) && tryCast(npc, target, AISkillScope.COT, dist))
		{
			return;
		}
		
		if ((random < 30) && tryCast(npc, target, AISkillScope.DEBUFF, dist))
		{
			return;
		}
		
		if ((random < 40) && tryCast(npc, target, AISkillScope.NEGATIVE, dist))
		{
			return;
		}
		
		if ((npc.isMovementDisabled() || (npc.getAiType() == AIType.MAGE) || (npc.getAiType() == AIType.HEALER)) && tryCast(npc, target, AISkillScope.ATTACK, dist))
		{
			return;
		}
		
		if (tryCast(npc, target, AISkillScope.UNIVERSAL, dist))
		{
			return;
		}
		
		// If cannot cast, try to attack.
		final int range = npc.getPhysicalAttackRange() + npc.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
		if ((dist <= range) && GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			_actor.doAttack(target);
			return;
		}
		
		// If cannot cast nor attack, find a new target.
		targetReconsider();
	}
	
	private boolean tryCast(Attackable npc, Creature target, AISkillScope aiSkillScope, double dist)
	{
		for (Skill sk : npc.getTemplate().getAISkills(aiSkillScope))
		{
			if (!checkSkillCastConditions(npc, sk) || (((sk.getCastRange() + target.getTemplate().getCollisionRadius()) <= dist) && !canAura(sk)))
			{
				continue;
			}
			
			if (!GeoEngine.getInstance().canSeeTarget(npc, target))
			{
				continue;
			}
			
			if (!allowsParameterizedCast(npc, sk, target))
			{
				continue;
			}
			
			clientStopMoving(null);
			npc.doCast(sk);
			return true;
		}
		
		return false;
	}
	
	/**
	 * @param caster the caster
	 * @param skill the skill to check.
	 * @return {@code true} if the skill is available for casting {@code false} otherwise.
	 */
	private static boolean checkSkillCastConditions(Attackable caster, Skill skill)
	{
		if (caster.isCastingNow() && !skill.isSimultaneousCast())
		{
			return false;
		}
		
		// Not enough MP.
		if (skill.getMpConsume() >= caster.getCurrentMp())
		{
			return false;
		}
		
		// Character is in "skill disabled" mode.
		if (caster.isSkillDisabled(skill))
		{
			return false;
		}
		
		// If is a static skill and magic skill and character is muted or is a physical skill muted and character is physically muted.
		if (!skill.isStatic() && ((skill.isMagic() && caster.isMuted()) || caster.isPhysicalMuted()))
		{
			return false;
		}
		
		return true;
	}
	
	private Creature effectTargetReconsider(Skill sk, boolean positive)
	{
		if (sk == null)
		{
			return null;
		}
		
		final Attackable actor = getActiveChar();
		final Creature attackTarget = getAttackTarget();
		if ((actor == null) || (attackTarget == null))
		{
			return null;
		}
		
		if (!sk.hasEffectType(EffectType.DISPEL, EffectType.DISPEL_BY_SLOT))
		{
			if (!positive)
			{
				double dist = 0;
				double dist2 = 0;
				int range = 0;
				for (Creature obj : actor.getAttackByList())
				{
					if ((obj == null) || obj.isDead() || !GeoEngine.getInstance().canSeeTarget(actor, obj) || (obj == attackTarget))
					{
						continue;
					}
					
					try
					{
						actor.setTarget(attackTarget);
						dist = actor.calculateDistance2D(obj);
						dist2 = dist - actor.getTemplate().getCollisionRadius();
						range = sk.getCastRange() + actor.getTemplate().getCollisionRadius() + obj.getTemplate().getCollisionRadius();
						if (obj.isMoving())
						{
							dist2 -= 70;
						}
					}
					catch (NullPointerException e)
					{
						continue;
					}
					
					if ((dist2 <= range) && !attackTarget.isAffectedBySkill(sk.getId()))
					{
						return obj;
					}
				}
				
				// ----------------------------------------------------------------------
				// If there is nearby Target with aggro, start going on random target that is attackable.
				final Creature found = World.getFirstVisibleObjectInRange(actor, Creature.class, range, obj ->
				{
					if (obj.isDead() || !GeoEngine.getInstance().canSeeTarget(actor, obj))
					{
						return false;
					}
					
					try
					{
						actor.setTarget(attackTarget);
						final double localDist = actor.calculateDistance2D(obj);
						final int localRange = sk.getCastRange() + actor.getTemplate().getCollisionRadius() + obj.getTemplate().getCollisionRadius();
						final double localDist2 = obj.isMoving() ? localDist - 70 : localDist;
						return (obj.isPlayer() || obj.isSummon()) && (localDist2 <= localRange) && !attackTarget.isAffectedBySkill(sk.getId());
					}
					catch (NullPointerException e)
					{
						return false;
					}
				});
				
				if (found != null)
				{
					return found;
				}
			}
			else
			{
				final int range = sk.getCastRange() + actor.getTemplate().getCollisionRadius();
				final Attackable foundClan = World.getFirstVisibleObjectInRange(actor, Attackable.class, range, targets ->
				{
					if (targets.isDead() || !GeoEngine.getInstance().canSeeTarget(actor, targets) || !targets.isInMyClan(actor))
					{
						return false;
					}
					
					try
					{
						actor.setTarget(attackTarget);
						final double localDist = actor.calculateDistance2D(targets);
						final int localRange = sk.getCastRange() + actor.getTemplate().getCollisionRadius() + targets.getTemplate().getCollisionRadius();
						final double localDist2Base = localDist - actor.getTemplate().getCollisionRadius();
						final double localDist2 = targets.isMoving() ? localDist2Base - 70 : localDist2Base;
						return (localDist2 <= localRange) && !targets.isAffectedBySkill(sk.getId());
					}
					catch (NullPointerException e)
					{
						return false;
					}
				});
				
				if (foundClan != null)
				{
					return foundClan;
				}
			}
		}
		else
		{
			final int range = sk.getCastRange() + actor.getTemplate().getCollisionRadius() + attackTarget.getTemplate().getCollisionRadius();
			final Creature foundBuff = World.getFirstVisibleObjectInRange(actor, Creature.class, range, obj ->
			{
				if (obj.isDead() || !GeoEngine.getInstance().canSeeTarget(actor, obj))
				{
					return false;
				}
				
				try
				{
					actor.setTarget(attackTarget);
					final double localDist = actor.calculateDistance2D(obj);
					final int localRange = sk.getCastRange() + actor.getTemplate().getCollisionRadius() + obj.getTemplate().getCollisionRadius();
					final double localDist2Base = localDist - actor.getTemplate().getCollisionRadius();
					final double localDist2 = obj.isMoving() ? localDist2Base - 70 : localDist2Base;
					return (obj.isPlayer() || obj.isSummon()) && (localDist2 <= localRange) && (attackTarget.getEffectList().getFirstEffect(EffectType.BUFF) != null);
				}
				catch (NullPointerException e)
				{
					return false;
				}
			});
			
			if (foundBuff != null)
			{
				return foundBuff;
			}
		}
		
		return null;
	}
	
	private Creature skillTargetReconsider(Skill sk)
	{
		double dist = 0;
		double dist2 = 0;
		int range = 0;
		final Attackable actor = getActiveChar();
		if (actor.getHateList() != null)
		{
			for (Creature obj : actor.getHateList())
			{
				if ((obj == null) || !GeoEngine.getInstance().canSeeTarget(actor, obj) || obj.isDead())
				{
					continue;
				}
				
				try
				{
					actor.setTarget(getAttackTarget());
					dist = actor.calculateDistance2D(obj);
					dist2 = dist - actor.getTemplate().getCollisionRadius();
					range = sk.getCastRange() + actor.getTemplate().getCollisionRadius() + getAttackTarget().getTemplate().getCollisionRadius();
					
					// if(obj.isMoving())
					// dist2 = dist2 - 40;
				}
				catch (NullPointerException e)
				{
					continue;
				}
				
				if (dist2 <= range)
				{
					return obj;
				}
			}
		}
		
		if (!(actor instanceof Guard))
		{
			final Creature foundWorld = World.getFirstVisibleObject(actor, Creature.class, obj ->
			{
				try
				{
					actor.setTarget(getAttackTarget());
					final double localDist = actor.calculateDistance2D(obj);
					final int localRange = sk.getCastRange() + actor.getTemplate().getCollisionRadius() + getAttackTarget().getTemplate().getCollisionRadius();
					if (localDist > localRange)
					{
						return false;
					}
				}
				catch (NullPointerException e)
				{
					return false;
				}
				
				if (!GeoEngine.getInstance().canSeeTarget(actor, obj))
				{
					return false;
				}
				
				if (obj.isPlayer())
				{
					return true;
				}
				
				if (obj.isAttackable() && actor.isChaos())
				{
					return !obj.asAttackable().isInMyClan(actor);
				}
				
				return obj.isSummon();
			});
			
			if (foundWorld != null)
			{
				return foundWorld;
			}
		}
		
		return null;
	}
	
	private void targetReconsider()
	{
		double dist = 0;
		double dist2 = 0;
		int range = 0;
		final Attackable actor = getActiveChar();
		final Creature mostHate = actor.getMostHated();
		if (actor.getHateList() != null)
		{
			for (Creature obj : actor.getHateList())
			{
				if ((obj == null) || !GeoEngine.getInstance().canSeeTarget(actor, obj) || obj.isDead() || (obj != mostHate) || (obj == actor))
				{
					continue;
				}
				
				try
				{
					dist = actor.calculateDistance2D(obj);
					dist2 = dist - actor.getTemplate().getCollisionRadius();
					range = actor.getPhysicalAttackRange() + actor.getTemplate().getCollisionRadius() + obj.getTemplate().getCollisionRadius();
					if (obj.isMoving())
					{
						dist2 -= 70;
					}
				}
				catch (NullPointerException e)
				{
					continue;
				}
				
				if (dist2 <= range)
				{
					actor.addDamageHate(obj, 0, mostHate != null ? actor.getHating(mostHate) : 2000);
					actor.setTarget(obj);
					setAttackTarget(obj);
					return;
				}
			}
		}
		
		if (!(actor instanceof Guard))
		{
			World.forEachVisibleObject(actor, Creature.class, obj ->
			{
				if ((obj == null) || !GeoEngine.getInstance().canSeeTarget(actor, obj) || obj.isDead() || (obj != mostHate) || (obj == actor) || (obj == getAttackTarget()))
				{
					return;
				}
				
				if (obj.isPlayer())
				{
					actor.addDamageHate(obj, 0, mostHate != null ? actor.getHating(mostHate) : 2000);
					actor.setTarget(obj);
					setAttackTarget(obj);
				}
				else if (obj.isAttackable())
				{
					if (actor.isChaos())
					{
						if (obj.asAttackable().isInMyClan(actor))
						{
							return;
						}
						
						actor.addDamageHate(obj, 0, mostHate != null ? actor.getHating(mostHate) : 2000);
						actor.setTarget(obj);
						setAttackTarget(obj);
					}
				}
				else if (obj.isSummon())
				{
					actor.addDamageHate(obj, 0, mostHate != null ? actor.getHating(mostHate) : 2000);
					actor.setTarget(obj);
					setAttackTarget(obj);
				}
			});
		}
	}
	
	/**
	 * Manage AI thinking actions of a Attackable.
	 */
	@Override
	public void notifyActionThink()
	{
		// Check if a thinking action is already in progress.
		if (_thinking)
		{
			return;
		}
		
		// Check if region and its neighbors are active.
		final WorldRegion region = _actor.getWorldRegion();
		if ((region == null) || !region.areNeighborsActive())
		{
			return;
		}
		
		// Check if the actor is all skills disabled.
		if (getActiveChar().isAllSkillsDisabled())
		{
			return;
		}
		
		// Start thinking action.
		_thinking = true;
		
		try
		{
			// Send raid minions back to their master when they were dragged out of the master territory.
			checkMinionLeash();
			
			// Manage AI thinks of a Attackable.
			switch (getIntention())
			{
				case ACTIVE:
				{
					thinkActive();
					break;
				}
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
			}
		}
		catch (Exception e)
		{
			// LOGGER.warning(getClass().getSimpleName() + ": " + getActor().getName() + " - onActionThink() failed!");
		}
		finally
		{
			// Stop thinking action.
			_thinking = false;
		}
	}
	
	/**
	 * Sends a raid minion back to its master when it was dragged out of the master territory.<br>
	 * A minion that is outside of the territory, while its master is still alive and while it is standing still, is teleported next to its master and loses all aggro.<br>
	 * The check runs every 120 seconds, the first one 120 seconds after the minion started to think.
	 */
	private void checkMinionLeash()
	{
		final Attackable npc = getActiveChar();
		if (!npc.isRaidMinion() || npc.isDead() || npc.isMoving())
		{
			return;
		}
		
		final long currentTime = System.currentTimeMillis();
		if (_minionLeashTime == 0)
		{
			_minionLeashTime = currentTime + MINION_LEASH_INTERVAL;
			return;
		}
		
		if (_minionLeashTime > currentTime)
		{
			return;
		}
		
		_minionLeashTime = currentTime + MINION_LEASH_INTERVAL;
		
		final Attackable master = npc.getLeader();
		if ((master == null) || master.isDead() || isInsideMasterTerritory(npc, master))
		{
			return;
		}
		
		npc.clearAggroList();
		npc.getAttackByList().clear();
		npc.teleToLocation(master, MINION_LEASH_OFFSET);
	}
	
	/**
	 * @param npc the raid minion.
	 * @param master the master of the raid minion.
	 * @return {@code true} if the minion is inside the territory of its master or if no territory could be resolved, {@code false} otherwise.
	 */
	private boolean isInsideMasterTerritory(Attackable npc, Attackable master)
	{
		final Spawn masterSpawn = master.getSpawn();
		if (masterSpawn != null)
		{
			final NpcSpawnTerritory territory = masterSpawn.getSpawnTerritory();
			if (territory != null)
			{
				return territory.isInsideZone(npc.getX(), npc.getY(), npc.getZ());
			}
		}
		
		final BossZone bossZone = ZoneManager.getInstance().getZone(master, BossZone.class);
		if (bossZone != null)
		{
			return bossZone.isInsideZone(npc.getX(), npc.getY(), npc.getZ());
		}
		
		return true;
	}
	
	/**
	 * Launch actions corresponding to the Action Attacked.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Init the attack : Calculate the attack timeout, Set the _globalAggro to 0, Add the attacker to the actor _aggroList</li>
	 * <li>Set the Creature movement type to run and send Server->Client packet ChangeMoveType to all others Player</li>
	 * <li>Set the Intention to ATTACK</li>
	 * </ul>
	 * @param attackerObj The WorldObject that attacks the actor
	 */
	@Override
	public void notifyActionAttacked(WorldObject attackerObj)
	{
		if (attackerObj == null)
		{
			return;
		}
		
		final Creature attacker = attackerObj.asCreature();
		if (attacker == null)
		{
			return;
		}
		
		final Attackable me = getActiveChar();
		
		// Calculate the attack timeout.
		_attackTimeout = MAX_ATTACK_TIMEOUT + GameTimeTaskManager.getInstance().getGameTicks();
		
		// Set the _globalAggro to 0 to permit attack even just after spawn.
		if (_globalAggro < 0)
		{
			_globalAggro = 0;
		}
		
		// Add the attacker to the _aggroList of the actor if not present.
		if (!me.isInAggroList(attacker))
		{
			me.addDamageHate(attacker, 0, 1);
		}
		
		// Set the Creature movement type to run and send Server->Client packet ChangeMoveType to all others Player.
		if (!me.isRunning())
		{
			me.setRunning();
		}
		
		if (!me.isCoreAIDisabled())
		{
			// Set the Intention to ATTACK.
			if (getIntention() != Intention.ATTACK)
			{
				setIntentionAttack(attacker);
			}
			else if (me.getMostHated() != getAttackTarget())
			{
				setIntentionAttack(attacker);
			}
		}
		
		if (me.isMonster())
		{
			Monster master = me.asMonster();
			if (master.hasMinions())
			{
				master.getMinionList().onAssist(me, attacker);
			}
			
			master = master.getLeader();
			if ((master != null) && master.hasMinions())
			{
				master.getMinionList().onAssist(me, attacker);
			}
		}
		
		// Handle all WorldObject of its Faction inside the Faction Range.
		final NpcTemplate template = me.getTemplate();
		final Set<Integer> clans = template.getClans();
		if ((clans != null) && !clans.isEmpty())
		{
			final int collision = template.getCollisionRadius();
			final int factionRange = template.getClanHelpRange() + collision;
			
			// Go through all WorldObject that belong to its faction.
			try
			{
				// Call friendly npcs for help only if this NPC was attacked by the target creature.
				final Creature finalTarget = attacker;
				boolean targetExistsInAttackByList = false;
				for (Creature reference : me.getAttackByList())
				{
					if (reference == finalTarget)
					{
						targetExistsInAttackByList = true;
						break;
					}
				}
				
				if (targetExistsInAttackByList)
				{
					World.forEachVisibleObjectInRange(me, Attackable.class, factionRange, nearby ->
					{
						// Don't call dead npcs, npcs without ai or npcs which are too far away.
						if (nearby.isDead() || !nearby.hasAI() || (Math.abs(finalTarget.getZ() - nearby.getZ()) > 600))
						{
							return;
						}
						
						// Don't call npcs spawned within the last 7 seconds.
						if ((System.currentTimeMillis() - nearby.getSpawnTime()) < 7000)
						{
							return;
						}
						
						// Don't call npcs who are already doing some action (e.g. attacking, casting).
						if ((nearby.getAI()._intention != Intention.IDLE) && (nearby.getAI()._intention != Intention.ACTIVE))
						{
							return;
						}
						
						// Don't call npcs who aren't in the same clan.
						final NpcTemplate nearbytemplate = nearby.getTemplate();
						if (!template.isClan(nearbytemplate.getClans()) || (nearbytemplate.hasIgnoreClanNpcIds() && nearbytemplate.getIgnoreClanNpcIds().contains(me.getId())))
						{
							return;
						}
						
						if (finalTarget.isPlayable())
						{
							// Dimensional Rift check.
							if ((me instanceof RiftInvader) && finalTarget.isInParty())
							{
								final Party party = finalTarget.getParty();
								if (party.isInDimensionalRift())
								{
									final byte riftType = party.getDimensionalRift().getType();
									final byte riftRoom = party.getDimensionalRift().getCurrentRoom();
									if (!DimensionalRiftManager.getInstance().getRoom(riftType, riftRoom).checkIfInZone(me.getX(), me.getY(), me.getZ()))
									{
										return;
									}
								}
							}
							
							// By default, when a faction member calls for help, attack the caller's attacker.
							if (GeoEngine.getInstance().canSeeTarget(nearby, finalTarget))
							{
								nearby.getAI().notifyActionAggression(finalTarget, 1);
							}
							
							if (EventDispatcher.getInstance().hasListener(EventType.ON_ATTACKABLE_FACTION_CALL, nearby))
							{
								EventDispatcher.getInstance().notifyEventAsync(new OnAttackableFactionCall(nearby, me, finalTarget.asPlayer(), finalTarget.isSummon()), nearby);
							}
						}
						else if (nearby.getAI()._intention != Intention.ATTACK)
						{
							if (GeoEngine.getInstance().canSeeTarget(nearby, finalTarget))
							{
								nearby.addDamageHate(finalTarget, 0, me.getHating(finalTarget));
								nearby.getAI().setIntentionAttack(finalTarget);
							}
						}
					});
				}
			}
			catch (NullPointerException e)
			{
				// LOGGER.warning(getClass().getSimpleName() + ": There has been a problem trying to think the attack!", e);
			}
		}
		
		super.notifyActionAttacked(attacker);
	}
	
	/**
	 * Launch actions corresponding to the Action Aggression.<br>
	 * <br>
	 * <b><u>Actions</u>:</b>
	 * <ul>
	 * <li>Add the target to the actor _aggroList or update hate if already present</li>
	 * <li>Set the actor Intention to ATTACK (if actor is GuardInstance check if it isn't too far from its home location)</li>
	 * </ul>
	 * @param targetObj the WorldObject that attacks
	 * @param aggro The value of hate to add to the actor against the target
	 */
	@Override
	public void notifyActionAggression(WorldObject targetObj, int aggro)
	{
		final Attackable me = getActiveChar();
		if ((targetObj == null) || me.isDead())
		{
			return;
		}
		
		final Creature target = targetObj.asCreature();
		if (target == null)
		{
			return;
		}
		
		// Add the target to the actor _aggroList or update hate if already present.
		me.addDamageHate(target, 0, aggro);
		
		// Set the actor AI Intention to ATTACK.
		if (getIntention() != Intention.ATTACK)
		{
			// Set the Creature movement type to run and send Server->Client packet ChangeMoveType to all others Player.
			if (!me.isRunning())
			{
				me.setRunning();
			}
			
			setIntentionAttack(target);
		}
		
		if (me.isMonster())
		{
			Monster master = me.asMonster();
			if (master.hasMinions())
			{
				master.getMinionList().onAssist(me, target);
			}
			
			master = master.getLeader();
			if ((master != null) && master.hasMinions())
			{
				master.getMinionList().onAssist(me, target);
			}
		}
	}
	
	/**
	 * Rolls the timeout of ATTACK that follows a spawn, three to seven seconds by default.
	 * SetAggressiveTime overrides it per npc: zero acquires on sight, a positive value gates for that many seconds.
	 */
	public void rollGlobalAggro()
	{
		final int aggressiveTime = getActiveChar().getTemplate().getParameters().getInt("SetAggressiveTime", -1);
		if (aggressiveTime == 0)
		{
			_globalAggro = 0;
		}
		else if (aggressiveTime > 0)
		{
			// The counter is tested on the tick that raises it, so it is set one past the wanted delay.
			_globalAggro = -(aggressiveTime + 1);
		}
		else
		{
			_globalAggro = -(Rnd.get(5) + 4);
		}
	}
	
	public void setGlobalAggro(int value)
	{
		_globalAggro = value;
	}
	
	public Attackable getActiveChar()
	{
		return _actor.asAttackable();
	}
	
	public int getFearTime()
	{
		return _fearTime;
	}
	
	public void setFearTime(int fearTime)
	{
		_fearTime = fearTime;
	}
	
	private boolean allowsParameterizedCast(Attackable caster, Skill skill, Creature target)
	{
		if ((caster == null) || (skill == null))
		{
			return true;
		}
		
		final StatSet params = caster.getTemplate() != null ? caster.getTemplate().getParameters() : null;
		if ((params == null) || params.isEmpty())
		{
			return true;
		}
		
		final int slot = findParameterizedSkillSlot(params, skill.getId());
		if (slot == 0)
		{
			return true;
		}
		
		return passesParameterizedGates(slot, params, caster, target);
	}
	
	private int findParameterizedSkillSlot(StatSet params, int skillId)
	{
		for (int slot = 1; slot <= MAX_PARAMETERIZED_SKILL_SLOTS; slot++)
		{
			final SkillHolder declared = params.getObject("Skill0" + slot + "_ID", SkillHolder.class);
			if ((declared != null) && (declared.getSkillId() == skillId))
			{
				return slot;
			}
		}
		return 0;
	}
	
	private boolean passesParameterizedGates(int slot, StatSet params, Attackable caster, Creature target)
	{
		final String prefix = "Skill0" + slot + "_";
		// Retail AI classes spell this parameter three different ways, all present in npcdata.
		final int probability = params.getInt(prefix + "Probablity", params.getInt(prefix + "Probability", params.getInt(prefix + "Prob", 0)));
		final boolean checksDistance = params.getInt(prefix + "Check_Dist", 0) == 1;
		final int highHp = params.getInt(prefix + "HighHP", 0);
		
		if (probability == 0)
		{
			final boolean hasAttackHint = (params.getInt(prefix + "AttackSplash", 0) == 1) || (params.getInt(prefix + "MainAttack", 0) == 1) || (params.getInt(prefix + "Target", 0) > 0);
			if (!checksDistance && (highHp == 0) && !hasAttackHint)
			{
				return false;
			}
		}
		else if (Rnd.get(SKILL_PROBABILITY_SCALE) >= probability)
		{
			return false;
		}
		
		if (checksDistance && (target != null))
		{
			final int distMin = params.getInt(prefix + "Dist_Min", 0);
			final int distMax = params.getInt(prefix + "Dist_Max", Integer.MAX_VALUE);
			final int distance = (int) caster.calculateDistance2D(target);
			if ((distance < distMin) || (distance > distMax))
			{
				return false;
			}
		}
		
		if (highHp > 0)
		{
			final int hpTarget = params.getInt(prefix + "HPTarget", 0);
			final Creature hpSource = (hpTarget == 1) ? caster : target;
			if (hpSource == null)
			{
				return false;
			}
			final double hpPercent = (hpSource.getCurrentHp() * 100.0) / hpSource.getMaxHp();
			if (hpPercent <= highHp)
			{
				return false;
			}
		}
		
		return true;
	}
}
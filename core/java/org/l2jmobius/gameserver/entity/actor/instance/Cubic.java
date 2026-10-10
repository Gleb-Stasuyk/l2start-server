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
package org.l2jmobius.gameserver.entity.actor.instance;

import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.xml.CubicData;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.tasks.cubics.CubicAction;
import org.l2jmobius.gameserver.entity.actor.tasks.cubics.CubicDisappear;
import org.l2jmobius.gameserver.entity.actor.tasks.cubics.CubicHeal;
import org.l2jmobius.gameserver.entity.actor.templates.CubicTemplate;
import org.l2jmobius.gameserver.entity.cubic.CubicSelectionMode;
import org.l2jmobius.gameserver.entity.cubic.CubicSkill;
import org.l2jmobius.gameserver.entity.cubic.CubicTargetType;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.DuelManager;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.EffectScope;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.stats.Formulas;
import org.l2jmobius.gameserver.mechanics.stats.Stat;
import org.l2jmobius.gameserver.network.SystemMessageId;

/**
 * @author UnAfraid, Mobius, BazookaRpm
 */
public class Cubic
{
	private static final Logger LOGGER = Logger.getLogger(Cubic.class.getName());
	
	// Type of Cubics
	public static final int STORM_CUBIC = 1;
	public static final int VAMPIRIC_CUBIC = 2;
	public static final int LIFE_CUBIC = 3;
	public static final int VIPER_CUBIC = 4;
	public static final int POLTERGEIST_CUBIC = 5;
	public static final int BINDING_CUBIC = 6;
	public static final int AQUA_CUBIC = 7;
	public static final int SPARK_CUBIC = 8;
	public static final int ATTRACT_CUBIC = 9;
	
	// Max range of cubic skills.
	// TODO: Check/fix the max range
	public static final int MAX_MAGIC_RANGE = 900;
	
	// Cubic skills
	public static final int SKILL_CUBIC_HEAL = 4051;
	
	private final Player _owner;
	private Creature _target;
	
	private final int _cubicId;
	private final CubicTemplate _template;
	private final int _cubicPower;
	private final int _cubicDelay;
	private final int _cubicSkillChance;
	private final int _cubicMaxCount;
	private final boolean _givenByOther;
	
	private final List<CubicSkill> _cubicSkills;
	
	private Future<?> _disappearTask;
	private Future<?> _actionTask;
	
	public Cubic(Player owner, int cubicId, int level, int cubicPower, int cubicDelay, int cubicSkillChance, int cubicMaxCount, int cubicDuration, boolean givenByOther)
	{
		_owner = owner;
		_cubicId = cubicId;
		_template = CubicData.getInstance().getCubicTemplate(cubicId, level);
		if (_template == null)
		{
			_cubicPower = cubicPower;
			_cubicDelay = cubicDelay * 1000;
			_cubicSkillChance = cubicSkillChance;
			_cubicMaxCount = cubicMaxCount;
			_cubicSkills = java.util.Collections.emptyList();
			LOGGER.warning("Missing cubic template. cubicId: " + cubicId + " level: " + level + " ownerId: " + owner.getObjectId());
		}
		else
		{
			_cubicPower = cubicPower > 0 ? cubicPower : _template.getBaseMAtk();
			_cubicDelay = (cubicDelay > 0 ? cubicDelay : _template.getDelay()) * 1000;
			_cubicSkillChance = cubicSkillChance;
			_cubicMaxCount = cubicMaxCount > -1 ? cubicMaxCount : _template.getMaxCount();
			_cubicSkills = _template.getCubicSkills();
		}
		_givenByOther = givenByOther;
		
		final int duration = cubicDuration > 0 ? cubicDuration : _template != null ? _template.getDuration() : cubicDuration;
		_disappearTask = ThreadPool.schedule(new CubicDisappear(this), duration * 1000);
		if ((_template != null) && (_template.getTargetType() == CubicTargetType.HEAL))
		{
			doAction();
		}
	}
	
	public void doAction()
	{
		if ((_actionTask == null) && (_template != null) && !_cubicSkills.isEmpty())
		{
			synchronized (this)
			{
				if (_actionTask == null)
				{
					if (_template.getTargetType() == CubicTargetType.HEAL)
					{
						_actionTask = ThreadPool.scheduleAtFixedRate(new CubicHeal(this), 0, _cubicDelay);
					}
					else
					{
						_actionTask = ThreadPool.scheduleAtFixedRate(new CubicAction(this, _cubicSkillChance), 0, _cubicDelay);
					}
				}
			}
		}
	}
	
	public int getId()
	{
		return _cubicId;
	}
	
	public Player getOwner()
	{
		return _owner;
	}
	
	public int getCubicPower()
	{
		return _cubicPower;
	}
	
	public Creature getTarget()
	{
		return _target;
	}
	
	public void setTarget(Creature target)
	{
		_target = target;
	}
	
	public CubicTemplate getTemplate()
	{
		return _template;
	}
	
	public List<CubicSkill> getCubicSkills()
	{
		return _cubicSkills;
	}
	
	public CubicSkill getCubicSkill()
	{
		if (getNormalCubicSkillCount() == 0)
		{
			return null;
		}
		
		final CubicSelectionMode selectionMode = _template != null ? _template.getSelectionMode() : CubicSelectionMode.RANDOM;
		switch (selectionMode)
		{
			case ORDERED:
			{
				return getOrderedCubicSkill();
			}
			case WEIGHTED:
			{
				return getWeightedCubicSkill();
			}
			case RANDOM:
			default:
			{
				return getRandomCubicSkill();
			}
		}
	}
	
	private int getNormalCubicSkillCount()
	{
		int count = 0;
		for (CubicSkill cubicSkill : _cubicSkills)
		{
			if (!cubicSkill.isPriority())
			{
				count++;
			}
		}
		
		return count;
	}
	
	private CubicSkill getRandomCubicSkill()
	{
		int index = Rnd.get(getNormalCubicSkillCount());
		for (CubicSkill cubicSkill : _cubicSkills)
		{
			if (cubicSkill.isPriority())
			{
				continue;
			}
			
			if (index-- == 0)
			{
				return cubicSkill;
			}
		}
		
		return null;
	}
	
	private CubicSkill getOrderedCubicSkill()
	{
		for (CubicSkill cubicSkill : _cubicSkills)
		{
			if (cubicSkill.isPriority())
			{
				continue;
			}
			
			if ((cubicSkill.getTriggerRate() >= 100) || (Rnd.get(100) < cubicSkill.getTriggerRate()))
			{
				return cubicSkill;
			}
		}
		
		return null;
	}
	
	private CubicSkill getWeightedCubicSkill()
	{
		int totalRate = 0;
		for (CubicSkill cubicSkill : _cubicSkills)
		{
			if (!cubicSkill.isPriority() && (cubicSkill.getTriggerRate() > 0))
			{
				totalRate += cubicSkill.getTriggerRate();
			}
		}
		
		if (totalRate <= 0)
		{
			return getRandomCubicSkill();
		}
		
		int chance = Rnd.get(totalRate);
		for (CubicSkill cubicSkill : _cubicSkills)
		{
			if (cubicSkill.isPriority() || (cubicSkill.getTriggerRate() <= 0))
			{
				continue;
			}
			
			chance -= cubicSkill.getTriggerRate();
			if (chance < 0)
			{
				return cubicSkill;
			}
		}
		
		return getRandomCubicSkill();
	}
	
	public int getCubicMaxCount()
	{
		return _cubicMaxCount;
	}
	
	public void stopAction()
	{
		_target = null;
		if (_actionTask != null)
		{
			_actionTask.cancel(true);
			_actionTask = null;
		}
	}
	
	public void cancelDisappear()
	{
		if (_disappearTask != null)
		{
			_disappearTask.cancel(true);
			_disappearTask = null;
		}
	}
	
	/** this sets the enemy target for a cubic */
	public void getCubicTarget()
	{
		try
		{
			_target = null;
			final WorldObject ownerTarget = _owner.getTarget();
			if (ownerTarget == null)
			{
				return;
			}
			
			// Custom event targeting
			if (_owner.isOnEvent())
			{
				final Player target = ownerTarget.asPlayer();
				if ((target != null) && ((_owner.getTeam() != target.getTeam()) || _owner.isOnSoloEvent()) && !(target.isDead()))
				{
					_target = ownerTarget.asCreature();
				}
				return;
			}
			
			// Duel targeting
			if (_owner.isInDuel())
			{
				final Player playerA = DuelManager.getInstance().getDuel(_owner.getDuelId()).getPlayerA();
				final Player playerB = DuelManager.getInstance().getDuel(_owner.getDuelId()).getPlayerB();
				if (DuelManager.getInstance().getDuel(_owner.getDuelId()).isPartyDuel())
				{
					final Party partyA = playerA.getParty();
					final Party partyB = playerB.getParty();
					Party partyEnemy = null;
					if (partyA != null)
					{
						if (partyA.getMembers().contains(_owner))
						{
							if (partyB != null)
							{
								partyEnemy = partyB;
							}
							else
							{
								_target = playerB;
							}
						}
						else
						{
							partyEnemy = partyA;
						}
					}
					else
					{
						if (playerA == _owner)
						{
							if (partyB != null)
							{
								partyEnemy = partyB;
							}
							else
							{
								_target = playerB;
							}
						}
						else
						{
							_target = playerA;
						}
					}
					
					if (((_target == playerA) || (_target == playerB)) && (_target == ownerTarget))
					{
						return;
					}
					
					if (partyEnemy != null)
					{
						if (partyEnemy.getMembers().contains(ownerTarget))
						{
							_target = ownerTarget.asCreature();
						}
						return;
					}
				}
				
				if ((playerA != _owner) && (ownerTarget == playerA))
				{
					_target = playerA;
					return;
				}
				
				if ((playerB != _owner) && (ownerTarget == playerB))
				{
					_target = playerB;
					return;
				}
				
				_target = null;
				return;
			}
			
			// Olympiad targeting
			if (_owner.isInOlympiadMode())
			{
				if (_owner.isOlympiadStart() && ownerTarget.isPlayable())
				{
					final Player targetPlayer = ownerTarget.asPlayer();
					if ((targetPlayer != null) && (targetPlayer.getOlympiadGameId() == _owner.getOlympiadGameId()) && (targetPlayer.getOlympiadSide() != _owner.getOlympiadSide()))
					{
						_target = ownerTarget.asCreature();
					}
				}
				return;
			}
			
			// test owners target if it is valid then use it
			if (ownerTarget.isCreature() && (ownerTarget != _owner.getSummon()) && (ownerTarget != _owner))
			{
				// target mob which has aggro on you or your summon
				if (ownerTarget.isAttackable())
				{
					final Attackable attackable = ownerTarget.asAttackable();
					if (attackable.isInAggroList(_owner) && !attackable.isDead())
					{
						_target = ownerTarget.asCreature();
						return;
					}
					
					if (_owner.hasSummon() && attackable.isInAggroList(_owner.getSummon()) && !attackable.isDead())
					{
						_target = ownerTarget.asCreature();
						return;
					}
				}
				
				// get target in pvp or in siege
				Player enemy = null;
				if (((_owner.getPvpFlag() > 0) && !_owner.isInsideZone(ZoneId.PEACE)) || _owner.isInsideZone(ZoneId.PVP))
				{
					if (!ownerTarget.asCreature().isDead())
					{
						enemy = ownerTarget.asPlayer();
					}
					
					if (enemy != null)
					{
						boolean targetIt = true;
						if (_owner.getParty() != null)
						{
							if (_owner.getParty().getMembers().contains(enemy))
							{
								targetIt = false;
							}
							else if ((_owner.getParty().getCommandChannel() != null) && _owner.getParty().getCommandChannel().getMembers().contains(enemy))
							{
								targetIt = false;
							}
						}
						
						if ((_owner.getClan() != null) && !_owner.isInsideZone(ZoneId.PVP))
						{
							if (_owner.getClan().isMember(enemy.getObjectId()))
							{
								targetIt = false;
							}
							
							if ((_owner.getAllyId() > 0) && (enemy.getAllyId() > 0) && (_owner.getAllyId() == enemy.getAllyId()))
							{
								targetIt = false;
							}
						}
						
						if ((enemy.getPvpFlag() == 0) && !enemy.isInsideZone(ZoneId.PVP))
						{
							targetIt = false;
						}
						
						if (enemy.isInsideZone(ZoneId.PEACE))
						{
							targetIt = false;
						}
						
						if ((_owner.getSiegeState() > 0) && (_owner.getSiegeState() == enemy.getSiegeState()))
						{
							targetIt = false;
						}
						
						if (!enemy.isSpawned())
						{
							targetIt = false;
						}
						
						if (targetIt)
						{
							_target = enemy;
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "Cubic target selection failed. cubicId: " + _cubicId + " ownerId: " + _owner.getObjectId(), e);
		}
	}
	
	private void applyCubicEffects(Skill skill, Creature target)
	{
		if ((target == null) || ((target != _owner) && skill.hasNegativeEffect() && (target.isInvul() || (_owner.isGM() && !_owner.getAccessLevel().canGiveDamage()))))
		{
			return;
		}
		
		if (target.isInvulAgainst(skill.getId(), skill.getLevel()))
		{
			return;
		}
		
		final boolean addContinuousEffects = skill.isToggle() || skill.isContinuous();
		final BuffInfo info = new BuffInfo(_owner, target, skill);
		skill.applyEffectScope(EffectScope.GENERAL, info, true, addContinuousEffects);
		final EffectScope pvpOrPveEffectScope = _owner.isPlayable() && target.isAttackable() ? EffectScope.PVE : _owner.isPlayable() && target.isPlayable() ? EffectScope.PVP : null;
		skill.applyEffectScope(pvpOrPveEffectScope, info, true, addContinuousEffects);
		skill.applyEffectScope(EffectScope.CHANNELING, info, true, addContinuousEffects);
		if (addContinuousEffects)
		{
			target.getEffectList().add(info);
		}
	}
	
	public void useCubicContinuous(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if ((target == null) || target.isDead())
			{
				continue;
			}
			
			if (skill.hasNegativeEffect())
			{
				final boolean acted = Formulas.calcCubicSkillSuccess(this, target, skill, Formulas.SHIELD_DEFENSE_FAILED);
				if (!acted)
				{
					_owner.sendPacket(SystemMessageId.YOUR_ATTACK_HAS_FAILED);
					continue;
				}
			}
			
			// Apply effects
			applyCubicEffects(skill, target);
			
			// If this is a negative effect skill notify the duel manager, so it can be removed after the duel (player & target must be in the same duel).
			if (target.isPlayer())
			{
				final Player player = target.asPlayer();
				if (player.isInDuel() && skill.hasNegativeEffect() && (_owner.getDuelId() == player.getDuelId()))
				{
					DuelManager.getInstance().onBuff(player, skill);
				}
			}
		}
	}
	
	/**
	 * @param skill
	 * @param targets
	 */
	public void useCubicMdam(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if (target == null)
			{
				continue;
			}
			
			if (target.isAlikeDead())
			{
				if (target.isPlayer() && PlayerConfig.FAKE_DEATH_DAMAGE_STAND)
				{
					target.stopFakeDeath(true);
				}
				else
				{
					continue;
				}
			}
			
			final boolean mcrit = Formulas.calcMCrit(_owner.getMCriticalHit(target, skill));
			final byte shld = Formulas.calcShldUse(_owner, target, skill);
			int damage = (int) Formulas.calcMagicDam(this, target, skill, mcrit, shld);
			if (damage > 0)
			{
				// Manage attack or cast break of the target (calculating rate, sending message...).
				if (!target.isRaid() && Formulas.calcAtkBreak(target, damage))
				{
					target.breakAttack();
					target.breakCast();
				}
				
				// Shield Deflect Magic: If target is reflecting the skill then no damage is done.
				if (target.getStat().calcStat(Stat.VENGEANCE_SKILL_MAGIC_DAMAGE, 0, target, skill) > Rnd.get(100))
				{
					damage = 0;
				}
				else
				{
					_owner.sendDamageMessage(target, damage, mcrit, false, false);
					target.reduceCurrentHp(damage, _owner, skill);
				}
			}
		}
	}
	
	public void useCubicDrain(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if (target.isAlikeDead())
			{
				continue;
			}
			
			final boolean mcrit = Formulas.calcMCrit(_owner.getMCriticalHit(target, skill));
			final byte shld = Formulas.calcShldUse(_owner, target, skill);
			final int damage = (int) Formulas.calcMagicDam(this, target, skill, mcrit, shld);
			
			// TODO: Unhardcode fixed value
			final double hpAdd = (0.4 * damage);
			final Player owner = _owner;
			final double hp = ((owner.getCurrentHp() + hpAdd) > owner.getMaxHp() ? owner.getMaxHp() : (owner.getCurrentHp() + hpAdd));
			owner.setCurrentHp(hp);
			
			// Check to see if we should damage the target.
			if ((damage > 0) && !target.isDead())
			{
				target.reduceCurrentHp(damage, _owner, skill);
				
				// Manage attack or cast break of the target (calculating rate, sending message...).
				if (!target.isRaid() && Formulas.calcAtkBreak(target, damage))
				{
					target.breakAttack();
					target.breakCast();
				}
				
				owner.sendDamageMessage(target, damage, mcrit, false, false);
			}
		}
	}
	
	public void useCubicDisabler(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if ((target == null) || target.isDead())
			{
				continue;
			}
			
			if (skill.hasEffectType(EffectType.STUN, EffectType.PARALYZE, EffectType.ROOT) && Formulas.calcCubicSkillSuccess(this, target, skill, Formulas.SHIELD_DEFENSE_FAILED))
			{
				// Apply effects
				applyCubicEffects(skill, target);
				
				// If this is a negative effect skill notify the duel manager, so it can be removed after the duel (player & target must be in the same duel).
				if (target.isPlayer())
				{
					final Player player = target.asPlayer();
					if (player.isInDuel() && skill.hasNegativeEffect() && (_owner.getDuelId() == player.getDuelId()))
					{
						DuelManager.getInstance().onBuff(player, skill);
					}
				}
			}
			
			if (skill.hasEffectType(EffectType.AGGRESSION) && Formulas.calcCubicSkillSuccess(this, target, skill, Formulas.SHIELD_DEFENSE_FAILED))
			{
				if (target.isAttackable())
				{
					target.getAI().notifyActionAggression(_owner, (int) ((150 * skill.getPower()) / (target.getLevel() + 7)));
				}
				
				// Apply effects
				applyCubicEffects(skill, target);
			}
		}
	}
	
	/**
	 * @param owner
	 * @param target
	 * @return true if the target is inside of the owner's max Cubic range
	 */
	public static boolean isInCubicRange(Creature owner, Creature target)
	{
		if ((owner == null) || (target == null))
		{
			return false;
		}
		
		// Temporary range check until real behavior of cubics is known/coded.
		final long range = MAX_MAGIC_RANGE;
		final long x = owner.getX() - target.getX();
		final long y = owner.getY() - target.getY();
		final long z = owner.getZ() - target.getZ();
		return (((x * x) + (y * y) + (z * z)) <= (range * range));
	}
	
	/** this sets the friendly target for a cubic */
	public void cubicTargetForHeal()
	{
		Creature target = null;
		double percentleft = 100.0;
		Party party = _owner.getParty();
		
		// if owner is in a duel but not in a party duel, then it is the same as he does not have a party
		if (_owner.isInDuel() && !DuelManager.getInstance().getDuel(_owner.getDuelId()).isPartyDuel())
		{
			party = null;
		}
		
		if ((party != null) && !_owner.isInOlympiadMode())
		{
			// Get all visible objects in a spheric area near the Creature.
			// Get a list of Party Members.
			for (Creature partyMember : party.getMembers())
			{
				// if party member not dead, check if he is in cast range of heal cubic and member is in cubic casting range, check if he need heal and if he have the lowest HP
				if (!partyMember.isDead() && isInCubicRange(_owner, partyMember) && (partyMember.getCurrentHp() < partyMember.getMaxHp()) && (percentleft > (partyMember.getCurrentHp() / partyMember.getMaxHp())))
				{
					percentleft = (partyMember.getCurrentHp() / partyMember.getMaxHp());
					target = partyMember;
				}
				
				final Player player = partyMember.asPlayer();
				if (player != null)
				{
					final Summon summon = player.getSummon();
					if (summon != null)
					{
						if (summon.isDead())
						{
							continue;
						}
						
						// If party member's pet not dead, check if it is in cast range of heal cubic.
						if (!isInCubicRange(_owner, summon))
						{
							continue;
						}
						
						// member's pet is in cubic casting range, check if he need heal and if he have the lowest HP
						if ((summon.getCurrentHp() < summon.getMaxHp()) && (percentleft > (summon.getCurrentHp() / summon.getMaxHp())))
						{
							percentleft = (summon.getCurrentHp() / summon.getMaxHp());
							target = summon;
						}
					}
				}
			}
		}
		else
		{
			if (_owner.getCurrentHp() < _owner.getMaxHp())
			{
				percentleft = (_owner.getCurrentHp() / _owner.getMaxHp());
				target = _owner;
			}
			
			if (_owner.hasSummon() && !_owner.getSummon().isDead() && (_owner.getSummon().getCurrentHp() < _owner.getSummon().getMaxHp()) && (percentleft > (_owner.getSummon().getCurrentHp() / _owner.getSummon().getMaxHp())) && isInCubicRange(_owner, _owner.getSummon()))
			{
				target = _owner.getSummon();
			}
		}
		
		_target = target;
	}
	
	public boolean givenByOther()
	{
		return _givenByOther;
	}
}

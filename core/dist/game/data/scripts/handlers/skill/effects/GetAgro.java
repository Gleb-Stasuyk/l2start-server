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
package handlers.skill.effects;

import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.mechanics.conditions.Condition;
import org.l2jmobius.gameserver.mechanics.effects.AbstractEffect;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * Get Agro effect implementation.
 * @author Adry_85, Mobius, Bazookarpm
 */
public class GetAgro extends AbstractEffect
{
	private final int _chance;
	private final boolean _affectPlayers;
	private final int _range;
	
	public GetAgro(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
		
		_chance = params.getInt("chance", 100);
		_affectPlayers = params.getBoolean("affectPlayers", false);
		_range = params.getInt("range", 0);
	}
	
	@Override
	public EffectType getEffectType()
	{
		return EffectType.AGGRESSION;
	}
	
	@Override
	public boolean calcSuccess(Creature effector, Creature effected, Skill skill)
	{
		return Rnd.get(100) < _chance;
	}
	
	@Override
	public boolean isInstant()
	{
		return true;
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		if (effected == null)
		{
			return;
		}
		
		// Handle attackable (mobs) targets.
		if (effected.isAttackable())
		{
			effected.getAI().setIntentionAttack(effector);
			
			// Monsters from the same clan should assist.
			final NpcTemplate template = effected.asAttackable().getTemplate();
			final Set<Integer> clans = template.getClans();
			if (clans != null)
			{
				World.forEachVisibleObjectInRange(effected, Attackable.class, template.getClanHelpRange(), nearby ->
				{
					if (!nearby.isMovementDisabled() && nearby.getTemplate().isClan(clans))
					{
						nearby.addDamageHate(effector, 1, 200);
						nearby.getAI().setIntentionAttack(effector);
						nearby.setRunning();
					}
				});
			}
		}
		// Handle player targets if enabled.
		else if (_affectPlayers && effected.isPlayer())
		{
			// Make the target player attack the caster.
			final Player targetPlayer = effected.asPlayer();
			final Player casterPlayer = effector.isPlayer() ? effector.asPlayer() : null;
			targetPlayer.getAI().setIntentionAttack(effector);
			
			// Make nearby players attack the caster if range is specified.
			if ((_range > 0) && (casterPlayer != null))
			{
				World.forEachVisibleObjectInRange(targetPlayer, Player.class, _range, nearbyPlayer ->
				{
					if (!nearbyPlayer.equals(casterPlayer) && !nearbyPlayer.isMovementDisabled())
					{
						final Party nearbyPlayerParty = nearbyPlayer.getParty();
						final Party casterPlayerParty = casterPlayer.getParty();
						final Clan nearbyPlayerClan = nearbyPlayer.getClan();
						final Clan casterPlayerClan = casterPlayer.getClan();
						final boolean sameParty = (nearbyPlayerParty != null) && (casterPlayerParty != null) && nearbyPlayerParty.equals(casterPlayerParty);
						final boolean sameClan = (nearbyPlayerClan != null) && (casterPlayerClan != null) && nearbyPlayerClan.equals(casterPlayerClan);
						if (!sameParty && !sameClan)
						{
							nearbyPlayer.getAI().setIntentionAttack(effector);
						}
					}
				});
			}
		}
	}
}

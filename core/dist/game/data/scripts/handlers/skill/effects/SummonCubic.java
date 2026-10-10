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
package handlers.skill.effects;

import org.l2jmobius.gameserver.data.xml.CubicData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Cubic;
import org.l2jmobius.gameserver.entity.actor.templates.CubicTemplate;
import org.l2jmobius.gameserver.mechanics.conditions.Condition;
import org.l2jmobius.gameserver.mechanics.effects.AbstractEffect;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * Summons a cubic from XML template data.<br>
 * Keeps Interlude skill parameters as runtime overrides.
 * @author Zoey76
 */
public class SummonCubic extends AbstractEffect
{
	private final int _cubicId;
	private final int _cubicLevel;
	private final int _cubicPower;
	private final int _cubicDuration;
	private final int _cubicDelay;
	private final int _cubicMaxCount;
	private final int _cubicSkillChance;
	
	public SummonCubic(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
		_cubicId = params.getInt("cubicId", -1);
		_cubicLevel = params.getInt("cubicLvl", params.getInt("cubicSkillLevel", 0));
		_cubicPower = params.getInt("cubicPower", 0);
		_cubicDuration = params.getInt("cubicDuration", 0);
		_cubicDelay = params.getInt("cubicDelay", 0);
		_cubicMaxCount = params.getInt("cubicMaxCount", -1);
		_cubicSkillChance = params.getInt("cubicSkillChance", 0);
	}
	
	@Override
	public boolean isInstant()
	{
		return true;
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		if ((effected == null) || !effected.isPlayer() || effected.isAlikeDead())
		{
			return;
		}
		
		if (_cubicId < 0)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Invalid cubic id: " + _cubicId + " skillId: " + skill.getId());
			return;
		}
		
		final Player player = effected.asPlayer();
		if (player.inObserverMode() || player.isMounted())
		{
			return;
		}
		
		int cubicTemplateLevel = _cubicLevel > 0 ? _cubicLevel : skill.getLevel();
		if ((_cubicLevel <= 0) && (cubicTemplateLevel > 100))
		{
			cubicTemplateLevel = ((skill.getLevel() - 100) / 7) + 8;
		}
		
		final CubicTemplate template = CubicData.getInstance().getCubicTemplate(_cubicId, cubicTemplateLevel);
		if (template == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Missing cubic template. cubicId: " + _cubicId + " level: " + cubicTemplateLevel + " skillId: " + skill.getId());
			return;
		}
		
		final Cubic cubic = player.getCubicById(_cubicId);
		if (cubic != null)
		{
			player.removeCubic(_cubicId);
		}
		else
		{
			final int allowedCubicCount = Math.max(1, player.getStat().getMaxCubicCount());
			while (player.getCubics().size() >= allowedCubicCount)
			{
				if (player.removeFirstCubic() == null)
				{
					break;
				}
			}
		}
		
		player.addCubic(_cubicId, template.getLevel(), _cubicPower, _cubicDelay, _cubicSkillChance, _cubicMaxCount, _cubicDuration, effected != effector);
		player.broadcastUserInfo();
	}
}

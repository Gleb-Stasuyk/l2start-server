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
package org.l2jmobius.gameserver.data.xml;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.entity.actor.templates.CubicTemplate;
import org.l2jmobius.gameserver.entity.cubic.CubicSkill;
import org.l2jmobius.gameserver.entity.cubic.ICubicConditionHolder;
import org.l2jmobius.gameserver.entity.cubic.conditions.HealthCondition;
import org.l2jmobius.gameserver.entity.cubic.conditions.HpCondition;
import org.l2jmobius.gameserver.entity.cubic.conditions.HpCondition.HpConditionType;
import org.l2jmobius.gameserver.entity.cubic.conditions.RangeCondition;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * Loads cubic templates from the datapack XML definitions.<br>
 * Provides cubic definitions grouped by cubic ID and cubic level.
 * <ul>
 * <li>Parses cubic base data.</li>
 * <li>Parses cubic skills and conditions.</li>
 * <li>Serves templates to SummonCubic and Cubic runtime.</li>
 * </ul>
 * @author UnAfraid
 */
public class CubicData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(CubicData.class.getName());
	
	private volatile Map<Integer, Map<Integer, CubicTemplate>> _cubics = Collections.emptyMap();
	private volatile Map<Integer, Map<Integer, CubicTemplate>> _loadingCubics;
	
	protected CubicData()
	{
		load();
	}
	
	@Override
	public synchronized void load()
	{
		final Map<Integer, Map<Integer, CubicTemplate>> loadedCubics = new ConcurrentHashMap<>();
		_loadingCubics = loadedCubics;
		try
		{
			parseDatapackDirectory("data/stats/cubics", true);
			int count = 0;
			for (Map<Integer, CubicTemplate> levels : loadedCubics.values())
			{
				count += levels.size();
			}
			
			if (count == 0)
			{
				LOGGER.warning(getClass().getSimpleName() + ": No cubic templates loaded. Keeping previous data.");
				return;
			}
			
			_cubics = loadedCubics;
			LOGGER.info(getClass().getSimpleName() + ": Loaded " + count + " cubic templates.");
		}
		finally
		{
			_loadingCubics = null;
		}
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "cubic", cubicNode -> parseTemplate(cubicNode, new CubicTemplate(new StatSet(parseAttributes(cubicNode))))));
	}
	
	private void parseTemplate(Node cubicNode, CubicTemplate template)
	{
		forEach(cubicNode, IXmlReader::isNode, innerNode ->
		{
			switch (innerNode.getNodeName())
			{
				case "conditions":
				{
					parseConditions(innerNode, template, template);
					break;
				}
				case "skills":
				{
					parseSkills(innerNode, template);
					break;
				}
			}
		});
		
		final Map<Integer, Map<Integer, CubicTemplate>> loadingCubics = _loadingCubics;
		if (loadingCubics == null)
		{
			LOGGER.warning("Skipping cubic template outside loading context. cubicId: " + template.getId() + " level: " + template.getLevel());
			return;
		}
		
		loadingCubics.computeIfAbsent(template.getId(), key -> new ConcurrentHashMap<>()).put(template.getLevel(), template);
	}
	
	private void parseConditions(Node conditionsNode, CubicTemplate template, ICubicConditionHolder holder)
	{
		forEach(conditionsNode, IXmlReader::isNode, conditionNode ->
		{
			switch (conditionNode.getNodeName())
			{
				case "hp":
				{
					final HpConditionType type = parseEnum(conditionNode.getAttributes(), HpConditionType.class, "type");
					holder.addCondition(new HpCondition(type, parseInteger(conditionNode.getAttributes(), "percent")));
					break;
				}
				case "range":
				{
					holder.addCondition(new RangeCondition(parseInteger(conditionNode.getAttributes(), "value")));
					break;
				}
				case "healthPercent":
				{
					final int min = parseInteger(conditionNode.getAttributes(), "min");
					final int max = parseInteger(conditionNode.getAttributes(), "max");
					holder.addCondition(new HealthCondition(min, max));
					break;
				}
				default:
				{
					LOGGER.warning("Unsupported cubic condition: " + conditionNode.getNodeName() + " cubicId: " + template.getId() + " level: " + template.getLevel());
					break;
				}
			}
		});
	}
	
	private void parseSkills(Node skillsNode, CubicTemplate template)
	{
		forEach(skillsNode, "skill", skillNode ->
		{
			final CubicSkill skill = new CubicSkill(new StatSet(parseAttributes(skillNode)));
			forEach(skillNode, "conditions", conditionsNode -> parseConditions(conditionsNode, template, skill));
			template.addCubicSkill(skill);
		});
	}
	
	/**
	 * Gets a cubic template by cubic ID and level.
	 * @param id
	 * @param level
	 * @return The cubic template or {@code null}.
	 */
	public CubicTemplate getCubicTemplate(int id, int level)
	{
		return _cubics.getOrDefault(id, Collections.emptyMap()).get(level);
	}
	
	public static CubicData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final CubicData INSTANCE = new CubicData();
	}
}

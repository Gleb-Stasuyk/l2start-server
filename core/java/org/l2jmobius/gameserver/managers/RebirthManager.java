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
package org.l2jmobius.gameserver.managers;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.custom.RebirthConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.SocialAction;

/**
 * Manages the complete rebirth system lifecycle for players.<br>
 * Handles rebirth requests, skill selection, persistence, rewards, visual effects and related UI windows.
 * <ul>
 * <li>Processes rebirth validation and rebirth execution.</li>
 * <li>Loads, grants, resets and persists selected rebirth skills.</li>
 * <li>Builds dynamic HTML windows for rebirth menus and skill previews.</li>
 * <li>Applies configured announcements, screen messages and visual effects.</li>
 * </ul>
 * @author BazookaRpm
 */
public final class RebirthManager
{
	// Logger.
	private static final Logger LOGGER = Logger.getLogger(RebirthManager.class.getName());
	
	// Constants.
	private static final int SKILLS_PER_ROW = 3;
	private static final int SKILL_ICON_PADDING_THRESHOLD = 1000;
	
	private static final String REBIRTH_BUSY_MESSAGE = "A rebirth operation is already in progress.";
	private static final String SELECT_REBIRTH_COUNT = "SELECT rebirthCount FROM rebirth_system WHERE charId=?";
	private static final String INSERT_FIRST_REBIRTH = "INSERT INTO rebirth_system (charId, rebirthCount) VALUES (?,1)";
	private static final String UPDATE_REBIRTH_COUNT = "UPDATE rebirth_system SET rebirthCount=? WHERE charId=?";
	private static final String SELECT_SELECTED_SKILLS = "SELECT selectedSkills FROM rebirth_system WHERE charId=?";
	private static final String UPDATE_SELECTED_SKILLS = "UPDATE rebirth_system SET selectedSkills=? WHERE charId=?";
	private static final String CLASS_LIST_FILE = "data/stats/players/classList.xml";
	
	private static final Map<Integer, Integer> ROOT_CLASS_IDS = new ConcurrentHashMap<>();
	private static volatile boolean ROOT_CLASS_IDS_LOADED = false;
	
	// Operation guards.
	private static final Set<Integer> REBIRTH_OPERATIONS_IN_PROGRESS = ConcurrentHashMap.newKeySet();
	private static final Set<Integer> REBIRTH_SKILL_OPERATIONS_IN_PROGRESS = ConcurrentHashMap.newKeySet();
	
	/**
	 * Creates the rebirth manager singleton instance.
	 */
	protected RebirthManager()
	{
	}
	
	private boolean isStartingClassSkill(Player player, Skill skill)
	{
		if ((player == null) || (skill == null))
		{
			return false;
		}
		
		final int rootClassId = getRootClassId(player.getBaseClass());
		final PlayerClass rootClass = PlayerClass.getPlayerClass(rootClassId);
		if (rootClass == null)
		{
			return false;
		}
		
		return isSkillInTree(skill, SkillTreeData.getInstance().getCompleteClassSkillTree(rootClass));
	}
	
	private boolean isFishingSkill(Skill skill)
	{
		return isSkillInTree(skill, SkillTreeData.getInstance().getFishingSkillTree());
	}
	
	private boolean isNobleSkill(Skill skill)
	{
		return isSkillInTree(skill, SkillTreeData.getInstance().getNobleSkillTree());
	}
	
	private boolean isHeroSkill(Skill skill)
	{
		return isSkillInTree(skill, SkillTreeData.getInstance().getHeroSkillTree());
	}
	
	private boolean isClanSkill(Skill skill)
	{
		return isSkillInTree(skill, SkillTreeData.getInstance().getPledgeSkillTree());
	}
	
	private boolean isProtectedByGroup(Player player, Skill skill)
	{
		if ((player == null) || (skill == null) || RebirthConfig.REBIRTH_DELETE_PROTECTED_SKILL_GROUPS.isEmpty())
		{
			return false;
		}
		
		for (String group : RebirthConfig.REBIRTH_DELETE_PROTECTED_SKILL_GROUPS)
		{
			switch (group)
			{
				case "STARTING_CLASS":
				{
					if (isStartingClassSkill(player, skill))
					{
						return true;
					}
					break;
				}
				case "FISHING":
				{
					if (isFishingSkill(skill))
					{
						return true;
					}
					break;
				}
				case "NOBLE":
				{
					if (isNobleSkill(skill))
					{
						return true;
					}
					break;
				}
				case "HERO":
				{
					if (isHeroSkill(skill))
					{
						return true;
					}
					break;
				}
				case "CLAN":
				{
					if (isClanSkill(skill))
					{
						return true;
					}
					break;
				}
			}
		}
		return false;
	}
	
	private boolean isSkillInTree(Skill skill, Map<?, ?> tree)
	{
		if ((skill == null) || (tree == null) || tree.isEmpty())
		{
			return false;
		}
		
		final long skillHashCode = SkillData.getSkillHashCode(skill.getId(), skill.getLevel());
		return tree.containsKey(Integer.valueOf((int) skillHashCode)) || tree.containsKey(Long.valueOf(skillHashCode));
	}
	
	private int getRootClassId(int classId)
	{
		ensureRootClassIdsLoaded();
		
		final Integer rootClassId = ROOT_CLASS_IDS.get(Integer.valueOf(classId));
		if (rootClassId != null)
		{
			return rootClassId.intValue();
		}
		
		final PlayerClass playerClass = PlayerClass.getPlayerClass(classId);
		if (playerClass != null)
		{
			return playerClass.getRootClass().getId();
		}
		return classId;
	}
	
	private void ensureRootClassIdsLoaded()
	{
		if (ROOT_CLASS_IDS_LOADED)
		{
			return;
		}
		
		synchronized (ROOT_CLASS_IDS)
		{
			if (ROOT_CLASS_IDS_LOADED)
			{
				return;
			}
			
			loadRootClassIds();
			ROOT_CLASS_IDS_LOADED = true;
		}
	}
	
	private void loadRootClassIds()
	{
		final File file = new File(CLASS_LIST_FILE);
		if (!file.exists())
		{
			LOGGER.warning("Rebirth class list file not found: " + CLASS_LIST_FILE);
			return;
		}
		
		final Map<Integer, Integer> parentClassIds = new HashMap<>();
		try
		{
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			final NodeList classNodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("class");
			for (int i = 0; i < classNodes.getLength(); i++)
			{
				final Node classNode = classNodes.item(i);
				final int classId = parseIntAttribute(classNode, "classId", -1);
				if (classId < 0)
				{
					continue;
				}
				
				parentClassIds.put(Integer.valueOf(classId), Integer.valueOf(parseIntAttribute(classNode, "parentClassId", -1)));
			}
			
			for (Integer classId : parentClassIds.keySet())
			{
				ROOT_CLASS_IDS.put(classId, Integer.valueOf(resolveRootClassId(classId.intValue(), parentClassIds)));
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "Failed to parse rebirth class list file: " + CLASS_LIST_FILE, e);
		}
	}
	
	private int resolveRootClassId(int classId, Map<Integer, Integer> parentClassIds)
	{
		int currentClassId = classId;
		final Set<Integer> visited = new HashSet<>();
		while (visited.add(Integer.valueOf(currentClassId)))
		{
			final Integer parentClassId = parentClassIds.get(Integer.valueOf(currentClassId));
			if ((parentClassId == null) || (parentClassId.intValue() < 0))
			{
				return currentClassId;
			}
			currentClassId = parentClassId.intValue();
		}
		return classId;
	}
	
	private int parseIntAttribute(Node node, String attributeName, int defaultValue)
	{
		if ((node == null) || (node.getAttributes() == null) || (node.getAttributes().getNamedItem(attributeName) == null))
		{
			return defaultValue;
		}
		
		try
		{
			return Integer.parseInt(node.getAttributes().getNamedItem(attributeName).getNodeValue());
		}
		catch (NumberFormatException e)
		{
			LOGGER.log(Level.WARNING, "Failed to parse rebirth class attribute: attribute=" + attributeName + " value=" + node.getAttributes().getNamedItem(attributeName).getNodeValue(), e);
			return defaultValue;
		}
	}
	
	/**
	 * Displays the rebirth skill selection window.
	 * @param player
	 * @param objectId
	 */
	public void displaySkillSelectionWindow(Player player, int objectId)
	{
		final int rebirthCount = getRebirthLevel(player);
		if (rebirthCount <= 0)
		{
			player.sendMessage("You must perform at least one Rebirth before selecting skills.");
			return;
		}
		
		final List<String> skillDefinitions = getSkillPool(player);
		final Map<Integer, String> skillDefinitionMap = createSkillDefinitionMap(skillDefinitions, player);
		if (skillDefinitionMap.isEmpty())
		{
			player.sendMessage("There are no skills configured for your class. Contact an administrator.");
			return;
		}
		
		final List<Integer> selectedSkills = loadValidatedSelectedSkills(player, skillDefinitionMap);
		final Set<Integer> selectedSkillSet = createSkillSet(selectedSkills);
		final int allowedSkills = Math.min(rebirthCount, RebirthConfig.REBIRTH_MAX_SELECTED_SKILLS);
		final NpcHtmlMessage html = new NpcHtmlMessage(objectId);
		final StringBuilder htmlBuilder = new StringBuilder();
		
		htmlBuilder.append("<html><body><center>");
		htmlBuilder.append("<title>Select your Rebirth Skills</title>");
		htmlBuilder.append("<br><font color=LEVEL>Maximum allowed: ").append(allowedSkills).append(" skill(s)</font><br1>");
		htmlBuilder.append("<font color=99FF99>Tokens available: ").append(getItemCount(player, RebirthConfig.REBIRTH_REWARD_ITEM_ID)).append("</font><br><br>");
		htmlBuilder.append("<table width=256 cellpadding=1 cellspacing=2><tr>");
		
		int column = 0;
		for (Entry<Integer, String> entry : skillDefinitionMap.entrySet())
		{
			final String definition = entry.getValue();
			final int skillLevel = getSkillLevel(definition);
			if (skillLevel <= 0)
			{
				LOGGER.warning("Skipping invalid rebirth skill definition in selection window: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " definition=" + definition);
				continue;
			}
			
			final int skillId = entry.getKey().intValue();
			final Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
			if (skill == null)
			{
				LOGGER.warning("Configured rebirth skill was not found in selection window: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " skillId=" + skillId + " skillLevel=" + skillLevel + " definition=" + definition);
				continue;
			}
			
			final int itemCost = getSkillCost(definition);
			if (column == SKILLS_PER_ROW)
			{
				htmlBuilder.append("</tr><tr>");
				column = 0;
			}
			
			final String icon = (skillId < SKILL_ICON_PADDING_THRESHOLD) ? ("0" + skillId) : String.valueOf(skillId);
			htmlBuilder.append("<td width=90 align=center>");
			htmlBuilder.append("<img src=\"icon.skill").append(icon).append("\" width=32 height=32><br1>");
			htmlBuilder.append("<font color=\"FFDD99\">").append(skill.getName()).append("</font><br1>");
			htmlBuilder.append("<font color=\"FFFF99\">Lv. ").append(skill.getLevel()).append("</font><br1>");
			htmlBuilder.append("<font color=\"99FF99\">Cost: ").append(itemCost).append("</font><br1>");
			
			if (selectedSkillSet.contains(Integer.valueOf(skillId)))
			{
				htmlBuilder.append("<font color=AAAAAA>Owned</font>");
			}
			else if (selectedSkills.size() >= allowedSkills)
			{
				htmlBuilder.append("<font color=AAAAAA>Limit reached</font>");
			}
			else
			{
				htmlBuilder.append("<button value=\"Choose\" action=\"bypass -h rebirth_previewSkill ").append(skillId).append("\" width=40 height=20 back=\"bigbutton_over\" fore=\"bigbutton\">");
			}
			
			htmlBuilder.append("</td>");
			column++;
		}
		
		htmlBuilder.append("</tr></table><br>");
		htmlBuilder.append("<button value=\"Return\" action=\"bypass -h rebirth_openmenu\" width=95 height=20 back=\"bigbutton_over\" fore=\"bigbutton\">");
		htmlBuilder.append("</center></body></html>");
		
		html.setHtml(htmlBuilder.toString());
		player.sendPacket(html);
	}
	
	/**
	 * Displays the rebirth skill preview window for a specific skill.
	 * @param player
	 * @param objectId
	 * @param skillId
	 */
	public void displaySkillPreviewWindow(Player player, int objectId, int skillId)
	{
		final List<String> skillDefinitions = getSkillPool(player);
		final Map<Integer, String> skillDefinitionMap = createSkillDefinitionMap(skillDefinitions, player);
		final List<Integer> savedSkills = loadValidatedSelectedSkills(player, skillDefinitionMap);
		final Set<Integer> savedSkillSet = createSkillSet(savedSkills);
		final int rebirthCount = getRebirthLevel(player);
		displaySkillPreviewWindow(player, objectId, skillId, skillDefinitionMap, savedSkills, savedSkillSet, rebirthCount);
	}
	
	/**
	 * Displays the rebirth main window.
	 * @param player
	 * @param objectId
	 */
	public void displayMainWindow(Player player, int objectId)
	{
		final Map<Integer, String> skillDefinitionMap = createSkillDefinitionMap(getSkillPool(player), player);
		final List<Integer> selectedSkills = loadValidatedSelectedSkills(player, skillDefinitionMap);
		final int currentRebirthCount = getRebirthLevel(player);
		displayMainWindow(player, objectId, selectedSkills, currentRebirthCount, skillDefinitionMap);
	}
	
	/**
	 * Acquires and persists a rebirth skill for the player.
	 * @param player
	 * @param objectId
	 * @param skillId
	 */
	public void acquireRebirthSkill(Player player, int objectId, int skillId)
	{
		final int playerId = player.getObjectId();
		if (!REBIRTH_SKILL_OPERATIONS_IN_PROGRESS.add(Integer.valueOf(playerId)))
		{
			player.sendMessage(REBIRTH_BUSY_MESSAGE);
			return;
		}
		
		try
		{
			final Map<Integer, String> skillDefinitionMap = createSkillDefinitionMap(getSkillPool(player), player);
			final List<Integer> savedSkills = loadValidatedSelectedSkills(player, skillDefinitionMap);
			final int rebirthCount = getRebirthLevel(player);
			if (rebirthCount <= 0)
			{
				player.sendMessage("You must perform at least one Rebirth before selecting skills.");
				displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
				return;
			}
			
			final Set<Integer> savedSkillSet = createSkillSet(savedSkills);
			if (savedSkillSet.contains(Integer.valueOf(skillId)))
			{
				player.sendMessage("You already own this rebirth skill.");
				displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
				return;
			}
			
			final int allowedSkills = Math.min(rebirthCount, RebirthConfig.REBIRTH_MAX_SELECTED_SKILLS);
			if (savedSkills.size() >= allowedSkills)
			{
				player.sendMessage("You have reached the maximum number of skills allowed. (" + allowedSkills + ").");
				displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
				return;
			}
			
			final String definition = getSkillDefinition(skillDefinitionMap, skillId);
			if (definition == null)
			{
				player.sendMessage("This skill is not configured for your class.");
				displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
				return;
			}
			
			final int itemCost = getSkillCost(definition);
			if (!consumeItem(player, RebirthConfig.REBIRTH_REWARD_ITEM_ID, itemCost, ItemProcessType.DESTROY, "rebirth token"))
			{
				displaySkillPreviewWindow(player, objectId, skillId, skillDefinitionMap, savedSkills, savedSkillSet, rebirthCount);
				return;
			}
			
			savedSkills.add(Integer.valueOf(skillId));
			saveSelectedSkills(playerId, savedSkills);
			grantRebirthSkills(player, skillDefinitionMap, savedSkills);
			playRebirthSelectFx(player);
			player.sendMessage("Rebirth skill purchased successfully.");
			displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
		}
		finally
		{
			REBIRTH_SKILL_OPERATIONS_IN_PROGRESS.remove(Integer.valueOf(playerId));
		}
	}
	
	/**
	 * Resets a previously acquired rebirth skill.
	 * @param player
	 * @param objectId
	 * @param skillId
	 */
	public void resetRebirthSkill(Player player, int objectId, int skillId)
	{
		final int playerId = player.getObjectId();
		if (!REBIRTH_SKILL_OPERATIONS_IN_PROGRESS.add(Integer.valueOf(playerId)))
		{
			player.sendMessage(REBIRTH_BUSY_MESSAGE);
			return;
		}
		
		try
		{
			final Map<Integer, String> skillDefinitionMap = createSkillDefinitionMap(getSkillPool(player), player);
			final List<Integer> savedSkills = loadValidatedSelectedSkills(player, skillDefinitionMap);
			final Set<Integer> savedSkillSet = createSkillSet(savedSkills);
			final int rebirthCount = getRebirthLevel(player);
			if (!savedSkillSet.contains(Integer.valueOf(skillId)))
			{
				player.sendMessage("You do not own this rebirth skill.");
				displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
				return;
			}
			
			final String definition = getSkillDefinition(skillDefinitionMap, skillId);
			if (definition == null)
			{
				player.sendMessage("The configured skill data is invalid.");
				displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
				return;
			}
			
			final int skillLevel = getSkillLevel(definition);
			final int itemCost = getSkillCost(definition);
			final int refundAmount = getRefundAmount(itemCost);
			
			savedSkills.remove(Integer.valueOf(skillId));
			saveSelectedSkills(playerId, savedSkills);
			
			final Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
			if (skill != null)
			{
				player.removeSkill(skill, true);
			}
			else
			{
				LOGGER.warning("Configured rebirth skill could not be removed because it was not found: playerId=" + playerId + " playerName=" + player.getName() + " skillId=" + skillId + " skillLevel=" + skillLevel + " definition=" + definition);
			}
			player.sendSkillList();
			
			if (refundAmount > 0)
			{
				player.addItem(ItemProcessType.REFUND, RebirthConfig.REBIRTH_REWARD_ITEM_ID, refundAmount, player, true);
				player.sendMessage("Refunded " + refundAmount + " rebirth token(s).");
			}
			else
			{
				player.sendMessage("Skill removed. Refund mode " + RebirthConfig.REBIRTH_SKILL_REFUND_MODE + " returned no tokens.");
			}
			
			displayMainWindow(player, objectId, savedSkills, rebirthCount, skillDefinitionMap);
		}
		finally
		{
			REBIRTH_SKILL_OPERATIONS_IN_PROGRESS.remove(Integer.valueOf(playerId));
		}
	}
	
	/**
	 * Builds the acquired rebirth skills HTML block.
	 * @param player
	 * @param selectedSkills
	 * @param skillDefinitionMap
	 * @param showResetButton
	 * @return The acquired skills HTML.
	 */
	private String buildAcquiredSkillsHtml(Player player, List<Integer> selectedSkills, Map<Integer, String> skillDefinitionMap, boolean showResetButton)
	{
		if (selectedSkills.isEmpty())
		{
			return "<center><font color=\"AAAAAA\">You don't have any skills selected yet.</font></center>";
		}
		
		final StringBuilder htmlBuilder = new StringBuilder();
		htmlBuilder.append("<table width=270 border=0 cellpadding=2 cellspacing=2><tr>");
		int column = 0;
		for (int skillId : selectedSkills)
		{
			final String definition = getSkillDefinition(skillDefinitionMap, skillId);
			if (definition == null)
			{
				LOGGER.warning("Selected rebirth skill definition was not found while building acquired skills HTML: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " skillId=" + skillId);
				continue;
			}
			
			final int skillLevel = getSkillLevel(definition);
			final Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
			if (skill == null)
			{
				LOGGER.warning("Selected rebirth skill was not found while building acquired skills HTML: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " skillId=" + skillId + " skillLevel=" + skillLevel + " definition=" + definition);
				continue;
			}
			
			final int itemCost = getSkillCost(definition);
			if (column == SKILLS_PER_ROW)
			{
				htmlBuilder.append("</tr><tr>");
				column = 0;
			}
			
			final String icon = (skillId < SKILL_ICON_PADDING_THRESHOLD) ? ("0" + skillId) : String.valueOf(skillId);
			htmlBuilder.append("<td width=90 align=center>");
			htmlBuilder.append("<img src=\"icon.skill").append(icon).append("\" width=32 height=32><br1>");
			htmlBuilder.append("<font color=\"FFDD99\">").append(skill.getName()).append("</font><br1>");
			htmlBuilder.append("<font color=\"FFFF99\">Lv. ").append(skill.getLevel()).append("</font><br1>");
			htmlBuilder.append("<font color=\"99FF99\">Cost: ").append(itemCost).append("</font><br1>");
			
			if (showResetButton)
			{
				htmlBuilder.append("<button value=\"Reset\" action=\"bypass -h rebirth_resetSkill ").append(skillId).append("\" width=65 height=18 back=\"bigbutton_over\" fore=\"bigbutton\"><br1>");
			}
			
			htmlBuilder.append("</td>");
			column++;
		}
		
		htmlBuilder.append("</tr></table>");
		return htmlBuilder.toString();
	}
	
	/**
	 * Returns the configured skill pool for the player's class type.
	 * @param player
	 * @return The configured skill pool.
	 */
	private List<String> getSkillPool(Player player)
	{
		return player.getPlayerClass().isMage() ? RebirthConfig.REBIRTH_MAGE_SKILLS : RebirthConfig.REBIRTH_FIGHTER_SKILLS;
	}
	
	/**
	 * Creates a skill definition lookup map indexed by skill id.
	 * @param skillDefinitions
	 * @param player
	 * @return The indexed skill definition map.
	 */
	private Map<Integer, String> createSkillDefinitionMap(List<String> skillDefinitions, Player player)
	{
		final Map<Integer, String> skillDefinitionMap = new HashMap<>();
		for (String definition : skillDefinitions)
		{
			final int skillId = getSkillId(definition);
			if (skillId <= 0)
			{
				LOGGER.warning("Skipping invalid rebirth skill definition while building index: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " definition=" + definition);
				continue;
			}
			
			final String previous = skillDefinitionMap.put(Integer.valueOf(skillId), definition);
			if (previous != null)
			{
				LOGGER.warning("Duplicate rebirth skill definition detected while building index: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " skillId=" + skillId + " previousDefinition=" + previous + " newDefinition=" + definition);
			}
		}
		return skillDefinitionMap;
	}
	
	/**
	 * Creates a skill membership set from the persisted selected skill list.
	 * @param skills
	 * @return The indexed skill set.
	 */
	private Set<Integer> createSkillSet(List<Integer> skills)
	{
		return new HashSet<>(skills);
	}
	
	/**
	 * Loads and sanitizes selected skills against current configuration.
	 * @param player
	 * @param skillDefinitionMap
	 * @return The validated selected skills list.
	 */
	private List<Integer> loadValidatedSelectedSkills(Player player, Map<Integer, String> skillDefinitionMap)
	{
		final int playerId = player.getObjectId();
		final List<Integer> storedSkills = getSelectedSkills(playerId);
		if (storedSkills.isEmpty())
		{
			return storedSkills;
		}
		
		final List<Integer> validSkills = new ArrayList<>(storedSkills.size());
		boolean changed = false;
		for (int skillId : storedSkills)
		{
			if (skillDefinitionMap.containsKey(Integer.valueOf(skillId)))
			{
				validSkills.add(Integer.valueOf(skillId));
			}
			else
			{
				changed = true;
				LOGGER.warning("Removing stale rebirth skill from persisted selection: playerId=" + playerId + " playerName=" + player.getName() + " skillId=" + skillId);
			}
		}
		
		if (changed)
		{
			saveSelectedSkills(playerId, validSkills);
		}
		return validSkills;
	}
	
	/**
	 * Returns the configured skill definition for a skill id.
	 * @param skillDefinitionMap
	 * @param skillId
	 * @return The skill definition, or {@code null} if not found.
	 */
	private String getSkillDefinition(Map<Integer, String> skillDefinitionMap, int skillId)
	{
		return skillDefinitionMap.get(Integer.valueOf(skillId));
	}
	
	/**
	 * Extracts the skill id from a configured skill definition.
	 * @param definition
	 * @return The configured skill id, or {@code 0} if invalid.
	 */
	private int getSkillId(String definition)
	{
		final String[] parts = definition.split(",");
		if (parts.length < 2)
		{
			return 0;
		}
		
		try
		{
			return Integer.parseInt(parts[0].trim());
		}
		catch (NumberFormatException e)
		{
			LOGGER.log(Level.WARNING, "Failed to parse rebirth skill id from definition: definition=" + definition, e);
			return 0;
		}
	}
	
	/**
	 * Extracts the skill level from a configured skill definition.
	 * @param definition
	 * @return The configured skill level, or {@code 1} if invalid.
	 */
	private int getSkillLevel(String definition)
	{
		final String[] parts = definition.split(",");
		if (parts.length < 2)
		{
			return 1;
		}
		
		try
		{
			return Integer.parseInt(parts[1].trim());
		}
		catch (NumberFormatException e)
		{
			LOGGER.log(Level.WARNING, "Failed to parse rebirth skill level from definition: definition=" + definition, e);
			return 1;
		}
	}
	
	/**
	 * Extracts the skill token cost from a configured skill definition.
	 * @param definition
	 * @return The configured skill cost, or {@code 1} if invalid.
	 */
	private int getSkillCost(String definition)
	{
		final String[] parts = definition.split(",");
		if (parts.length < 3)
		{
			return 1;
		}
		
		try
		{
			return Math.max(1, Integer.parseInt(parts[2].trim()));
		}
		catch (NumberFormatException e)
		{
			LOGGER.log(Level.WARNING, "Failed to parse rebirth skill cost from definition: definition=" + definition, e);
			return 1;
		}
	}
	
	/**
	 * Calculates the token refund amount for a skill reset.
	 * @param itemCost
	 * @return The refund amount.
	 */
	private int getRefundAmount(int itemCost)
	{
		switch (RebirthConfig.REBIRTH_SKILL_REFUND_MODE)
		{
			case "FULL":
				return itemCost;
			case "MID":
				return itemCost / 2;
			default:
				return 0;
		}
	}
	
	/**
	 * Returns the player inventory count for a specific item.
	 * @param player
	 * @param itemId
	 * @return The available item count.
	 */
	private long getItemCount(Player player, int itemId)
	{
		final Item item = player.getInventory().getItemByItemId(itemId);
		return item != null ? item.getCount() : 0;
	}
	
	/**
	 * Validates and processes a rebirth request.
	 * @param player
	 */
	public void requestRebirth(Player player)
	{
		final int playerId = player.getObjectId();
		if (!REBIRTH_OPERATIONS_IN_PROGRESS.add(Integer.valueOf(playerId)))
		{
			player.sendMessage(REBIRTH_BUSY_MESSAGE);
			return;
		}
		
		try
		{
			if (!RebirthConfig.REBIRTH_ALLOW_REBIRTH)
			{
				player.sendMessage("Rebirth is disabled by configuration.");
				return;
			}
			if (player.getLevel() < RebirthConfig.REBIRTH_MIN_LEVEL)
			{
				player.sendMessage("You do not meet the minimum level for Rebirth (" + RebirthConfig.REBIRTH_MIN_LEVEL + ").");
				return;
			}
			if (player.isAlikeDead() || player.isCastingNow() || player.isAttackingNow())
			{
				player.sendMessage("You cannot request Rebirth right now.");
				return;
			}
			
			final int currentRebirthCount = getRebirthLevel(player);
			if (currentRebirthCount >= RebirthConfig.REBIRTH_MAX_COUNT)
			{
				player.sendMessage("You are currently at your maximum rebirth count.");
				return;
			}
			
			if (!consumeItem(player, RebirthConfig.REBIRTH_ITEM_ID, RebirthConfig.REBIRTH_ITEM_AMOUNT, ItemProcessType.DESTROY, "rebirth request"))
			{
				return;
			}
			
			grantRebirth(player, currentRebirthCount + 1, currentRebirthCount == 0);
		}
		finally
		{
			REBIRTH_OPERATIONS_IN_PROGRESS.remove(Integer.valueOf(playerId));
		}
	}
	
	/**
	 * Applies the rebirth state change and updates the character.
	 * @param player
	 * @param newCount
	 * @param firstBirth
	 */
	private void grantRebirth(Player player, int newCount, boolean firstBirth)
	{
		final int playerId = player.getObjectId();
		final String playerName = player.getName();
		
		try
		{
			// Critical persistence step.
			final boolean updated = firstBirth ? insertFirst(playerId) : updateCount(playerId, newCount);
			if (!updated)
			{
				player.sendMessage("Rebirth could not be completed due to a database error.");
				LOGGER.severe("Aborting rebirth because rebirth count persistence failed: playerId=" + playerId + " playerName=" + playerName + " newCount=" + newCount + " firstBirth=" + firstBirth);
				return;
			}
			
			// Critical player mutation step.
			final long currentExp = player.getExp();
			final long targetExp = ExperienceData.getInstance().getExpForLevel(1);
			if (currentExp > targetExp)
			{
				player.getStat().setLevel((byte) 1);
				player.removeExpAndSp(currentExp - targetExp, 0);
				player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
				player.setCurrentCp(player.getMaxCp());
				player.broadcastUserInfo();
			}
			
			player.setPlayerClass(player.getBaseClass());
			if (RebirthConfig.REBIRTH_DELETE_ALL_SKILLS)
			{
				for (Skill skill : player.getAllSkills())
				{
					if (skill == null)
					{
						continue;
					}
					
					if (RebirthConfig.REBIRTH_SKILL_DELETE_WHITELIST.contains(Integer.valueOf(skill.getId())))
					{
						continue;
					}
					if (isProtectedByGroup(player, skill))
					{
						continue;
					}
					
					player.removeSkill(skill, true);
				}
			}
			player.giveAvailableSkills(true, true, true);
			if (RebirthConfig.REBIRTH_DELETE_RESTORE_TEMPORARY_SKILLS)
			{
				player.regiveTemporarySkills();
			}
			player.storeMe();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Failed during critical rebirth section: playerId=" + playerId + " playerName=" + playerName + " newCount=" + newCount + " firstBirth=" + firstBirth, e);
			player.sendMessage("Rebirth could not be completed due to an internal error.");
			return;
		}
		
		try
		{
			// Post-commit section.
			final Map<Integer, String> skillDefinitionMap = createSkillDefinitionMap(getSkillPool(player), player);
			final List<Integer> selectedSkills = loadValidatedSelectedSkills(player, skillDefinitionMap);
			grantRewardTokens(player);
			if (!RebirthConfig.REBIRTH_DELETE_RESTORE_TEMPORARY_SKILLS)
			{
				grantRebirthSkills(player, skillDefinitionMap, selectedSkills);
			}
			player.sendSkillList();
			player.broadcastUserInfo();
			player.broadcastStatusUpdate();
			displayCongrats(player, newCount);
			player.sendMessage("Rebirth applied successfully. You have been reborn!");
			routePostRebirthUi(player, selectedSkills, newCount, skillDefinitionMap);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Failed during post-commit rebirth section: playerId=" + playerId + " playerName=" + playerName + " newCount=" + newCount, e);
		}
	}
	
	/**
	 * Grants configured rebirth reward tokens.
	 * @param player
	 */
	private void grantRewardTokens(Player player)
	{
		if ((RebirthConfig.REBIRTH_REWARD_ITEM_ID <= 0) || (RebirthConfig.REBIRTH_REWARD_ITEM_AMOUNT <= 0))
		{
			return;
		}
		
		player.addItem(ItemProcessType.REWARD, RebirthConfig.REBIRTH_REWARD_ITEM_ID, RebirthConfig.REBIRTH_REWARD_ITEM_AMOUNT, player, true);
		player.sendMessage("You received " + RebirthConfig.REBIRTH_REWARD_ITEM_AMOUNT + " rebirth token(s).");
	}
	
	/**
	 * Displays configured congratulations effects and announcements.
	 * @param player
	 * @param rebirthCount
	 */
	private void displayCongrats(Player player, int rebirthCount)
	{
		if (RebirthConfig.REBIRTH_FINISH_SOCIAL_ID > 0)
		{
			player.broadcastPacket(new SocialAction(player.getObjectId(), RebirthConfig.REBIRTH_FINISH_SOCIAL_ID));
		}
		if (RebirthConfig.REBIRTH_FINISH_EFFECT_SKILL_ID > 0)
		{
			player.broadcastPacket(new MagicSkillUse(player, player, RebirthConfig.REBIRTH_FINISH_EFFECT_SKILL_ID, RebirthConfig.REBIRTH_FINISH_EFFECT_SKILL_LVL, 1, 0));
		}
		
		final String globalAnnouncement = RebirthConfig.REBIRTH_GLOBAL_ANNOUNCEMENT.replace("%player%", player.getName()).replace("%rebirth_count%", String.valueOf(rebirthCount)).replace("%max_rebirth%", String.valueOf(RebirthConfig.REBIRTH_MAX_COUNT));
		
		if (RebirthConfig.REBIRTH_GLOBAL_ANNOUNCEMENT_ENABLED)
		{
			World.broadcastToAllOnlinePlayers(globalAnnouncement, RebirthConfig.REBIRTH_GLOBAL_ANNOUNCEMENT_CRITICAL);
		}
		
		player.sendMessage("Congratulations " + player.getName() + "! You have been Reborn!");
	}
	
	/**
	 * Routes the player to the post-rebirth UI flow.
	 * @param player
	 * @param selectedSkills
	 * @param rebirthCount
	 * @param skillDefinitionMap
	 */
	private void routePostRebirthUi(Player player, List<Integer> selectedSkills, int rebirthCount, Map<Integer, String> skillDefinitionMap)
	{
		displayMainWindow(player, 0, selectedSkills, rebirthCount, skillDefinitionMap);
		player.sendMessage("Use your rebirth tokens to purchase skills.");
	}
	
	/**
	 * Plays configured skill selection visual effects.
	 * @param player
	 */
	public void playRebirthSelectFx(Player player)
	{
		if (RebirthConfig.REBIRTH_SELECT_SOCIAL_ID > 0)
		{
			player.broadcastPacket(new SocialAction(player.getObjectId(), RebirthConfig.REBIRTH_SELECT_SOCIAL_ID));
		}
		if (RebirthConfig.REBIRTH_SELECT_EFFECT_SKILL_ID > 0)
		{
			player.broadcastPacket(new MagicSkillUse(player, player, RebirthConfig.REBIRTH_SELECT_EFFECT_SKILL_ID, RebirthConfig.REBIRTH_SELECT_EFFECT_SKILL_LVL, 1, 0));
		}
	}
	
	/**
	 * Consumes an item amount from the player inventory.
	 * @param player
	 * @param itemId
	 * @param amount
	 * @param processType
	 * @param reason
	 * @return {@code true} if the item was consumed, {@code false} otherwise.
	 */
	private boolean consumeItem(Player player, int itemId, long amount, ItemProcessType processType, String reason)
	{
		if ((player == null) || (itemId <= 0) || (amount <= 0) || (amount > Integer.MAX_VALUE))
		{
			LOGGER.warning("Invalid consumeItem request: player=" + player + " itemId=" + itemId + " amount=" + amount + " processType=" + processType + " reason=" + reason);
			return false;
		}
		
		final Item item = player.getInventory().getItemByItemId(itemId);
		if ((item == null) || (item.getCount() < amount))
		{
			final String itemName = ((item != null) && (item.getTemplate() != null)) ? item.getTemplate().getName() : ("Item " + itemId);
			player.sendMessage("You need at least " + amount + " [ " + itemName + " ] for " + reason + ".");
			return false;
		}
		
		player.getInventory().destroyItem(processType, item, (int) amount, player, null);
		return true;
	}
	
	/**
	 * Grants all selected rebirth skills.
	 * @param player
	 */
	public void grantRebirthSkills(Player player)
	{
		final Map<Integer, String> skillDefinitionMap = createSkillDefinitionMap(getSkillPool(player), player);
		grantRebirthSkills(player, skillDefinitionMap, loadValidatedSelectedSkills(player, skillDefinitionMap));
	}
	
	/**
	 * Grants all selected rebirth skills.
	 * @param player
	 * @param skillDefinitionMap
	 * @param selectedSkills
	 */
	private void grantRebirthSkills(Player player, Map<Integer, String> skillDefinitionMap, List<Integer> selectedSkills)
	{
		if (!RebirthConfig.REBIRTH_INHERIT_SKILLS_TO_SUBCLASSES && (player.getClassIndex() > 0))
		{
			return;
		}
		
		for (int skillId : selectedSkills)
		{
			final String definition = getSkillDefinition(skillDefinitionMap, skillId);
			if (definition == null)
			{
				LOGGER.warning("Skipping selected rebirth skill because definition was not found: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " skillId=" + skillId);
				continue;
			}
			
			final Skill skill = SkillData.getInstance().getSkill(skillId, getSkillLevel(definition));
			if (skill != null)
			{
				player.addSkill(skill, true);
			}
			else
			{
				LOGGER.warning("Skipping selected rebirth skill because skill data was not found: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " skillId=" + skillId + " definition=" + definition);
			}
		}
		player.sendSkillList();
	}
	
	/**
	 * Returns the current player rebirth level.
	 * @param player
	 * @return The current rebirth count.
	 */
	public int getRebirthLevel(Player player)
	{
		return getCount(player.getObjectId());
	}
	
	/**
	 * Loads the persisted rebirth count for a character.
	 * @param charId
	 * @return The stored rebirth count.
	 */
	public int getCount(int charId)
	{
		int count = 0;
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement(SELECT_REBIRTH_COUNT))
		{
			statement.setInt(1, charId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				if (resultSet.next())
				{
					count = resultSet.getInt(1);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Failed to load rebirth count: charId=" + charId, e);
		}
		return count;
	}
	
	/**
	 * Inserts the first rebirth record for a character.
	 * @param charId
	 * @return {@code true} if the insert succeeded, {@code false} otherwise.
	 */
	public boolean insertFirst(int charId)
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement(INSERT_FIRST_REBIRTH))
		{
			statement.setInt(1, charId);
			return statement.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Failed to insert first rebirth record: charId=" + charId, e);
		}
		return false;
	}
	
	/**
	 * Updates the stored rebirth count for a character.
	 * @param charId
	 * @param newCount
	 * @return {@code true} if the update succeeded, {@code false} otherwise.
	 */
	public boolean updateCount(int charId, int newCount)
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement(UPDATE_REBIRTH_COUNT))
		{
			statement.setInt(1, newCount);
			statement.setInt(2, charId);
			return statement.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Failed to update rebirth count: charId=" + charId + " newCount=" + newCount, e);
		}
		return false;
	}
	
	/**
	 * Loads the persisted selected rebirth skills for a character.
	 * @param charId
	 * @return The stored selected skill ids.
	 */
	public List<Integer> getSelectedSkills(int charId)
	{
		final List<Integer> skills = new ArrayList<>();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement(SELECT_SELECTED_SKILLS))
		{
			statement.setInt(1, charId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				if (resultSet.next())
				{
					final String data = resultSet.getString(1);
					if ((data != null) && !data.isEmpty())
					{
						for (String skillValue : data.split(","))
						{
							try
							{
								skills.add(Integer.valueOf(Integer.parseInt(skillValue.trim())));
							}
							catch (NumberFormatException e)
							{
								LOGGER.log(Level.WARNING, "Failed to parse persisted rebirth skill id: charId=" + charId + " rawValue=" + skillValue + " data=" + data, e);
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Failed to load selected rebirth skills: charId=" + charId, e);
		}
		return skills;
	}
	
	/**
	 * Saves the selected rebirth skills for a character.
	 * @param charId
	 * @param skills
	 */
	public void saveSelectedSkills(int charId, List<Integer> skills)
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement(UPDATE_SELECTED_SKILLS))
		{
			final StringBuilder skillBuilder = new StringBuilder();
			for (int i = 0; i < skills.size(); i++)
			{
				if (i > 0)
				{
					skillBuilder.append(',');
				}
				skillBuilder.append(skills.get(i));
			}
			
			statement.setString(1, skillBuilder.toString());
			statement.setInt(2, charId);
			statement.executeUpdate();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Failed to save selected rebirth skills: charId=" + charId + " skills=" + skills, e);
		}
	}
	
	/**
	 * Displays the rebirth skill preview window for a specific skill.
	 * @param player
	 * @param objectId
	 * @param skillId
	 * @param skillDefinitionMap
	 * @param savedSkills
	 * @param savedSkillSet
	 * @param rebirthCount
	 */
	private void displaySkillPreviewWindow(Player player, int objectId, int skillId, Map<Integer, String> skillDefinitionMap, List<Integer> savedSkills, Set<Integer> savedSkillSet, int rebirthCount)
	{
		final String definition = getSkillDefinition(skillDefinitionMap, skillId);
		if (definition == null)
		{
			player.sendMessage("Invalid skill.");
			return;
		}
		
		final int targetLevel = getSkillLevel(definition);
		final Skill skill = SkillData.getInstance().getSkill(skillId, targetLevel);
		if (skill == null)
		{
			LOGGER.warning("Configured rebirth skill preview was not found: playerId=" + player.getObjectId() + " playerName=" + player.getName() + " skillId=" + skillId + " targetLevel=" + targetLevel + " definition=" + definition);
			player.sendMessage("Invalid skill.");
			return;
		}
		
		final int itemCost = getSkillCost(definition);
		final int allowedSkills = Math.min(rebirthCount, RebirthConfig.REBIRTH_MAX_SELECTED_SKILLS);
		final long availableTokens = getItemCount(player, RebirthConfig.REBIRTH_REWARD_ITEM_ID);
		final boolean alreadySelected = savedSkillSet.contains(Integer.valueOf(skillId));
		final boolean limitReached = savedSkills.size() >= allowedSkills;
		final boolean hasEnoughTokens = availableTokens >= itemCost;
		
		final NpcHtmlMessage html = new NpcHtmlMessage(objectId);
		final StringBuilder htmlBuilder = new StringBuilder();
		
		htmlBuilder.append("<html><body><center>");
		htmlBuilder.append("<br>");
		htmlBuilder.append("<font color=\"LEVEL\">Rebirth Manager</font><br>");
		htmlBuilder.append("<table width=256 border=0 cellpadding=1 cellspacing=2>");
		htmlBuilder.append("<tr><td width=95 align=center>");
		htmlBuilder.append("<font color=\"66CC66\">Your Rebirths</font><br1>");
		htmlBuilder.append("<font color=\"FFFF99\">").append(rebirthCount).append('/').append(RebirthConfig.REBIRTH_MAX_COUNT).append("</font><br1>");
		htmlBuilder.append("<font color=\"99FF99\">Tokens: ").append(availableTokens).append("</font>");
		htmlBuilder.append("</td><td width=95 align=center>");
		final String icon = (skillId < SKILL_ICON_PADDING_THRESHOLD) ? ("0" + skillId) : String.valueOf(skillId);
		htmlBuilder.append("<img src=\"icon.skill").append(icon).append("\" width=32 height=32><br1>");
		htmlBuilder.append("<font color=\"FFDD99\">").append(skill.getName()).append("</font><br1>");
		htmlBuilder.append("<font color=\"FFFF99\">Lv. ").append(skill.getLevel()).append("</font><br1>");
		htmlBuilder.append("<font color=\"99FF99\">Cost: ").append(itemCost).append("</font>");
		htmlBuilder.append("</td></tr></table>");
		htmlBuilder.append("<br1>");
		htmlBuilder.append("<font color=\"LEVEL\">This purchase consumes ").append(itemCost).append(" token(s).</font><br1>");
		htmlBuilder.append("<font color=\"LEVEL\">Refund: ").append(RebirthConfig.REBIRTH_SKILL_REFUND_MODE).append("</font><br1>");
		htmlBuilder.append("<font color=\"66CC66\">Rebirth Skills Obtained</font><br1>");
		htmlBuilder.append(buildAcquiredSkillsHtml(player, savedSkills, skillDefinitionMap, false));
		htmlBuilder.append("<br1>");
		if (!alreadySelected && !limitReached && hasEnoughTokens)
		{
			htmlBuilder.append("<button value=\"Confirm\" action=\"bypass -h rebirth_confirmSkill ").append(skillId).append("\" width=95 height=20 back=\"bigbutton_over\" fore=\"bigbutton\"><br1>");
		}
		else
		{
			if (alreadySelected)
			{
				htmlBuilder.append("<font color=AAAAAA>Already selected.</font><br1>");
			}
			if (limitReached)
			{
				htmlBuilder.append("<font color=AAAAAA>Skill limit reached.</font><br1>");
			}
			if (!hasEnoughTokens)
			{
				htmlBuilder.append("<font color=FF6666>Not enough tokens.</font><br1>");
			}
		}
		
		htmlBuilder.append("<button value=\"Return\" action=\"bypass -h rebirth_selectskills\" width=95 height=20 back=\"bigbutton_over\" fore=\"bigbutton\">");
		htmlBuilder.append("</center></body></html>");
		
		html.setHtml(htmlBuilder.toString());
		player.sendPacket(html);
	}
	
	/**
	 * Displays the rebirth main window.
	 * @param player
	 * @param objectId
	 * @param selectedSkills
	 * @param rebirthCount
	 * @param skillDefinitionMap
	 */
	private void displayMainWindow(Player player, int objectId, List<Integer> selectedSkills, int rebirthCount, Map<Integer, String> skillDefinitionMap)
	{
		final long tokenCount = getItemCount(player, RebirthConfig.REBIRTH_REWARD_ITEM_ID);
		
		final NpcHtmlMessage html = new NpcHtmlMessage(objectId);
		html.setFile(player, "data/html/default/70001_dynamic.htm");
		html.replace("%rebirth_count%", rebirthCount + "/" + RebirthConfig.REBIRTH_MAX_COUNT);
		html.replace("%rebirth_icons%", buildAcquiredSkillsHtml(player, selectedSkills, skillDefinitionMap, true));
		html.replace("%next_skill_icon%", "<img src=\"icon.etc_coins_gold_i00\" width=32 height=32>");
		html.replace("%next_skill_name%", "Rebirth Tokens");
		html.replace("%next_skill_lvl%", String.valueOf(tokenCount));
		html.replace("%next_skill_desc%", "Refund mode: " + RebirthConfig.REBIRTH_SKILL_REFUND_MODE);
		
		if (rebirthCount < RebirthConfig.REBIRTH_MAX_COUNT)
		{
			html.replace("%rebirth_button%", "<button value=\"Request Rebirth\" action=\"bypass -h rebirth_confirmrequest\" width=95 height=20 back=\"bigbutton_over\" fore=\"bigbutton\">");
		}
		else
		{
			html.replace("%rebirth_button%", "<font color=AAAAAA>Max Rebirth reached.</font>");
		}
		
		player.sendPacket(html);
	}
	
	/**
	 * Returns the singleton rebirth manager instance.
	 * @return The rebirth manager instance.
	 */
	public static RebirthManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	/**
	 * Holds the singleton rebirth manager instance.
	 */
	private static class SingletonHolder
	{
		protected static final RebirthManager INSTANCE = new RebirthManager();
	}
}
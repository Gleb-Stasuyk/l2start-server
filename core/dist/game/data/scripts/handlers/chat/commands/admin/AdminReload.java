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
package handlers.chat.commands.admin;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.StringUtil;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.ConfigLoader;
import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.data.sql.CrestTable;
import org.l2jmobius.gameserver.data.xml.AdminData;
import org.l2jmobius.gameserver.data.xml.ArmorSetData;
import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2jmobius.gameserver.data.xml.CategoryData;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.CubicData;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.data.xml.DynamicExpRateData;
import org.l2jmobius.gameserver.data.xml.EnchantItemData;
import org.l2jmobius.gameserver.data.xml.EnchantItemGroupsData;
import org.l2jmobius.gameserver.data.xml.EnchantItemHPBonusData;
import org.l2jmobius.gameserver.data.xml.EnchantSkillTreeData;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ExperienceLossData;
import org.l2jmobius.gameserver.data.xml.FenceData;
import org.l2jmobius.gameserver.data.xml.FishData;
import org.l2jmobius.gameserver.data.xml.FishingMonstersData;
import org.l2jmobius.gameserver.data.xml.FishingRodsData;
import org.l2jmobius.gameserver.data.xml.HennaData;
import org.l2jmobius.gameserver.data.xml.HitConditionBonusData;
import org.l2jmobius.gameserver.data.xml.InitialEquipmentData;
import org.l2jmobius.gameserver.data.xml.InitialShortcutData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.KarmaLossData;
import org.l2jmobius.gameserver.data.xml.LevelUpCrystalData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.NpcNameLocalisationData;
import org.l2jmobius.gameserver.data.xml.OptionData;
import org.l2jmobius.gameserver.data.xml.PetDataTable;
import org.l2jmobius.gameserver.data.xml.PetSkillData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.data.xml.SendMessageLocalisationData;
import org.l2jmobius.gameserver.data.xml.SiegeScheduleData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillLearnData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.data.xml.SpawnData;
import org.l2jmobius.gameserver.data.xml.StaticObjectData;
import org.l2jmobius.gameserver.data.xml.TeleporterData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.CursedWeaponsManager;
import org.l2jmobius.gameserver.managers.FakePlayerChatManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.managers.WalkingManager;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.scripting.ScriptEngine;

/**
 * @author NosBit, Mobius
 */
public class AdminReload implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(AdminReload.class.getName());
	
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_reload",
		"admin_reload_menu",
		"admin_reload_menu_page"
	};
	
	private static final String RELOAD_USAGE = "Usage: //reload <type> [parameters]";
	
	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final StringTokenizer st = new StringTokenizer(command, " ");
		final String actualCommand = st.nextToken();
		// //reload_menu - Opens the reload panel.
		if (actualCommand.equalsIgnoreCase("admin_reload_menu"))
		{
			showReloadMenu(activeChar, 0);
			return true;
		}
		
		// //reload_menu_page <n> - Pagination nav from menu buttons.
		if (actualCommand.equalsIgnoreCase("admin_reload_menu_page"))
		{
			final int page = st.hasMoreTokens() ? Integer.parseInt(st.nextToken()) : 0;
			showReloadMenu(activeChar, page);
			return true;
		}
		
		// //reload <type> [value] - Actual reload, confirmDlg="true" set in admincommands.xml file.
		if (actualCommand.equalsIgnoreCase("admin_reload"))
		{
			if (!st.hasMoreTokens())
			{
				showReloadMenu(activeChar, 0);
				return true;
			}
			
			final String type = st.nextToken();
			switch (type.toLowerCase())
			{
				case "access":
				{
					AdminData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Access.");
					break;
				}
				case "buylist":
				{
					BuyListData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Buylists.");
					break;
				}
				case "category":
				{
					CategoryData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Category data.");
					break;
				}
				case "classlist":
				{
					ClassListData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Class List data.");
					break;
				}
				case "config":
				{
					ConfigLoader.init();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Configs.");
					break;
				}
				case "crest":
				{
					CrestTable.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Crests.");
					break;
				}
				case "cubic":
				{
					CubicData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Cubic data.");
					break;
				}
				case "cw":
				{
					CursedWeaponsManager.getInstance().reload();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Cursed Weapons.");
					break;
				}
				case "door":
				{
					DoorData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Doors.");
					break;
				}
				case "dynamicexprate":
				{
					DynamicExpRateData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Dynamic Exp Rate data.");
					break;
				}
				case "effect":
				{
					try
					{
						ScriptEngine.getInstance().executeScript(ScriptEngine.EFFECT_MASTER_HANDLER_FILE);
						AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded effect master handler.");
					}
					catch (Exception e)
					{
						LOGGER.log(Level.WARNING, "Failed executing effect master handler!", e);
						activeChar.sendSysMessage("Error reloading effect master handler!");
					}
					break;
				}
				case "enchant":
				{
					EnchantItemGroupsData.getInstance().load();
					EnchantItemData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded item enchanting data.");
					break;
				}
				case "enchantitemhpbonus":
				{
					EnchantItemHPBonusData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Enchant Item HPBonus data.");
					break;
				}
				case "enchantskilltree":
				{
					EnchantSkillTreeData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Enchant Skill Tree data.");
					break;
				}
				case "experience":
				{
					ExperienceData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Experience data.");
					break;
				}
				case "experienceloss":
				{
					ExperienceLossData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Experience Loss data.");
					break;
				}
				case "fakeplayerchat":
				{
					FakePlayerChatManager.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Fake Player Chat data.");
					break;
				}
				case "fence":
				{
					FenceData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Fence data.");
					break;
				}
				case "fish":
				{
					FishData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Fish data.");
					break;
				}
				case "fishingmonsters":
				{
					FishingMonstersData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Fishing Monsters data.");
					break;
				}
				case "fishingrods":
				{
					FishingRodsData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Fishing Rods data.");
					break;
				}
				case "handler":
				{
					try
					{
						ScriptEngine.getInstance().executeScript(ScriptEngine.MASTER_HANDLER_FILE);
						AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded master handler.");
					}
					catch (Exception e)
					{
						LOGGER.log(Level.WARNING, "Failed executing master handler!", e);
						activeChar.sendSysMessage("Error reloading master handler!");
					}
					break;
				}
				case "henna":
				{
					HennaData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Henna data.");
					break;
				}
				case "hitconditionbonus":
				{
					HitConditionBonusData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Hit Condition Bonus data.");
					break;
				}
				case "htm":
				case "html":
				{
					if (st.hasMoreElements())
					{
						final String path = st.nextToken();
						final File file = new File(ServerConfig.DATAPACK_ROOT, "data/html/" + path);
						if (file.exists())
						{
							HtmCache.getInstance().reload(file);
							AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Htm File:" + file.getName() + ".");
						}
						else
						{
							activeChar.sendSysMessage("File or Directory does not exist.");
						}
					}
					else
					{
						HtmCache.getInstance().reload();
						activeChar.sendSysMessage("Cache[HTML]: " + HtmCache.getInstance().getMemoryUsage() + " megabytes on " + HtmCache.getInstance().getLoadedFiles() + " files loaded");
						AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Htms.");
					}
					break;
				}
				case "initialequipment":
				{
					InitialEquipmentData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Initial Equipment data.");
					break;
				}
				case "initialshortcut":
				{
					InitialShortcutData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Initial Shortcut data.");
					break;
				}
				case "instance":
				{
					InstanceManager.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Instances data.");
					break;
				}
				case "item":
				{
					ItemData.getInstance().reload();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Items.");
					break;
				}
				case "karmaloss":
				{
					KarmaLossData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Karma Loss data.");
					break;
				}
				case "levelupcrystal":
				{
					LevelUpCrystalData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Level Up Crystal data.");
					break;
				}
				case "localisations":
				{
					SystemMessageId.loadLocalisations();
					SendMessageLocalisationData.getInstance().load();
					NpcNameLocalisationData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Localisation data.");
					break;
				}
				case "mapregion":
				{
					MapRegionData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Map Region data.");
					break;
				}
				case "multisell":
				{
					MultisellData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Multisells.");
					break;
				}
				case "npc":
				{
					NpcData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Npcs.");
					break;
				}
				case "options":
				{
					OptionData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Options data.");
					break;
				}
				case "petdata":
				{
					PetDataTable.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Pet Data data.");
					break;
				}
				case "petskill":
				{
					PetSkillData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Pet Skill data.");
					break;
				}
				case "playertemplate":
				{
					PlayerTemplateData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Player Template data.");
					break;
				}
				case "quest":
				{
					if (st.hasMoreElements())
					{
						final String value = st.nextToken();
						if (!StringUtil.isNumeric(value))
						{
							ScriptManager.getInstance().reload(value);
							AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Quest Name:" + value + ".");
						}
						else
						{
							final int questId = Integer.parseInt(value);
							ScriptManager.getInstance().reload(questId);
							AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Quest ID:" + questId + ".");
						}
					}
					else
					{
						ScriptManager.getInstance().reloadAllScripts();
						activeChar.sendSysMessage("All scripts have been reloaded.");
						AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Quests.");
					}
					break;
				}
				case "recipe":
				{
					RecipeData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Recipe data.");
					break;
				}
				case "sets":
				{
					ArmorSetData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Armor sets data.");
					break;
				}
				case "siegeschedule":
				{
					SiegeScheduleData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Siege Schedule data.");
					break;
				}
				case "skill":
				{
					SkillData.getInstance().reload();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Skills.");
					break;
				}
				case "skilllearn":
				{
					SkillLearnData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Skill Learn data.");
					break;
				}
				case "skilltree":
				{
					SkillTreeData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Skill Tree data.");
					break;
				}
				case "spawn":
				{
					SpawnData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Spawn data.");
					break;
				}
				case "staticobject":
				{
					StaticObjectData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Static Object data.");
					break;
				}
				case "teleport":
				{
					TeleporterData.getInstance().load();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Teleports.");
					break;
				}
				case "walker":
				{
					WalkingManager.getInstance().load();
					activeChar.sendSysMessage("All walkers have been reloaded");
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Walkers.");
					break;
				}
				case "zone":
				{
					ZoneManager.getInstance().reload();
					AdminData.getInstance().broadcastMessageToGMs(activeChar.getName() + ": Reloaded Zones.");
					break;
				}
				default:
				{
					activeChar.sendMessage(RELOAD_USAGE);
					return true;
				}
			}
			
			activeChar.sendSysMessage("WARNING: There are several known issues regarding this feature. Reloading server data during runtime is STRONGLY NOT RECOMMENDED for live servers, just for developing environments.");
		}
		
		return true;
	}
	
	/**
	 * Builds and sends the reload panel HTML with pagination. Commands are sorted A-Z. One column, ITEMS_PER_PAGE rows per page.
	 */
	private static final int ITEMS_PER_PAGE = 20; // Text-link layout for older clients.
	
	// @formatter:off
	private static final List<String[]> RELOAD_COMMANDS = Arrays.asList(
		new String[]{"Access", "admin_reload access"},
		new String[]{"Armor Sets", "admin_reload sets"},
		new String[]{"BuyList", "admin_reload buylist"},
		new String[]{"Category", "admin_reload category"},
		new String[]{"Class List", "admin_reload classlist"},
		new String[]{"Config", "admin_reload config"},
		new String[]{"Crests", "admin_reload crest"},
		new String[]{"Cubic", "admin_reload cubic"},
		new String[]{"Cursed Weapons", "admin_reload cw"},
		new String[]{"Doors", "admin_reload door"},
		new String[]{"Dynamic Exp Rate", "admin_reload dynamicexprate"},
		new String[]{"Effects", "admin_reload effect"},
		new String[]{"Ench Item HP Bonus", "admin_reload enchantitemhpbonus"},
		new String[]{"Ench Skill Tree", "admin_reload enchantskilltree"},
		new String[]{"Enchant", "admin_reload enchant"},
		new String[]{"Experience", "admin_reload experience"},
		new String[]{"Experience Loss", "admin_reload experienceloss"},
		new String[]{"Fake Player Chat", "admin_reload fakeplayerchat"},
		new String[]{"Fence", "admin_reload fence"},
		new String[]{"Fish", "admin_reload fish"},
		new String[]{"Fishing Monsters", "admin_reload fishingmonsters"},
		new String[]{"Fishing Rods", "admin_reload fishingrods"},
		new String[]{"Handler", "admin_reload handler"},
		new String[]{"Henna", "admin_reload henna"},
		new String[]{"Hit Condition Bonus", "admin_reload hitconditionbonus"},
		new String[]{"HTM (path/x.htm)", "admin_reload htm $value"},
		new String[]{"HTML (path/x.htm)", "admin_reload html $value"},
		new String[]{"Initial Equipment", "admin_reload initialequipment"},
		new String[]{"Initial Shortcut", "admin_reload initialshortcut"},
		new String[]{"Instance", "admin_reload instance"},
		new String[]{"Item", "admin_reload item"},
		new String[]{"Karma Loss", "admin_reload karmaloss"},
		new String[]{"LevelUp Crystal", "admin_reload levelupcrystal"},
		new String[]{"Localisations", "admin_reload localisations"},
		new String[]{"Map Region", "admin_reload mapregion"},
		new String[]{"Multisell", "admin_reload multisell"},
		new String[]{"NPC", "admin_reload npc $value"},
		new String[]{"Options", "admin_reload options"},
		new String[]{"Pet Data", "admin_reload petdata"},
		new String[]{"Pet Skill", "admin_reload petskill"},
		new String[]{"Player Template", "admin_reload playertemplate"},
		new String[]{"Quest", "admin_reload quest $value"},
		new String[]{"Recipe", "admin_reload recipe"},
		new String[]{"Siege Schedule", "admin_reload siegeschedule"},
		new String[]{"Skill", "admin_reload skill"},
		new String[]{"Skill Learn", "admin_reload skilllearn"},
		new String[]{"Skill Tree", "admin_reload skilltree"},
		new String[]{"Spawn", "admin_reload spawn"},
		new String[]{"Static Objects", "admin_reload staticobject"},
		new String[]{"Teleport", "admin_reload teleport"},
		new String[]{"Walker", "admin_reload walker"},
		new String[]{"Zone", "admin_reload zone"}
	);
	// @formatter:on
	
	private void showReloadMenu(Player activeChar, int page)
	{
		String content = HtmCache.getInstance().getHtm(activeChar, "data/html/admin/reload.htm");
		final String buttons = generateReloadButtons(page);
		content = content.replace("%buttons%", buttons);
		activeChar.sendPacket(new NpcHtmlMessage(0, 1, content));
	}
	
	private String generateReloadButtons(int page)
	{
		final int total = RELOAD_COMMANDS.size();
		final int totalPages = (int) Math.ceil((double) total / ITEMS_PER_PAGE);
		final int start = page * ITEMS_PER_PAGE;
		final int end = Math.min(start + ITEMS_PER_PAGE, total);
		
		final StringBuilder html = new StringBuilder();
		
		// Value input field.
		html.append("<table width=260 cellspacing=0 cellpadding=0><tr>");
		html.append("<td fixwidth=50>Value:</td>");
		html.append("<td fixwidth=150 height=25><edit var=\"value\" width=148></td>");
		html.append("</tr></table><br>");
		
		// Plain text link list (older clients do not stretch L2UI buttons to fit long labels).
		for (int i = start; i < end; i++)
		{
			final String[] cmd = RELOAD_COMMANDS.get(i);
			html.append("<a action=\"bypass ").append(cmd[1]).append("\">").append(cmd[0]).append("</a><br1>");
		}
		
		// Pagination bar.
		if (totalPages > 1)
		{
			html.append("<br><table width=260><tr>");
			
			html.append("<td width=80 align=center>");
			if (page > 0)
			{
				html.append("<button value=\"Prev\" action=\"bypass -h admin_reload_menu_page ").append(page - 1);
				html.append("\" width=75 height=21 back=\"L2UI_ch3.smallbutton2_over\" fore=\"L2UI_ch3.smallbutton2\">");
			}
			else
			{
				html.append("<button value=\"Prev\" action=\"\" width=75 height=21 back=\"L2UI_ch3.smallbutton2_over\" fore=\"L2UI_ch3.smallbutton2\">");
			}
			html.append("</td>");
			
			html.append("<td width=100 align=center><font color=\"LEVEL\">Page ").append(page + 1).append(" / ").append(totalPages).append("</font></td>");
			
			html.append("<td width=80 align=center>");
			if (page < (totalPages - 1))
			{
				html.append("<button value=\"Next\" action=\"bypass -h admin_reload_menu_page ").append(page + 1);
				html.append("\" width=75 height=21 back=\"L2UI_ch3.smallbutton2_over\" fore=\"L2UI_ch3.smallbutton2\">");
			}
			else
			{
				html.append("<button value=\"Next\" action=\"\" width=75 height=21 back=\"L2UI_ch3.smallbutton2_over\" fore=\"L2UI_ch3.smallbutton2\">");
			}
			html.append("</td>");
			
			html.append("</tr></table>");
		}
		
		return html.toString();
	}
	
	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}

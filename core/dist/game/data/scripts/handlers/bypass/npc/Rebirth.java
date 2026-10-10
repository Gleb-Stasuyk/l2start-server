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
package handlers.bypass.npc;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.managers.RebirthManager;

/**
 * Handles rebirth-related bypass commands and delegates each action to the rebirth manager.<br>
 * Supports menu display, rebirth confirmation, skill selection, skill preview, skill acquisition and skill reset requests.
 * <ul>
 * <li>Validates required command and player references.</li>
 * <li>Extracts target object id from bypass origin when available.</li>
 * <li>Parses skill identifiers from parameterized bypass commands.</li>
 * </ul>
 * @author BazookaRpm
 */
public final class Rebirth implements IBypassHandler
{
	// Bypass commands.
	private static final String[] COMMANDS =
	{
		"rebirth_openmenu",
		"rebirth_confirmrequest",
		"rebirth_selectskills",
		"rebirth_previewSkill",
		"rebirth_confirmSkill",
		"rebirth_resetSkill"
	};
	
	/**
	 * Processes the requested rebirth bypass command.
	 * @param command
	 * @param player
	 * @param bypassOrigin
	 * @return {@code true} if the command was handled, {@code false} otherwise.
	 */
	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		if ((command == null) || (player == null))
		{
			return false;
		}
		
		final int objectId = (bypassOrigin != null) ? bypassOrigin.getObjectId() : 0;
		
		if (command.equals("rebirth_openmenu"))
		{
			RebirthManager.getInstance().displayMainWindow(player, objectId);
			return true;
		}
		else if (command.equals("rebirth_confirmrequest"))
		{
			RebirthManager.getInstance().requestRebirth(player);
			return true;
		}
		else if (command.equals("rebirth_selectskills"))
		{
			RebirthManager.getInstance().displaySkillSelectionWindow(player, objectId);
			return true;
		}
		else if (command.startsWith("rebirth_previewSkill"))
		{
			final Integer skillId = parseSkillId(command);
			if (skillId == null)
			{
				player.sendMessage("Invalid skill.");
				return true;
			}
			
			RebirthManager.getInstance().displaySkillPreviewWindow(player, objectId, skillId.intValue());
			return true;
		}
		else if (command.startsWith("rebirth_confirmSkill"))
		{
			final Integer skillId = parseSkillId(command);
			if (skillId == null)
			{
				player.sendMessage("Invalid skill.");
				return true;
			}
			
			RebirthManager.getInstance().acquireRebirthSkill(player, objectId, skillId.intValue());
			return true;
		}
		else if (command.startsWith("rebirth_resetSkill"))
		{
			final Integer skillId = parseSkillId(command);
			if (skillId == null)
			{
				player.sendMessage("Invalid skill.");
				return true;
			}
			
			RebirthManager.getInstance().resetRebirthSkill(player, objectId, skillId.intValue());
			return true;
		}
		
		return false;
	}
	
	/**
	 * Parses the skill id from a parameterized bypass command.
	 * @param command
	 * @return The parsed skill id, or {@code null} if parsing fails.
	 */
	private Integer parseSkillId(String command)
	{
		try
		{
			final int spaceIndex = command.indexOf(' ');
			if (spaceIndex > 0)
			{
				return Integer.valueOf(Integer.parseInt(command.substring(spaceIndex + 1).trim()));
			}
		}
		catch (Exception e)
		{
		}
		
		return null;
	}
	
	/**
	 * Returns the list of supported rebirth bypass commands.
	 * @return The supported command list.
	 */
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
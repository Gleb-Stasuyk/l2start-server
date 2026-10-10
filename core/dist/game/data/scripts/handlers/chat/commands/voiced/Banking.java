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
package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.config.custom.BankingConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.itemcontainer.PlayerInventory;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;

/**
 * This class trades Gold Bars for Adena and vice versa.
 * @author Ahmed, CostyKiller, Mobius
 */
public class Banking implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"bank",
		"withdraw",
		"deposit"
	};
	
	@Override
	public boolean onCommand(String command, Player activeChar, String params)
	{
		final String goldBarText = "Gold " + (BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT > 1 ? "Bars" : "Bar");
		
		if (command.equals("bank"))
		{
			activeChar.sendMessage("Use .deposit to convert " + BankingConfig.BANKING_SYSTEM_ADENA_COUNT + " Adena to " + BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT + " " + goldBarText + ".");
			activeChar.sendMessage("Use .withdraw to convert " + BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT + " " + goldBarText + " to " + BankingConfig.BANKING_SYSTEM_ADENA_COUNT + " Adena.");
		}
		else if (command.equals("deposit"))
		{
			final PlayerInventory inventory = activeChar.getInventory();
			final long currentAdena = inventory.getInventoryItemCount(Inventory.ADENA_ID, 0);
			if (currentAdena >= BankingConfig.BANKING_SYSTEM_ADENA_COUNT)
			{
				if (!activeChar.reduceAdena(ItemProcessType.BUY, BankingConfig.BANKING_SYSTEM_ADENA_COUNT, activeChar, false))
				{
					return false;
				}
				
				inventory.addItem(ItemProcessType.COMPENSATE, 3470, BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT, activeChar, null);
				inventory.updateDatabase();
				
				activeChar.sendMessage("Successfully converted " + BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT + " " + goldBarText + " into " + BankingConfig.BANKING_SYSTEM_ADENA_COUNT + " Adena.");
			}
			else
			{
				final long needed = BankingConfig.BANKING_SYSTEM_ADENA_COUNT - currentAdena;
				activeChar.sendMessage("You don't have enough Adena to convert to " + goldBarText + ".");
				activeChar.sendMessage("You need " + BankingConfig.BANKING_SYSTEM_ADENA_COUNT + " Adena but you only have " + currentAdena + ". You need " + needed + " more.");
			}
		}
		else if (command.equals("withdraw"))
		{
			final PlayerInventory inventory = activeChar.getInventory();
			final long currentBars = inventory.getInventoryItemCount(3470, 0);
			if (currentBars >= BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT)
			{
				if (!activeChar.destroyItemByItemId(ItemProcessType.SELL, 3470, BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT, activeChar, false))
				{
					return false;
				}
				
				inventory.addAdena(ItemProcessType.COMPENSATE, BankingConfig.BANKING_SYSTEM_ADENA_COUNT, activeChar, null);
				inventory.updateDatabase();
				
				activeChar.sendMessage("Successfully converted " + BankingConfig.BANKING_SYSTEM_ADENA_COUNT + " Adena to " + BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT + " " + goldBarText + ".");
			}
			else
			{
				final long needed = BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT - currentBars;
				activeChar.sendMessage("You don't have enough Gold Bars to convert to Adena.");
				activeChar.sendMessage("You need " + BankingConfig.BANKING_SYSTEM_GOLDBAR_COUNT + " " + goldBarText + " but you only have " + currentBars + ". You need " + needed + " more.");
			}
		}
		
		return true;
	}
	
	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}

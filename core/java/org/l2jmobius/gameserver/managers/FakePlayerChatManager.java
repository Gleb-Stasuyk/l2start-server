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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.holders.FakePlayerChatHolder;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * @author Mobius
 */
public class FakePlayerChatManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerChatManager.class.getName());
	
	private static final List<FakePlayerChatHolder> MESSAGES = new ArrayList<>();
	private static final int MIN_DELAY = 5000;
	private static final int MAX_DELAY = 15000;
	
	protected FakePlayerChatManager()
	{
		load();
	}
	
	@Override
	public void load()
	{
		if (FakePlayersConfig.FAKE_PLAYERS_ENABLED)
		{
			FakePlayerData.getInstance().report();
			if (FakePlayersConfig.FAKE_PLAYER_CHAT)
			{
				MESSAGES.clear();
				parseDatapackFile("data/FakePlayerChatData.xml");
				LOGGER.info(getClass().getSimpleName() + ": Loaded " + MESSAGES.size() + " chat templates.");
			}
		}
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "fakePlayerChat", fakePlayerChatNode ->
		{
			// Parse attributes of the "fakePlayerChat" element.
			final StatSet set = new StatSet(parseAttributes(fakePlayerChatNode));
			
			// Add a new FakePlayerChatHolder to the MESSAGES collection.
			MESSAGES.add(new FakePlayerChatHolder(set.getString("fpcName"), set.getString("searchMethod"), set.getString("searchText"), set.getString("answers")));
		}));
	}
	
	public void manageChat(Player player, String fpcName, String message)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(MIN_DELAY, MAX_DELAY));
	}
	
	public void manageChat(Player player, String fpcName, String message, int minDelay, int maxDelay)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(minDelay, maxDelay));
	}
	
	private void manageResponce(Player player, String fpcName, String message)
	{
		if (player == null)
		{
			return;
		}
		
		final String text = message.toLowerCase();
		
		// tricky question
		if (text.contains("can you see me"))
		{
			final Spawn spawn = SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(fpcName));
			if (spawn != null)
			{
				final Npc npc = spawn.getLastSpawn();
				if (npc != null)
				{
					if (npc.calculateDistance2D(player) < 3000)
					{
						if (GeoEngine.getInstance().canSeeTarget(npc, player) && !player.isInvisible())
						{
							sendChat(player, fpcName, Rnd.nextBoolean() ? "i am not blind" : Rnd.nextBoolean() ? "of course i can" : "yes");
						}
						else
						{
							sendChat(player, fpcName, Rnd.nextBoolean() ? "i know you are around" : Rnd.nextBoolean() ? "not at the moment :P" : "no, where are you?");
						}
					}
					else
					{
						sendChat(player, fpcName, Rnd.nextBoolean() ? "nope, can't see you" : Rnd.nextBoolean() ? "nope" : "no");
					}
					return;
				}
			}
		}
		
		for (FakePlayerChatHolder chatHolder : MESSAGES)
		{
			if (!chatHolder.getFpcName().equals(fpcName) && !chatHolder.getFpcName().equals("ALL"))
			{
				continue;
			}
			
			switch (chatHolder.getSearchMethod())
			{
				case "EQUALS":
				{
					if (text.equals(chatHolder.getSearchText().get(0)))
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
				case "STARTS_WITH":
				{
					if (text.startsWith(chatHolder.getSearchText().get(0)))
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
				case "CONTAINS":
				{
					boolean allFound = true;
					for (String word : chatHolder.getSearchText())
					{
						if (!text.contains(word))
						{
							allFound = false;
						}
					}
					
					if (allFound)
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
			}
		}
	}
	
	public void sendChat(Player player, String fpcName, String message)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(fpcName));
		if (spawn != null)
		{
			final Npc npc = spawn.getLastSpawn();
			if (npc != null)
			{
				player.sendPacket(new CreatureSay(npc, ChatType.WHISPER, fpcName, message));
			}
		}
	}
	
	public static FakePlayerChatManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePlayerChatManager INSTANCE = new FakePlayerChatManager();
	}
}

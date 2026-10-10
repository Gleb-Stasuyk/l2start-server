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
package quests.Q00503_PursuitOfClanAmbition;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.script.Quest;
import org.l2jmobius.gameserver.mechanics.script.QuestSound;
import org.l2jmobius.gameserver.mechanics.script.QuestState;
import org.l2jmobius.gameserver.mechanics.script.State;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.NpcSay;
import org.l2jmobius.gameserver.network.serverpackets.TeleportToLocation;

/**
 * @author Jackass, Skache
 */
public class Q00503_PursuitOfClanAmbition extends Quest
{
	// NPCs
	private static final int MARTIEN = 30645;
	private static final int ATHREA = 30758;
	private static final int KALIS = 30759;
	private static final int SIR_GUSTAV_ATHEBALDT = 30760;
	private static final int CORPSE_OF_FRITZ = 30761;
	private static final int CORPSE_OF_LUTZ = 30762;
	private static final int CORPSE_OF_KURTZ = 30763;
	private static final int KUSTO = 30512;
	private static final int BALTHAZAR = 30764;
	private static final int SIR_ERIC_RODEMAI = 30868;
	private static final int IMPERIAL_COFFER = 30765;
	private static final int CLEO = 30766;
	
	// Monsters
	private static final int THUNDER_WYRM = 20243;
	private static final int THUNDER_WYRM_HOLD = 20282;
	private static final int DRAKE = 20137;
	private static final int DRAKE_HOLD = 20285;
	private static final int BLITZ_WYRM = 27178;
	private static final int LESSER_GIANT_SOLDIER = 20654;
	private static final int LESSER_GIANT_SCOUT = 20656;
	private static final int GRAVE_GUARD = 20668;
	private static final int GRAVE_KEYMASTER = 27179;
	private static final int IMPERIAL_SLAVE = 27180;
	private static final int IMPERIAL_GRAVEKEEPER = 27181;
	
	// Imperial Gravekeeper teleports
	private static final int TELEPORT_1_X = 179520;
	private static final int TELEPORT_1_Y = 6464;
	private static final int TELEPORT_1_Z = -2706;
	private static final int TELEPORT_2_X = 171104;
	private static final int TELEPORT_2_Y = 6496;
	private static final int TELEPORT_2_Z = -2706;
	
	// Skill
	private static final int DARK_HEAL_SKILL = 4080;
	
	// First part items
	private static final int GUSTAVS_1ST_LETTER = 3866;
	private static final int MIST_DRAKE_EGG = 3839;
	private static final int BLITZ_WYRM_EGG = 3840;
	private static final int DRAKE_EGG = 3841;
	private static final int THUNDER_WYRM_EGG = 3842;
	private static final int BROOCH_OF_THE_MAGPIE = 3843;
	private static final int BLACK_ANVIL_COIN = 3871;
	
	// Second part items
	private static final int GUSTAVS_2ND_LETTER = 3867;
	private static final int RECIPE_TITANS_POWERSTONE = 3838;
	private static final int NEBULITE_CRYSTALS = 3844;
	private static final int BROKEN_TITANS_POWERSTONE = 3845;
	private static final int TITANS_POWERSTONE = 3846;
	
	// Third part items
	private static final int GUSTAVS_3RD_LETTER = 3868;
	private static final int SCEPTER_OF_JUDGMENT = 3869;
	private static final int IMPERIAL_KEY = 3847;
	
	// Final item
	private static final int SEAL_OF_ASPIRATION = 3870;
	
	// Droplist
	private static final int[][] DROPLIST =
	{
		// npcId, cond, MaxCount, chance, item1
		// @formatter:off
		{THUNDER_WYRM, 2, 10, 500000, THUNDER_WYRM_EGG},
		{THUNDER_WYRM_HOLD, 2, 10, 500000, THUNDER_WYRM_EGG},
		{DRAKE, 2, 10, 500000, DRAKE_EGG},
		{DRAKE_HOLD, 2, 10, 500000, DRAKE_EGG},
		{BLITZ_WYRM, 2, 10, 1000000, BLITZ_WYRM_EGG},
		// @formatter:on
	};
	
	public Q00503_PursuitOfClanAmbition()
	{
		super(503, "Pursuit of Clan Ambition!");
		registerQuestItems(MIST_DRAKE_EGG, BLITZ_WYRM_EGG, DRAKE_EGG, THUNDER_WYRM_EGG, BROOCH_OF_THE_MAGPIE, NEBULITE_CRYSTALS, BROKEN_TITANS_POWERSTONE, TITANS_POWERSTONE, IMPERIAL_KEY, GUSTAVS_1ST_LETTER, GUSTAVS_2ND_LETTER, GUSTAVS_3RD_LETTER, SCEPTER_OF_JUDGMENT);
		addStartNpc(SIR_GUSTAV_ATHEBALDT);
		addTalkId(MARTIEN, ATHREA, KALIS, SIR_GUSTAV_ATHEBALDT, CORPSE_OF_FRITZ, CORPSE_OF_LUTZ, CORPSE_OF_KURTZ, KUSTO, BALTHAZAR, SIR_ERIC_RODEMAI, IMPERIAL_COFFER, CLEO);
		addKillId(THUNDER_WYRM_HOLD, THUNDER_WYRM, DRAKE, DRAKE_HOLD, BLITZ_WYRM, LESSER_GIANT_SOLDIER, LESSER_GIANT_SCOUT, GRAVE_GUARD, GRAVE_KEYMASTER, IMPERIAL_GRAVEKEEPER);
		addAttackId(IMPERIAL_GRAVEKEEPER);
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		String htmltext = event;
		final QuestState st = getQuestState(player, false);
		if (st == null)
		{
			return htmltext;
		}
		
		switch (event)
		{
			case "30760-08.htm":
			{
				st.startQuest();
				giveItems(player, GUSTAVS_1ST_LETTER, 1);
				break;
			}
			case "30760-12.htm":
			{
				giveItems(player, GUSTAVS_2ND_LETTER, 1);
				st.setCond(4);
				break;
			}
			case "30760-16.htm":
			{
				giveItems(player, GUSTAVS_3RD_LETTER, 1);
				st.setCond(7);
				break;
			}
			case "30760-20.htm":
			{
				takeItems(player, SCEPTER_OF_JUDGMENT, -1);
				giveItems(player, SEAL_OF_ASPIRATION, 1);
				addExpAndSp(player, 0, 250000);
				st.exitQuest(false, true);
				finishQuestToClan(player);
				break;
			}
			case "30760-22.htm":
			{
				if ((st.getCond() == 11) || hasQuestItems(player, SCEPTER_OF_JUDGMENT))
				{
					st.setCond(13);
				}
				break;
			}
			case "30760-23.htm":
			{
				takeItems(player, SCEPTER_OF_JUDGMENT, -1);
				giveItems(player, SEAL_OF_ASPIRATION, 1);
				addExpAndSp(player, 0, 250000);
				st.exitQuest(false, true);
				finishQuestToClan(player);
				break;
			}
			case "30645-03.htm":
			{
				setQuestToClanMembers(player);
				takeItems(player, GUSTAVS_1ST_LETTER, -1);
				st.setCond(2);
				st.set("kurt", "0");
				break;
			}
			case "30763-02.htm":
			{
				giveItems(player, MIST_DRAKE_EGG, 6);
				giveItems(player, BROOCH_OF_THE_MAGPIE, 1);
				st.set("kurt", "1");
				break;
			}
			case "30762-02.htm":
			{
				giveItems(player, MIST_DRAKE_EGG, 4);
				giveItems(player, BLITZ_WYRM_EGG, 3);
				addSpawn(BLITZ_WYRM, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0);
				addSpawn(BLITZ_WYRM, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0);
				st.set("lutz", "1");
				break;
			}
			case "30761-02.htm":
			{
				giveItems(player, BLITZ_WYRM_EGG, 3);
				addSpawn(BLITZ_WYRM, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0);
				addSpawn(BLITZ_WYRM, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0);
				st.set("fritz", "1");
				break;
			}
			case "30512-03.htm":
			{
				takeItems(player, BROOCH_OF_THE_MAGPIE, 1);
				giveItems(player, BLACK_ANVIL_COIN, 1);
				st.set("kurt", "2");
				break;
			}
			case "30764-03.htm":
			{
				takeItems(player, GUSTAVS_2ND_LETTER, -1);
				st.setCond(5);
				break;
			}
			case "30764-05.htm":
			{
				takeItems(player, GUSTAVS_2ND_LETTER, -1);
				st.setCond(5);
				break;
			}
			case "30764-06.htm":
			{
				takeItems(player, BLACK_ANVIL_COIN, -1);
				giveItems(player, RECIPE_TITANS_POWERSTONE, 1);
				break;
			}
			case "30868-04.htm":
			{
				takeItems(player, GUSTAVS_3RD_LETTER, -1);
				st.setCond(8);
				break;
			}
			case "30868-06a.htm":
			{
				st.setCond(10);
				break;
			}
			case "30868-10.htm":
			{
				st.setCond(11);
				break;
			}
			case "30766-04.htm":
			{
				st.setCond(9);
				npc.broadcastPacket(new NpcSay(npc, ChatType.NPC_GENERAL, "Blood and honor!"));
				final Npc sister1 = addSpawn(KALIS, 160665, 21209, -3710, npc.getHeading(), false, 180000);
				sister1.broadcastPacket(new NpcSay(sister1, ChatType.NPC_GENERAL, "Ambition and power!"));
				final Npc sister2 = addSpawn(ATHREA, 160665, 21291, -3710, npc.getHeading(), false, 180000);
				sister2.broadcastPacket(new NpcSay(sister2, ChatType.NPC_GENERAL, "War and death!"));
				break;
			}
			case "OPEN":
			{
				if (!player.isClanLeader() || !st.isStarted() || (st.getCond() != 10) || hasQuestItems(player, SCEPTER_OF_JUDGMENT))
				{
					htmltext = "30765-02.htm";
				}
				else if (getQuestItemsCount(player, IMPERIAL_KEY) < 6)
				{
					htmltext = "30765-03a.htm";
				}
				else
				{
					htmltext = "30765-03.htm";
					st.setCond(11);
					takeItems(player, IMPERIAL_KEY, 6);
					giveItems(player, SCEPTER_OF_JUDGMENT, 1);
				}
				break;
			}
		}
		
		return htmltext;
	}
	
	@Override
	public String onTalk(Npc npc, Player player)
	{
		final QuestState leaderSt = getClanLeaderQuestState(player, npc);
		if ((npc.getId() == IMPERIAL_COFFER) && !player.isClanLeader() && (leaderSt != null) && leaderSt.isStarted() && (leaderSt.getCond() == 10))
		{
			return "30765-02.htm";
		}
		
		String htmltext = getNoQuestMsg(player);
		final QuestState st = getQuestState(player, true);
		int state = st.getState();
		if (!player.isClanLeader() && (state == State.CREATED) && (leaderSt != null) && leaderSt.isStarted())
		{
			state = State.STARTED;
		}
		
		switch (state)
		{
			case State.CREATED:
			{
				if (npc.getId() != SIR_GUSTAV_ATHEBALDT)
				{
					break;
				}
				if (player.getClan() == null)
				{
					htmltext = "30760-01.htm";
					st.exitQuest(true);
				}
				else if (player.isClanLeader())
				{
					if (hasQuestItems(player, SEAL_OF_ASPIRATION))
					{
						htmltext = "30760-03.htm";
						st.exitQuest(true);
					}
					else if (player.getClan().getLevel() != 4)
					{
						htmltext = "30760-02.htm";
						st.exitQuest(true);
					}
					else
					{
						htmltext = "30760-04.htm";
					}
				}
				else
				{
					htmltext = "30760-04t.htm";
					st.exitQuest(true);
				}
				break;
			}
			case State.STARTED:
			{
				final int cond = st.getCond();
				int memberCond = 0;
				if (leaderSt != null)
				{
					memberCond = leaderSt.getCond();
				}
				
				switch (npc.getId())
				{
					case SIR_GUSTAV_ATHEBALDT:
					{
						if (player.isClanLeader())
						{
							if (cond == 1)
							{
								htmltext = "30760-09.htm";
							}
							else if (cond == 2)
							{
								htmltext = "30760-10.htm";
							}
							else if (cond == 3)
							{
								htmltext = "30760-11.htm";
							}
							else if (cond == 4)
							{
								htmltext = "30760-13.htm";
							}
							else if (cond == 5)
							{
								htmltext = "30760-14.htm";
							}
							else if (cond == 6)
							{
								htmltext = "30760-15.htm";
							}
							else if (cond == 7)
							{
								htmltext = "30760-17.htm";
							}
							else if (cond == 11)
							{
								htmltext = "30760-19.htm";
							}
							else if (cond == 13)
							{
								htmltext = "30760-24.htm";
							}
							else if (hasQuestItems(player, SCEPTER_OF_JUDGMENT))
							{
								htmltext = "30760-19.htm";
							}
							else
							{
								htmltext = "30760-18.htm";
							}
						}
						else
						{
							if (memberCond == 3)
							{
								htmltext = "30760-11t.htm";
							}
							else if (memberCond == 4)
							{
								htmltext = "30760-15t.htm";
							}
							else if (memberCond == 11)
							{
								htmltext = "30760-19t.htm";
							}
							else if (memberCond == 13)
							{
								htmltext = "30760-24t.htm";
							}
						}
						break;
					}
					case MARTIEN:
					{
						if (player.isClanLeader())
						{
							if (cond == 1)
							{
								htmltext = "30645-02.htm";
							}
							else if (cond == 2)
							{
								if ((getQuestItemsCount(player, MIST_DRAKE_EGG) > 9) && (getQuestItemsCount(player, BLITZ_WYRM_EGG) > 9) && (getQuestItemsCount(player, DRAKE_EGG) > 9) && (getQuestItemsCount(player, THUNDER_WYRM_EGG) > 9))
								{
									htmltext = "30645-05.htm";
									st.setCond(3);
									takeItems(player, MIST_DRAKE_EGG, -1);
									takeItems(player, BLITZ_WYRM_EGG, -1);
									takeItems(player, DRAKE_EGG, -1);
									takeItems(player, THUNDER_WYRM_EGG, -1);
								}
								else
								{
									htmltext = "30645-04.htm";
								}
							}
							else if (cond == 3)
							{
								htmltext = "30645-07.htm";
							}
							else
							{
								htmltext = "30645-08.htm";
							}
						}
						else
						{
							if ((memberCond == 1) || (memberCond == 2) || (memberCond == 3))
							{
								htmltext = "30645-01.htm";
							}
						}
						break;
					}
					case CORPSE_OF_LUTZ:
					{
						if (player.isClanLeader() && (cond == 2))
						{
							if (st.getInt("lutz") == 1)
							{
								htmltext = "30762-03.htm";
							}
							else
							{
								htmltext = "30762-01.htm";
							}
						}
						break;
					}
					case CORPSE_OF_KURTZ:
					{
						if (player.isClanLeader() && (cond == 2))
						{
							if (st.getInt("kurt") == 1)
							{
								htmltext = "30763-03.htm";
							}
							else
							{
								htmltext = "30763-01.htm";
							}
						}
						break;
					}
					case CORPSE_OF_FRITZ:
					{
						if (player.isClanLeader() && (cond == 2))
						{
							if (st.getInt("fritz") == 1)
							{
								htmltext = "30761-03.htm";
							}
							else
							{
								htmltext = "30761-01.htm";
							}
						}
						break;
					}
					case KUSTO:
					{
						if (player.isClanLeader())
						{
							if (getQuestItemsCount(player, BROOCH_OF_THE_MAGPIE) == 1)
							{
								if (st.getInt("kurt") == 0)
								{
									htmltext = "30512-01.htm";
								}
								else if (st.getInt("kurt") == 1)
								{
									htmltext = "30512-02.htm";
								}
								else
								{
									htmltext = "30512-04.htm";
								}
							}
						}
						else
						{
							if ((memberCond > 2) && (memberCond < 6))
							{
								htmltext = "30512-01a.htm";
							}
						}
						break;
					}
					case BALTHAZAR:
					{
						if (player.isClanLeader())
						{
							if (cond == 4)
							{
								if (st.getInt("kurt") == 2)
								{
									htmltext = "30764-04.htm";
								}
								else
								{
									htmltext = "30764-02.htm";
								}
							}
							else if (cond == 5)
							{
								if ((getQuestItemsCount(player, TITANS_POWERSTONE) > 9) && (getQuestItemsCount(player, NEBULITE_CRYSTALS) > 9))
								{
									htmltext = "30764-08.htm";
									takeItems(player, TITANS_POWERSTONE, -1);
									takeItems(player, NEBULITE_CRYSTALS, -1);
									takeItems(player, BROOCH_OF_THE_MAGPIE, -1);
									st.setCond(6);
								}
								else
								{
									htmltext = "30764-07.htm";
								}
							}
							else if (cond == 6)
							{
								htmltext = "30764-09.htm";
							}
						}
						else
						{
							if (memberCond == 4)
							{
								htmltext = "30764-01.htm";
							}
						}
						break;
					}
					case SIR_ERIC_RODEMAI:
					{
						if (player.isClanLeader())
						{
							if (cond == 7)
							{
								htmltext = "30868-02.htm";
							}
							else if (cond == 8)
							{
								htmltext = "30868-05.htm";
							}
							else if (cond == 9)
							{
								htmltext = "30868-06.htm";
							}
							else if (cond == 10)
							{
								htmltext = "30868-08.htm";
							}
							else if (cond == 11)
							{
								htmltext = "30868-09.htm";
							}
							else if (cond == 12)
							{
								htmltext = "30868-11.htm";
							}
						}
						else
						{
							if (memberCond == 7)
							{
								htmltext = "30868-01.htm";
							}
							else if ((memberCond == 9) || (memberCond == 10))
							{
								htmltext = "30868-07.htm";
							}
						}
						break;
					}
					case CLEO:
					{
						if (player.isClanLeader())
						{
							if (cond == 8)
							{
								htmltext = "30766-02.htm";
							}
							else if (cond == 9)
							{
								htmltext = "30766-05.htm";
							}
							else if (cond == 10)
							{
								htmltext = "30766-06.htm";
							}
							else if ((cond == 11) || (cond == 12) || (cond == 13))
							{
								htmltext = "30766-07.htm";
							}
						}
						else
						{
							if (memberCond == 8)
							{
								htmltext = "30766-01.htm";
							}
						}
						break;
					}
					case IMPERIAL_COFFER:
					{
						if (player.isClanLeader())
						{
							if ((cond == 10) && !hasQuestItems(player, SCEPTER_OF_JUDGMENT))
							{
								htmltext = getQuestItemsCount(player, IMPERIAL_KEY) >= 6 ? "30765-01.htm" : "30765-03a.htm";
							}
						}
						else
						{
							if (memberCond == 10)
							{
								htmltext = "30765-02.htm";
							}
						}
						break;
					}
					case KALIS:
					{
						if (player.isClanLeader())
						{
							htmltext = "30759-01.htm";
						}
						break;
					}
					case ATHREA:
					{
						if (player.isClanLeader())
						{
							htmltext = "30758-01.htm";
						}
						break;
					}
				}
				break;
			}
		}
		
		return htmltext;
	}
	
	@Override
	public void onAttack(Npc npc, Player attacker, int damage, boolean isSummon, Skill skill)
	{
		if (npc.isDead() || (npc.getId() != IMPERIAL_GRAVEKEEPER))
		{
			return;
		}
		
		final Player realAttacker = isSummon && (attacker.asPlayer() != null) ? attacker.asPlayer() : attacker;
		if ((realAttacker == null) || !realAttacker.isOnline())
		{
			return;
		}
		
		final QuestState qs = getClanLeaderQuestState(realAttacker, npc);
		if ((qs == null) || !qs.isStarted() || (qs.getCond() != 10))
		{
			return;
		}
		
		final double hpPercent = (npc.getCurrentHp() / npc.getMaxHp()) * 100.0;
		
		final int lastSpawnTrigger = npc.getVariables().getInt("lastSpawnTrigger", 100);
		if ((lastSpawnTrigger > 80) && (hpPercent <= 80))
		{
			spawnImperialSlaves(npc);
			npc.getVariables().set("lastSpawnTrigger", 80);
		}
		else if ((lastSpawnTrigger > 40) && (hpPercent <= 40))
		{
			spawnImperialSlaves(npc);
			npc.getVariables().set("lastSpawnTrigger", 40);
		}
		else if ((lastSpawnTrigger > 20) && (hpPercent <= 20))
		{
			spawnImperialSlaves(npc);
			npc.getVariables().set("lastSpawnTrigger", 20);
		}
		
		final int lastTeleport = npc.getVariables().getInt("lastTeleport", 100);
		if ((lastTeleport > 50) && (hpPercent <= 50))
		{
			npc.asAttackable().setCanReturnToSpawnPoint(false);
			teleportImperialGravekeeper(npc, TELEPORT_1_X, TELEPORT_1_Y, TELEPORT_1_Z);
			npc.getVariables().set("lastTeleport", 50);
		}
		else if ((lastTeleport > 30) && (hpPercent <= 30))
		{
			npc.asAttackable().setCanReturnToSpawnPoint(false);
			teleportImperialGravekeeper(npc, TELEPORT_2_X, TELEPORT_2_Y, TELEPORT_2_Z);
			npc.getVariables().set("lastTeleport", 30);
		}
		
		final Skill selfHealSkill = SkillData.getInstance().getSkill(DARK_HEAL_SKILL, 1);
		if (selfHealSkill != null)
		{
			final long now = System.currentTimeMillis();
			final long lastCast = npc.getVariables().getLong("lastDarkHealCast", 0);
			final long reuseDelay = 60000;
			
			if (((now - lastCast) >= reuseDelay) && !npc.isCastingNow() && !npc.isSkillDisabled(selfHealSkill) && (npc.getCurrentMp() >= selfHealSkill.getMpConsume()) && (npc.getCurrentHp() > selfHealSkill.getHpConsume()))
			{
				npc.setTarget(npc);
				npc.getAI().setIntentionCast(selfHealSkill, npc);
				npc.getVariables().set("lastDarkHealCast", now);
			}
		}
	}
	
	@Override
	public void onKill(Npc npc, Player player, boolean isPet)
	{
		QuestState st = null;
		st = getClanLeaderQuestState(player, npc);
		if ((st == null) || !st.isStarted())
		{
			return;
		}
		
		int npcId = npc.getId();
		int cond = st.getCond();
		
		if (npcId == IMPERIAL_GRAVEKEEPER)
		{
			final Player clanLeader = st.getPlayer();
			if ((clanLeader != null) && clanLeader.isOnline() && (cond == 10) && !hasQuestItems(clanLeader, SCEPTER_OF_JUDGMENT) && (clanLeader.calculateDistance3D(npc) <= 1500))
			{
				final Npc coffer = addSpawn(IMPERIAL_COFFER, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 180000);
				coffer.broadcastPacket(new NpcSay(coffer, ChatType.NPC_GENERAL, "Curse of the gods on the one that defiles the property of the empire!"));
			}
			return;
		}
		
		if (npcId == GRAVE_GUARD)
		{
			final Player clanLeader = st.getPlayer();
			if ((clanLeader != null) && clanLeader.isOnline() && (cond == 10) && !hasQuestItems(clanLeader, SCEPTER_OF_JUDGMENT) && (clanLeader.calculateDistance3D(npc) <= 1500))
			{
				int graveGuardKills = st.getInt("grave_guard_kills") + 1;
				st.set("grave_guard_kills", graveGuardKills);
				if (((graveGuardKills >= 5) && (getRandom(100) < 50)) || (graveGuardKills >= 10))
				{
					st.set("grave_guard_kills", 0);
					addSpawn(GRAVE_KEYMASTER, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0);
				}
			}
			return;
		}
		
		if (npcId == GRAVE_KEYMASTER)
		{
			final Player clanLeader = st.getPlayer();
			if ((clanLeader != null) && clanLeader.isOnline() && (cond == 10) && !hasQuestItems(clanLeader, SCEPTER_OF_JUDGMENT) && (getQuestItemsCount(clanLeader, IMPERIAL_KEY) < 6) && (clanLeader.calculateDistance3D(npc) <= 1500))
			{
				giveItems(clanLeader, IMPERIAL_KEY, 1);
				if (getQuestItemsCount(clanLeader, IMPERIAL_KEY) >= 6)
				{
					playSound(clanLeader, QuestSound.ITEMSOUND_QUEST_MIDDLE);
				}
				else
				{
					playSound(clanLeader, QuestSound.ITEMSOUND_QUEST_ITEMGET);
				}
			}
			return;
		}
		
		if (((npcId == DRAKE) || (npcId == DRAKE_HOLD)) && (cond == 2))
		{
			final Player clanLeader = st.getPlayer();
			giveItemRandomly(clanLeader, npc, MIST_DRAKE_EGG, 1, 10, 0.10, true);
			giveItemRandomly(clanLeader, npc, DRAKE_EGG, 1, 10, 0.50, true);
			return;
		}
		
		// Handling for LESSER_GIANT_SOLDIER and LESSER_GIANT_SCOUT for retail chances
		if (((npcId == LESSER_GIANT_SOLDIER) || (npcId == LESSER_GIANT_SCOUT)) && (cond == 5))
		{
			final Player clanLeader = st.getPlayer();
			final int roll = getRandom(100);
			
			if ((roll < 10) && (getQuestItemsCount(clanLeader, TITANS_POWERSTONE) < 10))
			{
				giveItems(clanLeader, TITANS_POWERSTONE, 1);
			}
			else if ((roll < 30) && (getQuestItemsCount(clanLeader, NEBULITE_CRYSTALS) < 10))
			{
				giveItems(clanLeader, NEBULITE_CRYSTALS, 1);
			}
			else if (roll < 80)
			{
				giveItems(clanLeader, BROKEN_TITANS_POWERSTONE, 1);
			}
			
			return; // Skip rest of the drop logic for these NPCs
		}
		
		for (int[] element : DROPLIST)
		{
			if (element[0] == npcId)
			{
				if (cond == element[1])
				{
					final int maxCount = element[2];
					final int chance = element[3];
					final int item1 = element[4];
					
					if (item1 != 0)
					{
						giveItemRandomly(st.getPlayer(), item1, 1, maxCount, chance / 1000000d, true);
					}
					else if ((element[0] == GRAVE_GUARD) && (getQuestItemsCount(st.getPlayer(), IMPERIAL_KEY) < 6) && (getRandom(1000000) < chance))
					{
						addSpawn(GRAVE_KEYMASTER, player.getX(), player.getY(), player.getZ(), player.getHeading(), true, 0);
					}
				}
			}
		}
	}
	
	private void spawnImperialSlaves(Npc npc)
	{
		for (int i = 0; i < 4; i++)
		{
			addSpawn(IMPERIAL_SLAVE, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0);
		}
	}
	
	private void teleportImperialGravekeeper(Npc npc, int x, int y, int z)
	{
		npc.broadcastPacket(new TeleportToLocation(npc, x, y, z, npc.getHeading()));
		npc.setXYZ(x, y, z);
		npc.revalidateZone(true);
	}
}

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
package org.l2jmobius.gameserver.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.l2jmobius.commons.config.InterfaceConfig;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.CreatureAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.FactionSystemConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.instance.Pet;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.network.Disconnection;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.DeleteObject;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.LeaveWorld;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;

public class World
{
	private static final Logger LOGGER = Logger.getLogger(World.class.getName());
	
	public static volatile int MAX_CONNECTED_COUNT = 0;
	public static volatile int OFFLINE_TRADE_COUNT = 0;
	
	/** Bit shift, defines number of regions note, shifting by 15 will result in regions corresponding to map tiles shifting by 11 divides one tile to 16x16 regions. */
	public static final int SHIFT_BY = 11;
	
	public static final int TILE_SIZE = 32768;
	
	/** Map dimensions. */
	public static final int TILE_X_MIN = 16;
	public static final int TILE_Y_MIN = 10;
	public static final int TILE_X_MAX = 26;
	public static final int TILE_Y_MAX = 25;
	public static final int TILE_ZERO_COORD_X = 20;
	public static final int TILE_ZERO_COORD_Y = 18;
	public static final int WORLD_X_MIN = (TILE_X_MIN - TILE_ZERO_COORD_X) * TILE_SIZE;
	public static final int WORLD_Y_MIN = (TILE_Y_MIN - TILE_ZERO_COORD_Y) * TILE_SIZE;
	
	public static final int WORLD_X_MAX = ((TILE_X_MAX - TILE_ZERO_COORD_X) + 1) * TILE_SIZE;
	public static final int WORLD_Y_MAX = ((TILE_Y_MAX - TILE_ZERO_COORD_Y) + 1) * TILE_SIZE;
	
	/** Z coordinate limits */
	public static final int WORLD_Z_MIN = -16000;
	public static final int WORLD_Z_MAX = 16000;
	public static final int Z_REGION_SIZE = 2000;
	
	/** Calculated offset used so top left region is 0,0 */
	public static final int OFFSET_X = Math.abs(WORLD_X_MIN >> SHIFT_BY);
	public static final int OFFSET_Y = Math.abs(WORLD_Y_MIN >> SHIFT_BY);
	
	/** Number of regions. */
	private static final int REGIONS_X = (WORLD_X_MAX >> SHIFT_BY) + OFFSET_X;
	private static final int REGIONS_Y = (WORLD_Y_MAX >> SHIFT_BY) + OFFSET_Y;
	private static final int REGIONS_Z = (Math.abs(WORLD_Z_MIN) + Math.abs(WORLD_Z_MAX)) / Z_REGION_SIZE;
	
	/** Map containing all the players in game. */
	private static final Map<Integer, Player> _allPlayers = new ConcurrentHashMap<>();
	/** Map containing all the Good players in game. */
	private static final Map<Integer, Player> _allGoodPlayers = new ConcurrentHashMap<>();
	/** Map containing all the Evil players in game. */
	private static final Map<Integer, Player> _allEvilPlayers = new ConcurrentHashMap<>();
	/** Map containing all visible objects. */
	private static final Map<Integer, WorldObject> _allObjects = new ConcurrentHashMap<>();
	/** Map with the pets instances and their owner ID. */
	private static final Map<Integer, Pet> _petsInstance = new ConcurrentHashMap<>();
	
	private static final WorldRegion[][][] _worldRegions = new WorldRegion[REGIONS_X + 1][REGIONS_Y + 1][REGIONS_Z];
	
	private World()
	{
	}
	
	public static void init()
	{
		// Initialize regions.
		for (int x = 0; x <= REGIONS_X; x++)
		{
			for (int y = 0; y <= REGIONS_Y; y++)
			{
				for (int z = 0; z < REGIONS_Z; z++)
				{
					_worldRegions[x][y][z] = new WorldRegion(x, y, z);
				}
			}
		}
		
		// Set surrounding regions.
		for (int rx = 0; rx <= REGIONS_X; rx++)
		{
			for (int ry = 0; ry <= REGIONS_Y; ry++)
			{
				for (int rz = 0; rz < REGIONS_Z; rz++)
				{
					final List<WorldRegion> surroundingRegions = new ArrayList<>();
					for (int sx = rx - 1; sx <= (rx + 1); sx++)
					{
						for (int sy = ry - 1; sy <= (ry + 1); sy++)
						{
							for (int sz = rz - 1; sz <= (rz + 1); sz++)
							{
								if (((sx >= 0) && (sx < REGIONS_X) && (sy >= 0) && (sy < REGIONS_Y) && (sz >= 0) && (sz < REGIONS_Z)))
								{
									surroundingRegions.add(_worldRegions[sx][sy][sz]);
								}
							}
						}
					}
					
					WorldRegion[] regionArray = new WorldRegion[surroundingRegions.size()];
					regionArray = surroundingRegions.toArray(regionArray);
					_worldRegions[rx][ry][rz].setSurroundingRegions(regionArray);
				}
			}
		}
		
		// When GUI is enabled World is called early. So we need to skip this log.
		if (!InterfaceConfig.ENABLE_GUI)
		{
			LOGGER.info(World.class.getSimpleName() + ": (" + REGIONS_X + " by " + REGIONS_Y + ") World Region Grid set up.");
		}
	}
	
	/**
	 * Adds an object to the world.<br>
	 * <br>
	 * <b><u>Example of use</u>:</b>
	 * <ul>
	 * <li>Withdraw an item from the warehouse, create an item</li>
	 * <li>Spawn a Creature (PC, NPC, Pet)</li>
	 * </ul>
	 * @param object
	 */
	public static void addObject(WorldObject object)
	{
		_allObjects.putIfAbsent(object.getObjectId(), object);
		
		// if (_allObjects.putIfAbsent(object.getObjectId(), object) != null)
		// {
		// LOGGER.warning(World.class.getSimpleName() + ": Object " + object + " already exists in the world. Stack Trace: " + CommonUtil.getTraceString(Thread.currentThread().getStackTrace()));
		// }
		
		if (object.isPlayer())
		{
			final Player newPlayer = object.asPlayer();
			if (newPlayer.isTeleporting()) // TODO: Drop when we stop removing player from the world while teleporting.
			{
				return;
			}
			
			final Player existingPlayer = _allPlayers.putIfAbsent(object.getObjectId(), newPlayer);
			if (existingPlayer != null)
			{
				Disconnection.of(existingPlayer).storeAndDeleteWith(LeaveWorld.STATIC_PACKET);
				Disconnection.of(newPlayer).storeAndDeleteWith(LeaveWorld.STATIC_PACKET);
				LOGGER.warning(World.class.getSimpleName() + ": Duplicate character!? Disconnected both characters (" + newPlayer.getName() + ")");
			}
			else if (FactionSystemConfig.FACTION_SYSTEM_ENABLED)
			{
				addFactionPlayerToWorld(newPlayer);
			}
		}
	}
	
	/**
	 * Removes an object from the world.<br>
	 * <br>
	 * <b><u>Example of use</u>:</b>
	 * <ul>
	 * <li>Delete item from inventory, transfer Item from inventory to warehouse</li>
	 * <li>Crystallize item</li>
	 * <li>Remove NPC/PC/Pet from the world</li>
	 * </ul>
	 * @param object the object to remove
	 */
	public static void removeObject(WorldObject object)
	{
		_allObjects.remove(object.getObjectId());
		if (object.isPlayer())
		{
			final Player player = object.asPlayer();
			if (player.isTeleporting()) // TODO: Drop when we stop removing player from the world while teleporting.
			{
				return;
			}
			
			_allPlayers.remove(object.getObjectId());
			
			if (FactionSystemConfig.FACTION_SYSTEM_ENABLED)
			{
				if (player.isGood())
				{
					_allGoodPlayers.remove(player.getObjectId());
				}
				else if (player.isEvil())
				{
					_allEvilPlayers.remove(player.getObjectId());
				}
			}
		}
	}
	
	/**
	 * <b><u>Example of use</u>:</b>
	 * <ul>
	 * <li>Client packets : Action, AttackRequest, RequestJoinParty, RequestJoinPledge...</li>
	 * </ul>
	 * @param objectId Identifier of the WorldObject
	 * @return the WorldObject object that belongs to an ID or null if no object found.
	 */
	public static WorldObject findObject(int objectId)
	{
		return _allObjects.get(objectId);
	}
	
	public static Collection<WorldObject> getVisibleObjects()
	{
		return _allObjects.values();
	}
	
	/**
	 * Get the count of all visible objects in world.
	 * @return count off all World objects
	 */
	public static int getVisibleObjectsCount()
	{
		return _allObjects.size();
	}
	
	public static Collection<Player> getPlayers()
	{
		return _allPlayers.values();
	}
	
	public static Collection<Player> getAllGoodPlayers()
	{
		return _allGoodPlayers.values();
	}
	
	public static Collection<Player> getAllEvilPlayers()
	{
		return _allEvilPlayers.values();
	}
	
	/**
	 * <b>If you have access to player objectId use {@link #getPlayer(int playerObjId)}</b>
	 * @param name Name of the player to get Instance
	 * @return the player instance corresponding to the given name.
	 */
	public static Player getPlayer(String name)
	{
		return getPlayer(CharInfoTable.getInstance().getIdByName(name));
	}
	
	/**
	 * @param objectId of the player to get Instance
	 * @return the player instance corresponding to the given object ID.
	 */
	public static Player getPlayer(int objectId)
	{
		return _allPlayers.get(objectId);
	}
	
	/**
	 * @param ownerId ID of the owner
	 * @return the pet instance from the given ownerId.
	 */
	public static Pet getPet(int ownerId)
	{
		return _petsInstance.get(ownerId);
	}
	
	/**
	 * Add the given pet instance from the given ownerId.
	 * @param ownerId ID of the owner
	 * @param pet Pet of the pet
	 * @return
	 */
	public static Pet addPet(int ownerId, Pet pet)
	{
		return _petsInstance.put(ownerId, pet);
	}
	
	/**
	 * Remove the given pet instance.
	 * @param ownerId ID of the owner
	 */
	public static void removePet(int ownerId)
	{
		_petsInstance.remove(ownerId);
	}
	
	/**
	 * This operation is quite heave as it iterates all world visible objects.
	 * @param npcId the id of the NPC to find.
	 * @return the first NPC found corresponding to the given id.
	 */
	public static Npc getNpc(int npcId)
	{
		for (WorldObject wo : getVisibleObjects())
		{
			if (wo.isNpc() && (wo.getId() == npcId))
			{
				return wo.asNpc();
			}
		}
		
		return null;
	}
	
	/**
	 * Add a WorldObject in the world. <b><u>Concept</u>:</b> WorldObject (including Player) are identified in <b>_visibleObjects</b> of his current WorldRegion and in <b>_knownObjects</b> of other surrounding Creatures<br>
	 * Player are identified in <b>_allPlayers</b> of World, in <b>_allPlayers</b> of his current WorldRegion and in <b>_knownPlayer</b> of other surrounding Creatures <b><u> Actions</u>:</b>
	 * <li>Add the WorldObject object in _allPlayers* of World</li>
	 * <li>Add the WorldObject object in _gmList** of GmListTable</li>
	 * <li>Add object in _knownObjects and _knownPlayer* of all surrounding WorldRegion Creatures</li>
	 * <li>If object is a Creature, add all surrounding WorldObject in its _knownObjects and all surrounding Player in its _knownPlayer</li><br>
	 * <i>* only if object is a Player</i><br>
	 * <i>** only if object is a GM Player</i> <font color=#FF0000><b><u>Caution</u>: This method DOESN'T ADD the object in _visibleObjects and _allPlayers* of WorldRegion (need synchronisation)</b></font><br>
	 * <font color=#FF0000><b><u>Caution</u>: This method DOESN'T ADD the object to _allObjects and _allPlayers* of World (need synchronisation)</b></font> <b><u> Example of use</u>:</b>
	 * <li>Drop an Item</li>
	 * <li>Spawn a Creature</li>
	 * <li>Apply Death Penalty of a Player</li><br>
	 * @param object L2object to add in the world
	 * @param newRegion WorldRegion in wich the object will be add (not used)
	 */
	public static void addVisibleObject(WorldObject object, WorldRegion newRegion)
	{
		if (!newRegion.isActive())
		{
			return;
		}
		
		forEachVisibleObject(object, WorldObject.class, wo ->
		{
			if (object.isPlayer() && wo.isVisibleFor(object.asPlayer()))
			{
				wo.sendInfo(object.asPlayer());
				if (wo.isCreature())
				{
					final CreatureAI ai = wo.asCreature().getAI();
					if (ai != null)
					{
						ai.describeStateToPlayer(object.asPlayer());
						if (wo.isMonster() && (ai.getIntention() == Intention.IDLE))
						{
							ai.setIntentionActive();
						}
					}
				}
			}
			
			if (wo.isPlayer() && object.isVisibleFor(wo.asPlayer()))
			{
				object.sendInfo(wo.asPlayer());
				if (object.isCreature())
				{
					final CreatureAI ai = object.asCreature().getAI();
					if (ai != null)
					{
						ai.describeStateToPlayer(wo.asPlayer());
						if (object.isMonster() && (ai.getIntention() == Intention.IDLE))
						{
							ai.setIntentionActive();
						}
					}
				}
			}
		});
	}
	
	public static void addFactionPlayerToWorld(Player player)
	{
		if (player.isGood())
		{
			_allGoodPlayers.put(player.getObjectId(), player);
		}
		else if (player.isEvil())
		{
			_allEvilPlayers.put(player.getObjectId(), player);
		}
	}
	
	/**
	 * Remove a WorldObject from the world. <b><u>Concept</u>:</b> WorldObject (including Player) are identified in <b>_visibleObjects</b> of his current WorldRegion and in <b>_knownObjects</b> of other surrounding Creatures<br>
	 * Player are identified in <b>_allPlayers</b> of World, in <b>_allPlayers</b> of his current WorldRegion and in <b>_knownPlayer</b> of other surrounding Creatures <b><u> Actions</u>:</b>
	 * <li>Remove the WorldObject object from _allPlayers* of World</li>
	 * <li>Remove the WorldObject object from _visibleObjects and _allPlayers* of WorldRegion</li>
	 * <li>Remove the WorldObject object from _gmList** of GmListTable</li>
	 * <li>Remove object from _knownObjects and _knownPlayer* of all surrounding WorldRegion Creatures</li>
	 * <li>If object is a Creature, remove all WorldObject from its _knownObjects and all Player from its _knownPlayer</li> <font color=#FF0000><b><u>Caution</u>: This method DOESN'T REMOVE the object from _allObjects of World</b></font> <i>* only if object is a Player</i><br>
	 * <i>** only if object is a GM Player</i> <b><u> Example of use</u>:</b>
	 * <li>Pickup an Item</li>
	 * <li>Decay a Creature</li><br>
	 * @param object L2object to remove from the world
	 * @param oldRegion WorldRegion in which the object was before removing
	 */
	public static void removeVisibleObject(WorldObject object, WorldRegion oldRegion)
	{
		if ((object == null) || (oldRegion == null))
		{
			return;
		}
		
		oldRegion.removeVisibleObject(object);
		
		// Go through all surrounding WorldRegion Creatures.
		final WorldRegion[] surroundingRegions = oldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if (wo == object)
				{
					continue;
				}
				
				if (object.isCreature())
				{
					final Creature objectCreature = object.asCreature();
					final CreatureAI ai = objectCreature.getAI();
					if (ai != null)
					{
						ai.notifyActionForgetObject(wo);
					}
					
					if (objectCreature.getTarget() == wo)
					{
						objectCreature.setTarget(null);
					}
					
					if (object.isPlayer())
					{
						object.sendPacket(new DeleteObject(wo));
					}
				}
				
				if (wo.isCreature())
				{
					final Creature woCreature = wo.asCreature();
					final CreatureAI ai = woCreature.getAI();
					if (ai != null)
					{
						ai.notifyActionForgetObject(object);
					}
					
					if (woCreature.getTarget() == object)
					{
						woCreature.setTarget(null);
					}
					
					if (wo.isPlayer())
					{
						wo.sendPacket(new DeleteObject(object));
					}
				}
			}
		}
	}
	
	public static void switchRegion(WorldObject object, WorldRegion newRegion)
	{
		final WorldRegion oldRegion = object.getWorldRegion();
		if ((oldRegion == null) || (oldRegion == newRegion))
		{
			return;
		}
		
		final WorldRegion[] oldSurroundingRegions = oldRegion.getSurroundingRegions();
		for (int i = 0; i < oldSurroundingRegions.length; i++)
		{
			final WorldRegion worldRegion = oldSurroundingRegions[i];
			if (newRegion.isSurroundingRegion(worldRegion))
			{
				continue;
			}
			
			final Collection<WorldObject> visibleObjects = worldRegion.getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if (wo == object)
				{
					continue;
				}
				
				if (object.isCreature())
				{
					final Creature objectCreature = object.asCreature();
					final CreatureAI ai = objectCreature.getAI();
					if (ai != null)
					{
						ai.notifyActionForgetObject(wo);
					}
					
					if (objectCreature.getTarget() == wo)
					{
						objectCreature.setTarget(null);
					}
					
					if (object.isPlayer())
					{
						object.sendPacket(new DeleteObject(wo));
					}
				}
				
				if (wo.isCreature())
				{
					final Creature woCreature = wo.asCreature();
					final CreatureAI ai = woCreature.getAI();
					if (ai != null)
					{
						ai.notifyActionForgetObject(object);
					}
					
					if (woCreature.getTarget() == object)
					{
						woCreature.setTarget(null);
					}
					
					if (wo.isPlayer())
					{
						wo.sendPacket(new DeleteObject(object));
					}
				}
			}
		}
		
		final WorldRegion[] newSurroundingRegions = newRegion.getSurroundingRegions();
		for (int i = 0; i < newSurroundingRegions.length; i++)
		{
			final WorldRegion worldRegion = newSurroundingRegions[i];
			if (oldRegion.isSurroundingRegion(worldRegion))
			{
				continue;
			}
			
			final Collection<WorldObject> visibleObjects = worldRegion.getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				if (object.isPlayer() && wo.isVisibleFor(object.asPlayer()))
				{
					wo.sendInfo(object.asPlayer());
					if (wo.isCreature())
					{
						final CreatureAI ai = wo.asCreature().getAI();
						if (ai != null)
						{
							ai.describeStateToPlayer(object.asPlayer());
							if (wo.isMonster() && (ai.getIntention() == Intention.IDLE))
							{
								ai.setIntentionActive();
							}
						}
					}
				}
				
				if (wo.isPlayer() && object.isVisibleFor(wo.asPlayer()))
				{
					object.sendInfo(wo.asPlayer());
					if (object.isCreature())
					{
						final CreatureAI ai = object.asCreature().getAI();
						if (ai != null)
						{
							ai.describeStateToPlayer(wo.asPlayer());
							if (object.isMonster() && (ai.getIntention() == Intention.IDLE))
							{
								ai.setIntentionActive();
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Collects every visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that share its instance world. The reference object itself is excluded.
	 * <p>
	 * If the caller does not need a materialized {@link List}, prefer {@link #forEachVisibleObject(WorldObject, Class, Consumer)} to avoid {@link ArrayList} allocation, resizes and population overhead, or {@link #getFirstVisibleObject(WorldObject, Class)} /
	 * {@link #forFirstVisibleObject(WorldObject, Class, Consumer)} when a single match is enough.
	 * @param <T> the {@link WorldObject} subtype to collect
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @return a freshly allocated {@link List} of matches; empty when {@code object} is {@code null} or has no region
	 */
	public static <T extends WorldObject> List<T> getVisibleObjects(WorldObject object, Class<T> clazz)
	{
		final List<T> result = new ArrayList<>();
		if (object == null)
		{
			return result;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return result;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				result.add(clazz.cast(wo));
			}
		}
		
		return result;
	}
	
	/**
	 * Collects every visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that share its instance world and lie within {@code range} 3D-distance from it. The reference object itself is excluded.
	 * <p>
	 * If the caller does not need a materialized {@link List}, prefer {@link #forEachVisibleObjectInRange(WorldObject, Class, int, Consumer)} to avoid {@link ArrayList} allocation, resizes and population overhead, or {@link #getFirstVisibleObjectInRange(WorldObject, Class, int)} /
	 * {@link #forFirstVisibleObjectInRange(WorldObject, Class, int, Consumer)} when a single match is enough.
	 * @param <T> the {@link WorldObject} subtype to collect
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @return a freshly allocated {@link List} of matches; empty when {@code object} is {@code null} or has no region
	 */
	public static <T extends WorldObject> List<T> getVisibleObjectsInRange(WorldObject object, Class<T> clazz, int range)
	{
		final List<T> result = new ArrayList<>();
		if (object == null)
		{
			return result;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return result;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				result.add(clazz.cast(wo));
			}
		}
		
		return result;
	}
	
	/**
	 * Collects every visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that share its instance world and satisfy {@code predicate}. The reference object itself is excluded.
	 * <p>
	 * If the caller does not need a materialized {@link List}, prefer {@link #forEachVisibleObject(WorldObject, Class, Consumer)} (testing the predicate inline) to avoid {@link ArrayList} allocation, resizes and population overhead, or {@link #getFirstVisibleObject(WorldObject, Class, Predicate)} /
	 * {@link #forFirstVisibleObject(WorldObject, Class, Predicate, Consumer)} when a single match is enough.
	 * @param <T> the {@link WorldObject} subtype to collect
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param predicate additional filter applied to each candidate already matching {@code clazz}
	 * @return a freshly allocated {@link List} of matches; empty when {@code object} is {@code null} or has no region
	 */
	public static <T extends WorldObject> List<T> getVisibleObjects(WorldObject object, Class<T> clazz, Predicate<T> predicate)
	{
		final List<T> result = new ArrayList<>();
		if (object == null)
		{
			return result;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return result;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					result.add(cast);
				}
			}
		}
		
		return result;
	}
	
	/**
	 * Collects every visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that share its instance world, lie within {@code range} 3D-distance from it and satisfy {@code predicate}. The reference object itself is excluded.
	 * <p>
	 * If the caller does not need a materialized {@link List}, prefer {@link #forEachVisibleObjectInRange(WorldObject, Class, int, Consumer)} (testing the predicate inline) to avoid {@link ArrayList} allocation, resizes and population overhead, or
	 * {@link #getFirstVisibleObjectInRange(WorldObject, Class, int, Predicate)} / {@link #forFirstVisibleObjectInRange(WorldObject, Class, int, Predicate, Consumer)} when a single match is enough.
	 * @param <T> the {@link WorldObject} subtype to collect
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param predicate additional filter applied to each candidate already matching {@code clazz} and within range
	 * @return a freshly allocated {@link List} of matches; empty when {@code object} is {@code null} or has no region
	 */
	public static <T extends WorldObject> List<T> getVisibleObjectsInRange(WorldObject object, Class<T> clazz, int range, Predicate<T> predicate)
	{
		final List<T> result = new ArrayList<>();
		if (object == null)
		{
			return result;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return result;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					result.add(cast);
				}
			}
		}
		
		return result;
	}
	
	/**
	 * Applies {@code c} to every visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that share its instance world. The reference object itself is skipped. Returns silently when {@code object} is {@code null} or has no region.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param c action invoked for each matching object
	 */
	public static <T extends WorldObject> void forEachVisibleObject(WorldObject object, Class<T> clazz, Consumer<T> c)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				c.accept(clazz.cast(wo));
			}
		}
	}
	
	/**
	 * Applies {@code c} to every visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that share its instance world and lie within {@code range} 3D-distance from it. The reference object itself is skipped. Returns silently when {@code object} is {@code null}
	 * or has no region.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param c action invoked for each matching object
	 */
	public static <T extends WorldObject> void forEachVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Consumer<T> c)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				c.accept(clazz.cast(wo));
			}
		}
	}
	
	/**
	 * Returns the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world, or {@code null} if none is found (also when {@code object} is {@code null} or has no region). The reference object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @return the first matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getFirstVisibleObject(WorldObject object, Class<T> clazz)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				return clazz.cast(wo);
			}
		}
		
		return null;
	}
	
	/**
	 * Returns the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world and lies within {@code range} 3D-distance from it, or {@code null} if none is found (also when {@code object} is {@code null} or has no
	 * region). The reference object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match within range.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @return the first matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getFirstVisibleObjectInRange(WorldObject object, Class<T> clazz, int range)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				return clazz.cast(wo);
			}
		}
		
		return null;
	}
	
	/**
	 * Returns the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world and satisfies {@code predicate}, or {@code null} if none is found (also when {@code object} is {@code null} or has no region). The reference
	 * object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param predicate additional filter applied to each candidate already matching {@code clazz}
	 * @return the first matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getFirstVisibleObject(WorldObject object, Class<T> clazz, Predicate<T> predicate)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					return cast;
				}
			}
		}
		
		return null;
	}
	
	/**
	 * Returns the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world, lies within {@code range} 3D-distance from it and satisfies {@code predicate}, or {@code null} if none is found (also when {@code object}
	 * is {@code null} or has no region). The reference object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match within range.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param predicate additional filter applied to each candidate already matching {@code clazz} and within range
	 * @return the first matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getFirstVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Predicate<T> predicate)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					return cast;
				}
			}
		}
		
		return null;
	}
	
	/**
	 * Invokes {@code action} on the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world, then returns. Does nothing if no match is found, or if {@code object} is {@code null} or has no region. The reference
	 * object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match. Prefer this over {@link #getFirstVisibleObject(WorldObject, Class)} when the hit can be acted on inline without a {@code null} check.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param action action invoked at most once, on the first matching object
	 */
	public static <T extends WorldObject> void forFirstVisibleObject(WorldObject object, Class<T> clazz, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				action.accept(clazz.cast(wo));
				return;
			}
		}
	}
	
	/**
	 * Invokes {@code action} on the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world and lies within {@code range} 3D-distance from it, then returns. Does nothing if no match is found, or if {@code object}
	 * is {@code null} or has no region. The reference object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match within range. Prefer this over {@link #getFirstVisibleObjectInRange(WorldObject, Class, int)} when the hit can be acted on inline without a {@code null} check.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param action action invoked at most once, on the first matching object
	 */
	public static <T extends WorldObject> void forFirstVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				action.accept(clazz.cast(wo));
				return;
			}
		}
	}
	
	/**
	 * Invokes {@code action} on the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world and satisfies {@code predicate}, then returns. Does nothing if no match is found, or if {@code object} is {@code null} or
	 * has no region. The reference object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match. Prefer this over {@link #getFirstVisibleObject(WorldObject, Class, Predicate)} when the hit can be acted on inline without a {@code null} check.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param predicate additional filter applied to each candidate already matching {@code clazz}
	 * @param action action invoked at most once, on the first matching object
	 */
	public static <T extends WorldObject> void forFirstVisibleObject(WorldObject object, Class<T> clazz, Predicate<T> predicate, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					action.accept(cast);
					return;
				}
			}
		}
	}
	
	/**
	 * Invokes {@code action} on the first visible {@link WorldObject} of type {@code T} encountered in the reference object's surrounding regions that shares its instance world, lies within {@code range} 3D-distance from it and satisfies {@code predicate}, then returns. Does nothing if no match is
	 * found, or if {@code object} is {@code null} or has no region. The reference object itself is skipped.
	 * <p>
	 * "First" is the first hit in region iteration order, not the closest match within range. Prefer this over {@link #getFirstVisibleObjectInRange(WorldObject, Class, int, Predicate)} when the hit can be acted on inline without a {@code null} check.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param predicate additional filter applied to each candidate already matching {@code clazz} and within range
	 * @param action action invoked at most once, on the first matching object
	 */
	public static <T extends WorldObject> void forFirstVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Predicate<T> predicate, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					action.accept(cast);
					return;
				}
			}
		}
	}
	
	/**
	 * Returns the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world, or {@code null} if none is found (also when {@code object} is {@code null} or has no region). The reference object itself is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #getFirstVisibleObject(WorldObject, Class)} unless the actual closest match is required - the simple {@code getFirst*} variant can
	 * short-circuit on the first hit while this method must inspect every candidate.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @return the nearest matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getNearestVisibleObject(WorldObject object, Class<T> clazz)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final double distance = wo.calculateDistance3D(object);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					nearest = clazz.cast(wo);
				}
			}
		}
		
		return nearest;
	}
	
	/**
	 * Returns the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and lies within {@code range} 3D-distance from it, or {@code null} if none is found (also when {@code object} is {@code null} or has no region). The
	 * reference object itself is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #getFirstVisibleObjectInRange(WorldObject, Class, int)} unless the actual closest match is required - the simple {@code getFirst*}
	 * variant can short-circuit on the first hit while this method must inspect every candidate within range.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @return the nearest matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getNearestVisibleObjectInRange(WorldObject object, Class<T> clazz, int range)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final double distance = wo.calculateDistance3D(object);
				if ((distance <= range) && (distance < bestDistance))
				{
					bestDistance = distance;
					nearest = clazz.cast(wo);
				}
			}
		}
		
		return nearest;
	}
	
	/**
	 * Returns the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and satisfies {@code predicate}, or {@code null} if none is found (also when {@code object} is {@code null} or has no region). The reference object
	 * itself is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #getFirstVisibleObject(WorldObject, Class, Predicate)} unless the actual closest match is required - the simple {@code getFirst*}
	 * variant can short-circuit on the first hit while this method must inspect every candidate.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param predicate additional filter applied to each candidate already matching {@code clazz}
	 * @return the nearest matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getNearestVisibleObject(WorldObject object, Class<T> clazz, Predicate<T> predicate)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					final double distance = wo.calculateDistance3D(object);
					if (distance < bestDistance)
					{
						bestDistance = distance;
						nearest = cast;
					}
				}
			}
		}
		
		return nearest;
	}
	
	/**
	 * Returns the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world, lies within {@code range} 3D-distance from it and satisfies {@code predicate}, or {@code null} if none is found (also when {@code object} is {@code
	 * null} or has no region). The reference object itself is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #getFirstVisibleObjectInRange(WorldObject, Class, int, Predicate)} unless the actual closest match is required - the simple {@code
	 * getFirst*} variant can short-circuit on the first hit while this method must inspect every candidate within range.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param predicate additional filter applied to each candidate already matching {@code clazz} and within range
	 * @return the nearest matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getNearestVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Predicate<T> predicate)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final double distance = wo.calculateDistance3D(object);
				if (distance <= range)
				{
					final T cast = clazz.cast(wo);
					if (predicate.test(cast) && (distance < bestDistance))
					{
						bestDistance = distance;
						nearest = cast;
					}
				}
			}
		}
		
		return nearest;
	}
	
	/**
	 * Invokes {@code action} on the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world, then returns. Does nothing if no match is found, or if {@code object} is {@code null} or has no region. The reference object itself
	 * is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #forFirstVisibleObject(WorldObject, Class, Consumer)} unless the actual closest match is required - the simple {@code forFirst*}
	 * variant can short-circuit on the first hit while this method must inspect every candidate.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param action action invoked at most once, on the nearest matching object
	 */
	public static <T extends WorldObject> void forNearestVisibleObject(WorldObject object, Class<T> clazz, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final double distance = wo.calculateDistance3D(object);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					nearest = clazz.cast(wo);
				}
			}
		}
		
		if (nearest != null)
		{
			action.accept(nearest);
		}
	}
	
	/**
	 * Invokes {@code action} on the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and lies within {@code range} 3D-distance from it, then returns. Does nothing if no match is found, or if {@code object} is
	 * {@code null} or has no region. The reference object itself is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #forFirstVisibleObjectInRange(WorldObject, Class, int, Consumer)} unless the actual closest match is required - the simple {@code
	 * forFirst*} variant can short-circuit on the first hit while this method must inspect every candidate within range.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param action action invoked at most once, on the nearest matching object
	 */
	public static <T extends WorldObject> void forNearestVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final double distance = wo.calculateDistance3D(object);
				if ((distance <= range) && (distance < bestDistance))
				{
					bestDistance = distance;
					nearest = clazz.cast(wo);
				}
			}
		}
		
		if (nearest != null)
		{
			action.accept(nearest);
		}
	}
	
	/**
	 * Invokes {@code action} on the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and satisfies {@code predicate}, then returns. Does nothing if no match is found, or if {@code object} is {@code null} or has no
	 * region. The reference object itself is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #forFirstVisibleObject(WorldObject, Class, Predicate, Consumer)} unless the actual closest match is required - the simple {@code
	 * forFirst*} variant can short-circuit on the first hit while this method must inspect every candidate.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param predicate additional filter applied to each candidate already matching {@code clazz}
	 * @param action action invoked at most once, on the nearest matching object
	 */
	public static <T extends WorldObject> void forNearestVisibleObject(WorldObject object, Class<T> clazz, Predicate<T> predicate, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					final double distance = wo.calculateDistance3D(object);
					if (distance < bestDistance)
					{
						bestDistance = distance;
						nearest = cast;
					}
				}
			}
		}
		
		if (nearest != null)
		{
			action.accept(nearest);
		}
	}
	
	/**
	 * Invokes {@code action} on the nearest visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world, lies within {@code range} 3D-distance from it and satisfies {@code predicate}, then returns. Does nothing if no match is found, or
	 * if {@code object} is {@code null} or has no region. The reference object itself is skipped.
	 * <p>
	 * "Nearest" is computed by 3D distance to {@code object} via a single-pass scan that tracks the best candidate, with no {@link ArrayList} allocation. Prefer {@link #forFirstVisibleObjectInRange(WorldObject, Class, int, Predicate, Consumer)} unless the actual closest match is required - the
	 * simple {@code forFirst*} variant can short-circuit on the first hit while this method must inspect every candidate within range.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param predicate additional filter applied to each candidate already matching {@code clazz} and within range
	 * @param action action invoked at most once, on the nearest matching object
	 */
	public static <T extends WorldObject> void forNearestVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Predicate<T> predicate, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T nearest = null;
		double bestDistance = Double.MAX_VALUE;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final double distance = wo.calculateDistance3D(object);
				if (distance <= range)
				{
					final T cast = clazz.cast(wo);
					if (predicate.test(cast) && (distance < bestDistance))
					{
						bestDistance = distance;
						nearest = cast;
					}
				}
			}
		}
		
		if (nearest != null)
		{
			action.accept(nearest);
		}
	}
	
	/**
	 * Returns a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world, or {@code null} if none is found (also when {@code object} is {@code null} or has no region). The reference object itself is skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #getFirstVisibleObject(WorldObject, Class)} when first-in-iteration-order is acceptable; this method's full scan is only needed when uniform randomness over all matching
	 * candidates is required.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @return a uniformly-random matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getRandomVisibleObject(WorldObject object, Class<T> clazz)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T selected = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				count++;
				if (Rnd.get(count) == 0)
				{
					selected = clazz.cast(wo);
				}
			}
		}
		
		return (selected != null) ? selected : getFirstVisibleObject(object, clazz);
	}
	
	/**
	 * Returns a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and lies within {@code range} 3D-distance from it, or {@code null} if none is found (also when {@code object} is {@code null} or has no
	 * region). The reference object itself is skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #getFirstVisibleObjectInRange(WorldObject, Class, int)} when first-in-iteration-order is acceptable; this method's full scan is only needed when uniform randomness over all
	 * matching candidates within range is required.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @return a uniformly-random matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getRandomVisibleObjectInRange(WorldObject object, Class<T> clazz, int range)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T selected = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				count++;
				if (Rnd.get(count) == 0)
				{
					selected = clazz.cast(wo);
				}
			}
		}
		
		return (selected != null) ? selected : getFirstVisibleObjectInRange(object, clazz, range);
	}
	
	/**
	 * Returns a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and satisfies {@code predicate}, or {@code null} if none is found (also when {@code object} is {@code null} or has no region). The reference
	 * object itself is skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #getFirstVisibleObject(WorldObject, Class, Predicate)} when first-in-iteration-order is acceptable; this method's full scan is only needed when uniform randomness over all
	 * matching candidates is required.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param predicate additional filter applied to each candidate already matching {@code clazz}
	 * @return a uniformly-random matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getRandomVisibleObject(WorldObject object, Class<T> clazz, Predicate<T> predicate)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T selected = null;
		T firstValid = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					if (firstValid == null)
					{
						firstValid = cast;
					}
					
					count++;
					if (Rnd.get(count) == 0)
					{
						selected = cast;
					}
				}
			}
		}
		
		return (selected != null) ? selected : firstValid;
	}
	
	/**
	 * Returns a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world, lies within {@code range} 3D-distance from it and satisfies {@code predicate}, or {@code null} if none is found (also when {@code object} is
	 * {@code null} or has no region). The reference object itself is skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #getFirstVisibleObjectInRange(WorldObject, Class, int, Predicate)} when first-in-iteration-order is acceptable; this method's full scan is only needed when uniform
	 * randomness over all matching candidates within range is required.
	 * @param <T> the {@link WorldObject} subtype to return
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param predicate additional filter applied to each candidate already matching {@code clazz} and within range
	 * @return a uniformly-random matching object, or {@code null}
	 */
	public static <T extends WorldObject> T getRandomVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Predicate<T> predicate)
	{
		if (object == null)
		{
			return null;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return null;
		}
		
		T selected = null;
		T firstValid = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					if (firstValid == null)
					{
						firstValid = cast;
					}
					
					count++;
					if (Rnd.get(count) == 0)
					{
						selected = cast;
					}
				}
			}
		}
		
		return (selected != null) ? selected : firstValid;
	}
	
	/**
	 * Invokes {@code action} on a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world. Does nothing if no match is found, or if {@code object} is {@code null} or has no region. The reference object itself is
	 * skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #forFirstVisibleObject(WorldObject, Class, Consumer)} when first-in-iteration-order is acceptable; this method's full scan is only needed when uniform randomness is
	 * required.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param action action invoked at most once, on the randomly-selected matching object
	 */
	public static <T extends WorldObject> void forRandomVisibleObject(WorldObject object, Class<T> clazz, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T selected = null;
		T firstValid = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (firstValid == null)
				{
					firstValid = cast;
				}
				
				count++;
				if (Rnd.get(count) == 0)
				{
					selected = cast;
				}
			}
		}
		
		final T target = (selected != null) ? selected : firstValid;
		if (target != null)
		{
			action.accept(target);
		}
	}
	
	/**
	 * Invokes {@code action} on a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and lies within {@code range} 3D-distance from it. Does nothing if no match is found, or if {@code object} is {@code null} or
	 * has no region. The reference object itself is skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #forFirstVisibleObjectInRange(WorldObject, Class, int, Consumer)} when first-in-iteration-order is acceptable; this method's full scan is only needed when uniform
	 * randomness over all matching candidates within range is required.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param action action invoked at most once, on the randomly-selected matching object
	 */
	public static <T extends WorldObject> void forRandomVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T selected = null;
		T firstValid = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (firstValid == null)
				{
					firstValid = cast;
				}
				
				count++;
				if (Rnd.get(count) == 0)
				{
					selected = cast;
				}
			}
		}
		
		final T target = (selected != null) ? selected : firstValid;
		if (target != null)
		{
			action.accept(target);
		}
	}
	
	/**
	 * Invokes {@code action} on a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world and satisfies {@code predicate}. Does nothing if no match is found, or if {@code object} is {@code null} or has no region.
	 * The reference object itself is skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #forFirstVisibleObject(WorldObject, Class, Predicate, Consumer)} when first-in-iteration-order is acceptable; this method's full scan is only needed when uniform randomness
	 * is required.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param predicate additional filter applied to each candidate already matching {@code clazz}
	 * @param action action invoked at most once, on the randomly-selected matching object
	 */
	public static <T extends WorldObject> void forRandomVisibleObject(WorldObject object, Class<T> clazz, Predicate<T> predicate, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T selected = null;
		T firstValid = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					if (firstValid == null)
					{
						firstValid = cast;
					}
					
					count++;
					if (Rnd.get(count) == 0)
					{
						selected = cast;
					}
				}
			}
		}
		
		final T target = (selected != null) ? selected : firstValid;
		if (target != null)
		{
			action.accept(target);
		}
	}
	
	/**
	 * Invokes {@code action} on a uniformly-random visible {@link WorldObject} of type {@code T} in the reference object's surrounding regions that shares its instance world, lies within {@code range} 3D-distance from it and satisfies {@code predicate}. Does nothing if no match is found, or if
	 * {@code
	 * object} is {@code null} or has no region. The reference object itself is skipped.
	 * <p>
	 * Uses single-pass reservoir sampling (Algorithm R with k=1) - no {@link ArrayList} allocation, O(1) memory. Prefer {@link #forFirstVisibleObjectInRange(WorldObject, Class, int, Predicate, Consumer)} when first-in-iteration-order is acceptable; this method's full scan is only needed when
	 * uniform randomness over all matching candidates within range is required.
	 * @param <T> the {@link WorldObject} subtype to consume
	 * @param object the reference object whose surrounding regions are scanned
	 * @param clazz the type filter
	 * @param range the maximum inclusive 3D distance from {@code object}
	 * @param predicate additional filter applied to each candidate already matching {@code clazz} and within range
	 * @param action action invoked at most once, on the randomly-selected matching object
	 */
	public static <T extends WorldObject> void forRandomVisibleObjectInRange(WorldObject object, Class<T> clazz, int range, Predicate<T> predicate, Consumer<T> action)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		T selected = null;
		T firstValid = null;
		int count = 0;
		final WorldRegion[] surroundingRegions = worldRegion.getSurroundingRegions();
		for (int i = 0; i < surroundingRegions.length; i++)
		{
			final Collection<WorldObject> visibleObjects = surroundingRegions[i].getVisibleObjects();
			if (visibleObjects.isEmpty())
			{
				continue;
			}
			
			for (WorldObject wo : visibleObjects)
			{
				if ((wo == object) || !clazz.isInstance(wo) || (wo.getInstanceId() != object.getInstanceId()) || (wo.calculateDistance3D(object) > range))
				{
					continue;
				}
				
				final T cast = clazz.cast(wo);
				if (predicate.test(cast))
				{
					if (firstValid == null)
					{
						firstValid = cast;
					}
					
					count++;
					if (Rnd.get(count) == 0)
					{
						selected = cast;
					}
				}
			}
		}
		
		final T target = (selected != null) ? selected : firstValid;
		if (target != null)
		{
			action.accept(target);
		}
	}
	
	/**
	 * Sends a packet to every other player visible from the given object's surrounding regions, sharing the same instance world.<br>
	 * The source object itself is excluded from the broadcast.
	 * @param object the reference WorldObject whose surrounding regions define the broadcast area
	 * @param packet the ServerPacket to send to each nearby player
	 */
	public static void broadcastToVisiblePlayers(WorldObject object, ServerPacket packet)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		packet.sendInBroadcast();
		
		final WorldRegion[] regions = worldRegion.getSurroundingRegions();
		if (regions != null)
		{
			for (int i = 0; i < regions.length; i++)
			{
				final Collection<WorldObject> objects = regions[i].getVisibleObjects();
				if (objects.isEmpty())
				{
					continue;
				}
				
				for (WorldObject nearby : objects)
				{
					if ((nearby == object) || !nearby.isPlayer() || (nearby.getInstanceId() != object.getInstanceId()))
					{
						continue;
					}
					
					nearby.asPlayer().sendPacket(packet);
				}
			}
		}
	}
	
	/**
	 * Sends a packet to every other player visible from the given object's surrounding regions, sharing the same instance world and within the specified range.<br>
	 * The source object itself is excluded from the broadcast.
	 * @param object the reference WorldObject whose surrounding regions define the broadcast area
	 * @param packet the ServerPacket to send to each nearby player
	 * @param range the maximum 3D distance from the object at which a player will receive the packet
	 */
	public static void broadcastToVisiblePlayersInRange(WorldObject object, ServerPacket packet, int range)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		packet.sendInBroadcast();
		
		final WorldRegion[] regions = worldRegion.getSurroundingRegions();
		if (regions != null)
		{
			for (int i = 0; i < regions.length; i++)
			{
				final Collection<WorldObject> objects = regions[i].getVisibleObjects();
				if (objects.isEmpty())
				{
					continue;
				}
				
				for (WorldObject nearby : objects)
				{
					if ((nearby == object) || !nearby.isPlayer() || (nearby.getInstanceId() != object.getInstanceId()) || (nearby.calculateDistance3D(object) > range))
					{
						continue;
					}
					
					nearby.asPlayer().sendPacket(packet);
				}
			}
		}
	}
	
	/**
	 * Sends a packet to the given object (if it is a player) and to every other player visible from its surrounding regions, sharing the same instance world.
	 * @param object the reference WorldObject whose surrounding regions define the broadcast area
	 * @param packet the ServerPacket to send to the object and each nearby player
	 */
	public static void broadcastToSelfAndVisiblePlayers(WorldObject object, ServerPacket packet)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		packet.sendInBroadcast();
		
		final WorldRegion[] regions = worldRegion.getSurroundingRegions();
		if (regions != null)
		{
			for (int i = 0; i < regions.length; i++)
			{
				final Collection<WorldObject> objects = regions[i].getVisibleObjects();
				if (objects.isEmpty())
				{
					continue;
				}
				
				for (WorldObject nearby : objects)
				{
					if (!nearby.isPlayer() || (nearby.getInstanceId() != object.getInstanceId()))
					{
						continue;
					}
					
					nearby.asPlayer().sendPacket(packet);
				}
			}
		}
	}
	
	/**
	 * Sends a packet to the given object (if it is a player) and to every other player visible from its surrounding regions, sharing the same instance world and within the specified range.
	 * @param object the reference WorldObject whose surrounding regions define the broadcast area
	 * @param packet the ServerPacket to send to the object and each nearby player
	 * @param range the maximum 3D distance from the object at which a player will receive the packet
	 */
	public static void broadcastToSelfAndVisiblePlayersInRange(WorldObject object, ServerPacket packet, int range)
	{
		if (object == null)
		{
			return;
		}
		
		final WorldRegion worldRegion = getRegion(object);
		if (worldRegion == null)
		{
			return;
		}
		
		packet.sendInBroadcast();
		
		final WorldRegion[] regions = worldRegion.getSurroundingRegions();
		if (regions != null)
		{
			for (int i = 0; i < regions.length; i++)
			{
				final Collection<WorldObject> objects = regions[i].getVisibleObjects();
				if (objects.isEmpty())
				{
					continue;
				}
				
				for (WorldObject nearby : objects)
				{
					if (!nearby.isPlayer() || (nearby.getInstanceId() != object.getInstanceId()) || (nearby.calculateDistance3D(object) > range))
					{
						continue;
					}
					
					nearby.asPlayer().sendPacket(packet);
				}
			}
		}
	}
	
	/**
	 * Sends a packet to every online player currently registered in the world, regardless of instance or location.
	 * @param packet the ServerPacket to send to all online players
	 */
	public static void broadcastToAllOnlinePlayers(ServerPacket packet)
	{
		packet.sendInBroadcast();
		
		for (Player player : _allPlayers.values())
		{
			if (player.isOnline())
			{
				player.sendPacket(packet);
			}
		}
	}
	
	/**
	 * Broadcasts a non-critical announcement chat message to every online player.
	 * @param text the text to be sent as a regular announcement
	 */
	public static void broadcastToAllOnlinePlayers(String text)
	{
		broadcastToAllOnlinePlayers(text, false);
	}
	
	/**
	 * Broadcasts an announcement chat message to every online player, optionally as a critical announcement.
	 * @param text the text to be sent as an announcement
	 * @param isCritical {@code true} to send as a CRITICAL_ANNOUNCE chat type, {@code false} to send as a regular ANNOUNCEMENT
	 */
	public static void broadcastToAllOnlinePlayers(String text, boolean isCritical)
	{
		broadcastToAllOnlinePlayers(new CreatureSay(null, isCritical ? ChatType.CRITICAL_ANNOUNCE : ChatType.ANNOUNCEMENT, "", text));
	}
	
	/**
	 * Broadcasts an on-screen message to every online player for a default duration of 10 seconds.
	 * @param text the text to display on each player's screen
	 */
	public static void broadcastToAllOnlinePlayersOnScreen(String text)
	{
		broadcastToAllOnlinePlayers(new ExShowScreenMessage(text, 10000));
	}
	
	/**
	 * Calculate the current WorldRegions of the object according to its position (x,y). <b><u>Example of use</u>:</b>
	 * <li>Set position of a new WorldObject (drop, spawn...)</li>
	 * <li>Update position of a WorldObject after a movement</li><br>
	 * @param object the object
	 * @return
	 */
	public static WorldRegion getRegion(WorldObject object)
	{
		try
		{
			final int z = object.getZ();
			final int regionZ = calculateRegionZ(z);
			return _worldRegions[(object.getX() >> SHIFT_BY) + OFFSET_X][(object.getY() >> SHIFT_BY) + OFFSET_Y][regionZ];
		}
		catch (ArrayIndexOutOfBoundsException e) // Precaution. Moved at invalid region?
		{
			disposeOutOfBoundsObject(object);
			return null;
		}
	}
	
	public static WorldRegion getRegion(int x, int y, int z)
	{
		try
		{
			final int regionZ = calculateRegionZ(z);
			return _worldRegions[(x >> SHIFT_BY) + OFFSET_X][(y >> SHIFT_BY) + OFFSET_Y][regionZ];
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			LOGGER.warning(World.class.getSimpleName() + ": Incorrect world region X: " + ((x >> SHIFT_BY) + OFFSET_X) + " Y: " + ((y >> SHIFT_BY) + OFFSET_Y) + " Z: " + calculateRegionZ(z));
			return null;
		}
	}
	
	/**
	 * Calculate the region Z index based on Z coordinate
	 * @param z The Z coordinate
	 * @return The region Z index (0-3)
	 */
	private static int calculateRegionZ(int z)
	{
		// Handle values outside the normal range.
		if (z < WORLD_Z_MIN)
		{
			return 0; // Bottom region.
		}
		if (z > WORLD_Z_MAX)
		{
			return REGIONS_Z - 1; // Top region.
		}
		
		// Calculate the region Z index (0-based).
		return (z - WORLD_Z_MIN) / Z_REGION_SIZE;
	}
	
	/**
	 * Returns the whole 3d array containing the world regions used by ZoneData.java to setup zones inside the world regions
	 * @return
	 */
	public static WorldRegion[][][] getWorldRegions()
	{
		return _worldRegions;
	}
	
	public static synchronized void disposeOutOfBoundsObject(WorldObject object)
	{
		if (object.isPlayer())
		{
			object.asCreature().stopMove(object.asPlayer().getLastServerPosition());
		}
		else if (object.isSummon())
		{
			final Summon summon = object.asSummon();
			summon.unSummon(summon.getOwner());
		}
		else if (_allObjects.remove(object.getObjectId()) != null)
		{
			if (object.isNpc())
			{
				final Npc npc = object.asNpc();
				LOGGER.warning("Deleting npc " + object.getName() + " NPCID[" + npc.getId() + "] from invalid location X:" + object.getX() + " Y:" + object.getY() + " Z:" + object.getZ());
				npc.deleteMe();
				
				final Spawn spawn = npc.getSpawn();
				if (spawn != null)
				{
					LOGGER.warning("Spawn location X:" + spawn.getX() + " Y:" + spawn.getY() + " Z:" + spawn.getZ() + " Heading:" + spawn.getHeading());
				}
			}
			else if (object.isCreature())
			{
				LOGGER.warning("Deleting object " + object.getName() + " OID[" + object.getObjectId() + "] from invalid location X:" + object.getX() + " Y:" + object.getY() + " Z:" + object.getZ());
				object.asCreature().deleteMe();
			}
			
			if (object.getWorldRegion() != null)
			{
				object.getWorldRegion().removeVisibleObject(object);
			}
		}
	}
}

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
package org.l2jmobius.gameserver.entity.spawns;

import java.util.ArrayList;
import java.util.List;

/**
 * A single NPC entry of a spawn group, holding its quota and one spawn slot per territory.
 * @author Altur
 */
public class SpawnGroupEntry
{
	private final int _total;
	private final int _respawnDelay;
	private final int _respawnRandom;
	private final List<Spawn> _slots = new ArrayList<>();
	private int _aliveOrReserved = 0;
	
	public SpawnGroupEntry(int total, int respawnDelay, int respawnRandom)
	{
		_total = total;
		_respawnDelay = respawnDelay;
		_respawnRandom = respawnRandom;
	}
	
	public int getTotal()
	{
		return _total;
	}
	
	public int getRespawnDelay()
	{
		return _respawnDelay;
	}
	
	public int getRespawnRandom()
	{
		return _respawnRandom;
	}
	
	public List<Spawn> getSlots()
	{
		return _slots;
	}
	
	public void addSlot(Spawn spawn)
	{
		_slots.add(spawn);
	}
	
	public int getAliveOrReserved()
	{
		return _aliveOrReserved;
	}
	
	public void setAliveOrReserved(int count)
	{
		_aliveOrReserved = count;
	}
}

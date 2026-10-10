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
package org.l2jmobius.gameserver.data;

import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.entity.spawns.SpawnGroup;

/**
 * Registry of the spawn groups declared by the datapack.
 * @author Altur
 */
public class SpawnGroupTable
{
	private final Collection<SpawnGroup> _groups = new CopyOnWriteArrayList<>();
	
	protected SpawnGroupTable()
	{
	}
	
	public Collection<SpawnGroup> getGroups()
	{
		return _groups;
	}
	
	public void addGroup(SpawnGroup group)
	{
		_groups.add(group);
	}
	
	public void clear()
	{
		_groups.clear();
	}
	
	/**
	 * Stops the unit replacement of every group, used by the unspawn admin commands.
	 */
	public void deactivateAll()
	{
		for (SpawnGroup group : _groups)
		{
			group.setActive(false);
		}
	}
	
	public static SpawnGroupTable getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final SpawnGroupTable INSTANCE = new SpawnGroupTable();
	}
}

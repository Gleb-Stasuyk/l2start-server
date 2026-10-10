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
package org.l2jmobius.gameserver.taskmanagers;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.entity.spawns.SpawnGroup;
import org.l2jmobius.gameserver.entity.spawns.SpawnGroupEntry;

/**
 * Materializes pending spawn group replacements whose units were already reserved on death.<br>
 * A queue is used instead of a map because the same entry can have several replacements pending.
 * @author Altur
 */
public class SpawnGroupTaskManager implements Runnable
{
	private static final Queue<PendingGroupSpawn> PENDING_SPAWNS = new ConcurrentLinkedQueue<>();
	private static boolean _working = false;
	
	protected SpawnGroupTaskManager()
	{
		ThreadPool.scheduleAtFixedRate(this, 0, 1000);
	}
	
	@Override
	public void run()
	{
		if (_working)
		{
			return;
		}
		
		_working = true;
		
		if (!PENDING_SPAWNS.isEmpty())
		{
			final long currentTime = System.currentTimeMillis();
			final Iterator<PendingGroupSpawn> iterator = PENDING_SPAWNS.iterator();
			while (iterator.hasNext())
			{
				final PendingGroupSpawn pending = iterator.next();
				if (currentTime > pending._time)
				{
					iterator.remove();
					pending._group.spawnUnits(pending._entry, 1);
				}
			}
		}
		
		_working = false;
	}
	
	public void add(SpawnGroup group, SpawnGroupEntry entry, long time)
	{
		PENDING_SPAWNS.add(new PendingGroupSpawn(group, entry, time));
	}
	
	public void clear()
	{
		PENDING_SPAWNS.clear();
	}
	
	private static class PendingGroupSpawn
	{
		final SpawnGroup _group;
		final SpawnGroupEntry _entry;
		final long _time;
		
		PendingGroupSpawn(SpawnGroup group, SpawnGroupEntry entry, long time)
		{
			_group = group;
			_entry = entry;
			_time = time;
		}
	}
	
	public static SpawnGroupTaskManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final SpawnGroupTaskManager INSTANCE = new SpawnGroupTaskManager();
	}
}

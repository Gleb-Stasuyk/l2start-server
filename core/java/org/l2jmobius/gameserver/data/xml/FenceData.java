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
package org.l2jmobius.gameserver.data.xml;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldRegion;
import org.l2jmobius.gameserver.entity.actor.enums.creature.FenceState;
import org.l2jmobius.gameserver.entity.actor.instance.Fence;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * @author HorridoJoho, BazookaRpm
 */
public class FenceData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(FenceData.class.getSimpleName());
	
	private static final int MAX_Z_DIFF = 100;
	
	private final Map<Integer, Fence> _fences = new ConcurrentHashMap<>();
	
	protected FenceData()
	{
		load();
	}
	
	@Override
	public void load()
	{
		if (!_fences.isEmpty())
		{
			// Remove old fences when reloading.
			_fences.values().forEach(this::removeFence);
		}
		
		parseDatapackFile("data/FenceData.xml");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _fences.size() + " fences.");
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "fence", this::spawnFence));
	}
	
	public int getLoadedElementsCount()
	{
		return _fences.size();
	}
	
	private void spawnFence(Node fenceNode)
	{
		final StatSet set = new StatSet(parseAttributes(fenceNode));
		spawnFence(set.getInt("x"), set.getInt("y"), set.getInt("z"), set.getString("name"), set.getInt("width"), set.getInt("length"), set.getInt("height"), 0, set.getEnum("state", FenceState.class, FenceState.CLOSED));
	}
	
	public Fence spawnFence(int x, int y, int z, int width, int length, int height, int instanceId, FenceState state)
	{
		return spawnFence(x, y, z, null, width, length, height, instanceId, state);
	}
	
	public Fence spawnFence(int x, int y, int z, String name, int width, int length, int height, int instanceId, FenceState state)
	{
		final Fence fence = new Fence(x, y, name, width, length, height, state);
		if (instanceId > 0)
		{
			fence.setInstanceId(instanceId);
		}
		
		fence.spawnMe(x, y, z);
		addFence(fence);
		
		return fence;
	}
	
	private void addFence(Fence fence)
	{
		_fences.put(fence.getObjectId(), fence);
	}
	
	public void removeFence(Fence fence)
	{
		_fences.remove(fence.getObjectId());
	}
	
	public Map<Integer, Fence> getFences()
	{
		return _fences;
	}
	
	public Fence getFence(int objectId)
	{
		return _fences.get(objectId);
	}
	
	public boolean checkIfFenceBetween(int x, int y, int z, int tx, int ty, int tz, int instanceId)
	{
		final WorldRegion region = World.getRegion(x, y, z);
		final Collection<Fence> fences = region != null ? region.getFences() : null;
		if ((fences == null) || fences.isEmpty())
		{
			return false;
		}
		
		for (Fence fence : fences)
		{
			// Check if fence is geodata enabled.
			if (!fence.getState().isGeodataEnabled())
			{
				continue;
			}
			
			// Check if fence is within the instance we search for.
			if (fence.getInstanceId() != instanceId)
			{
				continue;
			}
			
			final int xMin = fence.getXMin();
			if ((x < xMin) && (tx < xMin))
			{
				continue;
			}
			
			final int xMax = fence.getXMax();
			if ((x > xMax) && (tx > xMax))
			{
				continue;
			}
			
			final int yMin = fence.getYMin();
			if ((y < yMin) && (ty < yMin))
			{
				continue;
			}
			
			final int yMax = fence.getYMax();
			if ((y > yMax) && (ty > yMax))
			{
				continue;
			}
			
			if ((x > xMin) && (tx > xMin) && (x < xMax) && (tx < xMax) && (y > yMin) && (ty > yMin) && (y < yMax) && (ty < yMax))
			{
				continue;
			}
			
			if ((crossLinePart(xMin, yMin, xMax, yMin, x, y, tx, ty, xMin, yMin, xMax, yMax) || crossLinePart(xMax, yMin, xMax, yMax, x, y, tx, ty, xMin, yMin, xMax, yMax) || crossLinePart(xMax, yMax, xMin, yMax, x, y, tx, ty, xMin, yMin, xMax, yMax) || crossLinePart(xMin, yMax, xMin, yMin, x, y, tx, ty, xMin, yMin, xMax, yMax)) && (z > (fence.getZ() - MAX_Z_DIFF)) && (z < (fence.getZ() + MAX_Z_DIFF)))
			{
				return true;
			}
		}
		
		return false;
	}
	
	private boolean crossLinePart(double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4, double xMin, double yMin, double xMax, double yMax)
	{
		final double[] result = intersection(x1, y1, x2, y2, x3, y3, x4, y4);
		if (result == null)
		{
			return false;
		}
		
		final double xCross = result[0];
		if ((xCross <= xMax) && (xCross >= xMin))
		{
			return true;
		}
		
		final double yCross = result[1];
		if ((yCross <= yMax) && (yCross >= yMin))
		{
			return true;
		}
		
		return false;
	}
	
	private double[] intersection(double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4)
	{
		final double d = ((x1 - x2) * (y3 - y4)) - ((y1 - y2) * (x3 - x4));
		if (d == 0)
		{
			return null;
		}
		
		final double xi = (((x3 - x4) * ((x1 * y2) - (y1 * x2))) - ((x1 - x2) * ((x3 * y4) - (y3 * x4)))) / d;
		final double yi = (((y3 - y4) * ((x1 * y2) - (y1 * x2))) - ((y1 - y2) * ((x3 * y4) - (y3 * x4)))) / d;
		return new double[]
		{
			xi,
			yi
		};
	}
	
	public static FenceData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FenceData INSTANCE = new FenceData();
	}
}

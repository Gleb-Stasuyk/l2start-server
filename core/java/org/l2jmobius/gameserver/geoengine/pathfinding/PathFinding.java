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
package org.l2jmobius.gameserver.geoengine.pathfinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.geoengine.GeoEngine;

/**
 * Entry point for pathfinding.<br>
 * Buffers are owned per-thread via {@link ThreadLocal} - no shared pool, no locks.<br>
 * Each thread lazily allocates one {@link NodeBuffer} per configured size class on first use; subsequent calls of compatible size reuse the same instance via {@link NodeBuffer#free()}.
 * @author Mobius
 */
public class PathFinding
{
	private static final Logger LOGGER = Logger.getLogger(PathFinding.class.getName());
	
	private final int[] _sizeClasses;
	private final ThreadLocal<NodeBuffer[]> _tlBuffers;
	
	protected PathFinding()
	{
		try
		{
			final String[] array = GeoEngineConfig.PATHFIND_BUFFERS.split(";");
			final int[] parsed = new int[array.length];
			for (int i = 0; i < array.length; i++)
			{
				final String[] args = array[i].split("x");
				if (args.length < 1)
				{
					throw new Exception("Invalid buffer definition: " + array[i]);
				}
				
				parsed[i] = Integer.parseInt(args[0]);
			}
			
			Arrays.sort(parsed);
			_sizeClasses = parsed;
			_tlBuffers = ThreadLocal.withInitial(() -> new NodeBuffer[_sizeClasses.length]);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "CellPathFinding: Problem during buffer init: " + e.getMessage(), e);
			throw new Error("CellPathFinding: load aborted");
		}
	}
	
	/**
	 * Converts a geodata position to a pathnode position.
	 * @param geoPos the geodata position
	 * @return the corresponding pathnode position
	 */
	public short getNodePos(int geoPos)
	{
		return (short) (geoPos >> 3);
	}
	
	/**
	 * Converts a node position to a pathnode block position.
	 * @param nodePos the node position
	 * @return the pathnode block position (range: 0 to 255)
	 */
	public short getNodeBlock(int nodePos)
	{
		return (short) (nodePos % 256);
	}
	
	/**
	 * Retrieves the X region of a node position.
	 * @param nodePos the node position
	 * @return the X region
	 */
	public byte getRegionX(int nodePos)
	{
		return (byte) ((nodePos >> 8) + World.TILE_X_MIN);
	}
	
	/**
	 * Retrieves the Y region of a node position.
	 * @param nodePos the node position
	 * @return the Y region
	 */
	public byte getRegionY(int nodePos)
	{
		return (byte) ((nodePos >> 8) + World.TILE_Y_MIN);
	}
	
	/**
	 * Calculates the region offset based on region X and Y.
	 * @param rx the X region
	 * @param ry the Y region
	 * @return the region offset
	 */
	public short getRegionOffset(byte rx, byte ry)
	{
		return (short) ((rx << 5) + ry);
	}
	
	/**
	 * Converts a pathnode X coordinate to a world X coordinate.
	 * @param nodeX the pathnode X coordinate
	 * @return the corresponding world X coordinate
	 */
	public int calculateWorldX(short nodeX)
	{
		return World.WORLD_X_MIN + (nodeX * 128) + 48;
	}
	
	/**
	 * Converts a pathnode Y coordinate to a world Y coordinate.
	 * @param nodeY the pathnode Y coordinate
	 * @return the corresponding world Y coordinate
	 */
	public int calculateWorldY(short nodeY)
	{
		return World.WORLD_Y_MIN + (nodeY * 128) + 48;
	}
	
	/**
	 * Finds a path between two world positions considering geodata and an instance.
	 * @param x the starting X coordinate
	 * @param y the starting Y coordinate
	 * @param z the starting Z coordinate
	 * @param tx the target X coordinate
	 * @param ty the target Y coordinate
	 * @param tz the target Z coordinate
	 * @param instanceId the instance ID to consider for pathfinding
	 * @param playable whether the pathfinding is for a playable character
	 * @return a list of pathnodes forming the path, or {@code null} if no path is found
	 */
	public List<GeoLocation> findPath(int x, int y, int z, int tx, int ty, int tz, int instanceId, boolean playable)
	{
		final GeoEngine geoEngine = GeoEngine.getInstance();
		if (!geoEngine.hasGeo(x, y))
		{
			return null;
		}
		
		if (!geoEngine.hasGeo(tx, ty))
		{
			return null;
		}
		
		final int gx = GeoEngine.getGeoX(x);
		final int gy = GeoEngine.getGeoY(y);
		final int gtx = GeoEngine.getGeoX(tx);
		final int gty = GeoEngine.getGeoY(ty);
		final NodeBuffer buffer = alloc(64 + (2 * Math.max(Math.abs(gx - gtx), Math.abs(gy - gty))));
		if (buffer == null)
		{
			return null;
		}
		
		final int gz = geoEngine.getHeight(x, y, z);
		final int gtz = geoEngine.getHeight(tx, ty, tz);
		List<GeoLocation> path;
		try
		{
			path = buffer.findPath(gx, gy, gz, gtx, gty, gtz);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "CellPathFinding: Problem finding path: " + e.getMessage(), e);
			return null;
		}
		finally
		{
			buffer.free();
		}
		
		if (path == null)
		{
			return null;
		}
		
		if ((path.size() < 3) || (GeoEngineConfig.MAX_POSTFILTER_PASSES <= 0))
		{
			return path;
		}
		
		return applyPostFiltering(path, x, y, z, instanceId, playable);
	}
	
	/**
	 * Apply post-filtering to remove unnecessary waypoints. Enhanced version with better logic and configurable iterations.
	 * @param initialPath the initial path to optimize
	 * @param startX starting world X coordinate
	 * @param startY starting world Y coordinate
	 * @param startZ starting world Z coordinate
	 * @param instanceId the instance ID to consider for pathfinding
	 * @param playable whether the pathfinding is for a playable character
	 * @return optimized path
	 */
	private List<GeoLocation> applyPostFiltering(List<GeoLocation> initialPath, int startX, int startY, int startZ, int instanceId, boolean playable)
	{
		final GeoEngine geoEngine = GeoEngine.getInstance();
		List<GeoLocation> path = initialPath;
		
		int pass = 0;
		boolean changed;
		do
		{
			pass++;
			changed = false;
			int currentX = startX;
			int currentY = startY;
			int currentZ = startZ;
			
			final List<GeoLocation> optimizedPath = new ArrayList<>();
			for (int i = 0; i < (path.size() - 1); i++)
			{
				final GeoLocation current = path.get(i);
				final GeoLocation next = path.get(i + 1);
				
				// Check if we can move directly to the next waypoint.
				if (geoEngine.canMoveToTarget(currentX, currentY, currentZ, next.getX(), next.getY(), next.getZ(), instanceId))
				{
					// Skip current waypoint.
					changed = true;
				}
				else
				{
					// Keep current waypoint.
					optimizedPath.add(current);
					currentX = current.getX();
					currentY = current.getY();
					currentZ = current.getZ();
				}
			}
			
			// Always add the final destination.
			if (!path.isEmpty())
			{
				optimizedPath.add(path.get(path.size() - 1));
			}
			
			path = optimizedPath;
		}
		while (playable && changed && (path.size() > 2) && (pass < GeoEngineConfig.MAX_POSTFILTER_PASSES));
		
		return path;
	}
	
	/**
	 * Returns a per-thread buffer whose map size is at least {@code size}. Lazily allocates one buffer per size class per thread; never blocks.
	 * @param size the minimum map size required
	 * @return a usable buffer, or {@code null} when {@code size} exceeds the largest configured class
	 */
	private NodeBuffer alloc(int size)
	{
		final NodeBuffer[] perThread = _tlBuffers.get();
		for (int i = 0; i < _sizeClasses.length; i++)
		{
			if (_sizeClasses[i] >= size)
			{
				NodeBuffer buffer = perThread[i];
				if (buffer == null)
				{
					buffer = new NodeBuffer(_sizeClasses[i]);
					perThread[i] = buffer;
				}
				
				return buffer;
			}
		}
		
		return null;
	}
	
	public static PathFinding getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final PathFinding INSTANCE = new PathFinding();
	}
}

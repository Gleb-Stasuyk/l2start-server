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
import java.util.Collections;
import java.util.List;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;

/**
 * Data-oriented A* pathfinder.<br>
 * Cell state is held in parallel primitive arrays indexed by {@code cellIdx = (aY * mapSize) + aX}.<br>
 * The open list is a primitive indexed binary min-heap with O(log N) decrease-key; the closed-set and open-set are bit flags packed into {@link #_flags}.<br>
 * Free runs in O(touched), not O(mapSize^2). Buffers are owned by a single thread via ThreadLocal in {@link PathFinding} - no internal locking.
 * @author Mobius
 */
public class NodeBuffer
{
	private static final int MAX_ITERATIONS = 7000;
	private static final int COST_SCALE = 1000;
	private static final int Z_TOLERANCE = 64;
	private static final int Z_STEP_LIMIT = 16;
	
	private static final byte FLAG_TOUCHED = 0x1;
	private static final byte FLAG_OPEN = 0x2;
	private static final byte FLAG_CLOSED = 0x4;
	
	private final int _mapSize;
	private final int _capacity;
	
	// Scaled weight constants captured at construction.
	private final int _wLow;
	private final int _wMedium;
	private final int _wHigh;
	private final int _wDiagonal;
	private final int _hStraight; // octile straight step cost
	private final int _hDiagonalExcess; // octile diagonal step extra cost (diag - straight)
	private final boolean _diagonalStrategy;
	
	// Cell state (indexed by cellIdx).
	private final int[] _parent;
	private final int[] _gCost;
	private final int[] _hCost;
	private final int[] _fCost;
	private final byte[] _flags;
	private final byte[] _nswe;
	private final short[] _z;
	
	// Touched-cell ledger so free() runs in O(touched).
	private final int[] _touched;
	private int _touchedCount;
	
	// Primitive indexed binary min-heap.
	private final int[] _heap;
	private final int[] _heapPos;
	private int _heapSize;
	
	private int _baseX;
	private int _baseY;
	private int _targetX;
	private int _targetY;
	private int _targetZ;
	
	public NodeBuffer(int size)
	{
		_mapSize = size;
		_capacity = size * size;
		_parent = new int[_capacity];
		_gCost = new int[_capacity];
		_hCost = new int[_capacity];
		_fCost = new int[_capacity];
		_flags = new byte[_capacity];
		_nswe = new byte[_capacity];
		_z = new short[_capacity];
		_touched = new int[_capacity];
		_heap = new int[_capacity];
		_heapPos = new int[_capacity];
		
		_wLow = (int) (GeoEngineConfig.LOW_WEIGHT * COST_SCALE);
		_wMedium = (int) (GeoEngineConfig.MEDIUM_WEIGHT * COST_SCALE);
		_wHigh = (int) (GeoEngineConfig.HIGH_WEIGHT * COST_SCALE);
		_wDiagonal = (int) (GeoEngineConfig.DIAGONAL_WEIGHT * COST_SCALE);
		_hStraight = _wLow;
		_hDiagonalExcess = _wDiagonal - _wLow;
		_diagonalStrategy = GeoEngineConfig.ADVANCED_DIAGONAL_STRATEGY;
	}
	
	public int getMapSize()
	{
		return _mapSize;
	}
	
	/**
	 * Runs A* from start to target in geo-cell space.<br>
	 * Returns the path as a list of {@link GeoLocation} waypoints (in forward order, start exclusive, target inclusive), collapsing colinear runs into endpoints.
	 * @param x the start geo X
	 * @param y the start geo Y
	 * @param z the start world Z
	 * @param tx the target geo X
	 * @param ty the target geo Y
	 * @param tz the target world Z
	 * @return the waypoint list, or {@code null} when no path exists within the iteration budget
	 */
	public List<GeoLocation> findPath(int x, int y, int z, int tx, int ty, int tz)
	{
		_baseX = x + ((tx - x - _mapSize) / 2);
		_baseY = y + ((ty - y - _mapSize) / 2);
		_targetX = tx;
		_targetY = ty;
		_targetZ = tz;
		
		final int startIdx = cellIdx(x, y);
		if (startIdx < 0)
		{
			return null;
		}
		
		touch(startIdx, x, y, z);
		_gCost[startIdx] = 0;
		_fCost[startIdx] = _hCost[startIdx];
		heapPush(startIdx);
		
		for (int count = 0; count < MAX_ITERATIONS; count++)
		{
			if (_heapSize == 0)
			{
				return null;
			}
			
			final int currIdx = heapPoll();
			_flags[currIdx] &= ~FLAG_OPEN;
			_flags[currIdx] |= FLAG_CLOSED;
			
			final int currX = idxToX(currIdx);
			final int currY = idxToY(currIdx);
			final int currZ = _z[currIdx];
			
			if ((currX == _targetX) && (currY == _targetY) && (Math.abs(currZ - _targetZ) < Z_TOLERANCE))
			{
				return constructPath(currIdx);
			}
			
			expandNeighbors(currIdx, currX, currY, currZ);
		}
		
		return null;
	}
	
	/**
	 * Resets touched cells and the heap so the buffer can be reused. Runs in O(touched) rather than O(mapSize^2).
	 */
	public void free()
	{
		for (int i = 0; i < _touchedCount; i++)
		{
			_flags[_touched[i]] = 0;
		}
		
		_touchedCount = 0;
		_heapSize = 0;
	}
	
	private int cellIdx(int gx, int gy)
	{
		final int aX = gx - _baseX;
		if ((aX < 0) || (aX >= _mapSize))
		{
			return -1;
		}
		
		final int aY = gy - _baseY;
		if ((aY < 0) || (aY >= _mapSize))
		{
			return -1;
		}
		
		return (aY * _mapSize) + aX;
	}
	
	private int idxToX(int idx)
	{
		return _baseX + (idx % _mapSize);
	}
	
	private int idxToY(int idx)
	{
		return _baseY + (idx / _mapSize);
	}
	
	/**
	 * Lazily initialises a cell on first contact and records it in the touched ledger.<br>
	 * The NSWE bitmask and nearest Z are queried from the geo engine exactly once per cell per pathfind.
	 * @param idx the cell index
	 * @param gx the geo X coordinate of the cell
	 * @param gy the geo Y coordinate of the cell
	 * @param refZ the reference world Z used to resolve the nearest geo layer
	 */
	private void touch(int idx, int gx, int gy, int refZ)
	{
		if ((_flags[idx] & FLAG_TOUCHED) != 0)
		{
			return;
		}
		
		final GeoEngine geo = GeoEngine.getInstance();
		byte nswe = 0;
		if (geo.checkNearestNswe(gx, gy, refZ, Cell.NSWE_NORTH))
		{
			nswe |= Cell.NSWE_NORTH;
		}
		
		if (geo.checkNearestNswe(gx, gy, refZ, Cell.NSWE_EAST))
		{
			nswe |= Cell.NSWE_EAST;
		}
		
		if (geo.checkNearestNswe(gx, gy, refZ, Cell.NSWE_SOUTH))
		{
			nswe |= Cell.NSWE_SOUTH;
		}
		
		if (geo.checkNearestNswe(gx, gy, refZ, Cell.NSWE_WEST))
		{
			nswe |= Cell.NSWE_WEST;
		}
		
		_nswe[idx] = nswe;
		_z[idx] = (short) geo.getNearestZ(gx, gy, refZ);
		_flags[idx] = FLAG_TOUCHED;
		_parent[idx] = -1;
		_gCost[idx] = Integer.MAX_VALUE;
		_hCost[idx] = computeH(gx, gy);
		_fCost[idx] = Integer.MAX_VALUE;
		_heapPos[idx] = -1;
		
		_touched[_touchedCount++] = idx;
	}
	
	/**
	 * Octile heuristic in pre-scaled integer arithmetic - admissible for the 8-connected grid using {@code LOW_WEIGHT} as the per-step cost and {@code DIAGONAL_WEIGHT} as the diagonal cost.<br>
	 * Z is folded into the neighbour weighting (see {@link #considerNeighbor}) rather than the heuristic to keep h consistent.
	 * @param gx the geo X coordinate of the cell being scored
	 * @param gy the geo Y coordinate of the cell being scored
	 * @return the scaled heuristic cost to the target
	 */
	private int computeH(int gx, int gy)
	{
		final int dx = Math.abs(gx - _targetX);
		final int dy = Math.abs(gy - _targetY);
		final int maxD = Math.max(dx, dy);
		final int minD = Math.min(dx, dy);
		return (maxD * _hStraight) + (minD * _hDiagonalExcess);
	}
	
	/**
	 * Discovers and relaxes all walkable neighbours of the current cell - four orthogonal neighbours always, plus the four diagonals when {@code ADVANCED_DIAGONAL_STRATEGY} is enabled and both flanking cells permit the diagonal.
	 * @param currIdx the index of the current cell
	 * @param x the geo X coordinate of the current cell
	 * @param y the geo Y coordinate of the current cell
	 * @param z the world Z of the current cell, used as reference for new touches
	 */
	private void expandNeighbors(int currIdx, int x, int y, int z)
	{
		final byte nswe = _nswe[currIdx];
		if (nswe == 0)
		{
			return;
		}
		
		int idxE = -1;
		int idxS = -1;
		int idxW = -1;
		int idxN = -1;
		
		if ((nswe & Cell.NSWE_EAST) != 0)
		{
			idxE = considerNeighbor(currIdx, x + 1, y, z, false);
		}
		
		if ((nswe & Cell.NSWE_SOUTH) != 0)
		{
			idxS = considerNeighbor(currIdx, x, y + 1, z, false);
		}
		
		if ((nswe & Cell.NSWE_WEST) != 0)
		{
			idxW = considerNeighbor(currIdx, x - 1, y, z, false);
		}
		
		if ((nswe & Cell.NSWE_NORTH) != 0)
		{
			idxN = considerNeighbor(currIdx, x, y - 1, z, false);
		}
		
		if (!_diagonalStrategy)
		{
			return;
		}
		
		if ((idxE >= 0) && (idxS >= 0) && ((_nswe[idxE] & Cell.NSWE_SOUTH) != 0) && ((_nswe[idxS] & Cell.NSWE_EAST) != 0))
		{
			considerNeighbor(currIdx, x + 1, y + 1, z, true);
		}
		
		if ((idxS >= 0) && (idxW >= 0) && ((_nswe[idxW] & Cell.NSWE_SOUTH) != 0) && ((_nswe[idxS] & Cell.NSWE_WEST) != 0))
		{
			considerNeighbor(currIdx, x - 1, y + 1, z, true);
		}
		
		if ((idxN >= 0) && (idxE >= 0) && ((_nswe[idxE] & Cell.NSWE_NORTH) != 0) && ((_nswe[idxN] & Cell.NSWE_EAST) != 0))
		{
			considerNeighbor(currIdx, x + 1, y - 1, z, true);
		}
		
		if ((idxN >= 0) && (idxW >= 0) && ((_nswe[idxW] & Cell.NSWE_NORTH) != 0) && ((_nswe[idxN] & Cell.NSWE_WEST) != 0))
		{
			considerNeighbor(currIdx, x - 1, y - 1, z, true);
		}
	}
	
	/**
	 * Relaxes a neighbouring cell against the current node - picks a movement weight from terrain (high for blocked/cliff, medium for adjacent-to-blocked, low or diagonal otherwise), then pushes or sifts up in the open heap when a better path is found.
	 * @param parentIdx the index of the parent cell
	 * @param nx the geo X coordinate of the neighbour
	 * @param ny the geo Y coordinate of the neighbour
	 * @param parentZ the world Z of the parent cell
	 * @param diagonal whether this neighbour is reached via a diagonal step
	 * @return the cell index of the neighbour, or {@code -1} if it falls outside the buffer
	 */
	private int considerNeighbor(int parentIdx, int nx, int ny, int parentZ, boolean diagonal)
	{
		final int idx = cellIdx(nx, ny);
		if (idx < 0)
		{
			return -1;
		}
		
		touch(idx, nx, ny, parentZ);
		
		if ((_flags[idx] & FLAG_CLOSED) != 0)
		{
			return idx;
		}
		
		final int neighborZ = _z[idx];
		final int stepZ = Math.abs(neighborZ - parentZ);
		
		final int weight;
		if (((_nswe[idx] & Cell.NSWE_ALL) != Cell.NSWE_ALL) || (stepZ > Z_STEP_LIMIT))
		{
			weight = _wHigh;
		}
		else if (isHighWeight(nx + 1, ny, neighborZ) || isHighWeight(nx - 1, ny, neighborZ) || isHighWeight(nx, ny + 1, neighborZ) || isHighWeight(nx, ny - 1, neighborZ))
		{
			weight = _wMedium;
		}
		else
		{
			weight = diagonal ? _wDiagonal : _wLow;
		}
		
		final int newGCost = _gCost[parentIdx] + weight;
		final boolean inOpen = (_flags[idx] & FLAG_OPEN) != 0;
		if (inOpen && (newGCost >= _gCost[idx]))
		{
			return idx;
		}
		
		_parent[idx] = parentIdx;
		_gCost[idx] = newGCost;
		_fCost[idx] = newGCost + _hCost[idx];
		if (inOpen)
		{
			heapSiftUp(_heapPos[idx]);
		}
		else
		{
			heapPush(idx);
		}
		
		return idx;
	}
	
	private boolean isHighWeight(int gx, int gy, int refZ)
	{
		final int idx = cellIdx(gx, gy);
		if (idx < 0)
		{
			return true;
		}
		
		touch(idx, gx, gy, refZ);
		return ((_nswe[idx] & Cell.NSWE_ALL) != Cell.NSWE_ALL) || (Math.abs(_z[idx] - refZ) > Z_STEP_LIMIT);
	}
	
	private void heapPush(int idx)
	{
		final int pos = _heapSize++;
		_heap[pos] = idx;
		_heapPos[idx] = pos;
		_flags[idx] |= FLAG_OPEN;
		heapSiftUp(pos);
	}
	
	private int heapPoll()
	{
		final int root = _heap[0];
		_heapPos[root] = -1;
		final int lastPos = --_heapSize;
		if (lastPos > 0)
		{
			final int moved = _heap[lastPos];
			_heap[0] = moved;
			_heapPos[moved] = 0;
			heapSiftDown(0);
		}
		
		return root;
	}
	
	private void heapSiftUp(int startPos)
	{
		int pos = startPos;
		final int idx = _heap[pos];
		final int fCost = _fCost[idx];
		final int hCost = _hCost[idx];
		while (pos > 0)
		{
			final int parentPos = (pos - 1) >>> 1;
			final int parentIdx = _heap[parentPos];
			final int parentF = _fCost[parentIdx];
			if ((fCost > parentF) || ((fCost == parentF) && (hCost >= _hCost[parentIdx])))
			{
				break;
			}
			
			_heap[pos] = parentIdx;
			_heapPos[parentIdx] = pos;
			pos = parentPos;
		}
		
		_heap[pos] = idx;
		_heapPos[idx] = pos;
	}
	
	private void heapSiftDown(int startPos)
	{
		int pos = startPos;
		final int idx = _heap[pos];
		final int fCost = _fCost[idx];
		final int hCost = _hCost[idx];
		final int half = _heapSize >>> 1;
		while (pos < half)
		{
			int childPos = (pos << 1) + 1;
			int childIdx = _heap[childPos];
			int childF = _fCost[childIdx];
			final int rightPos = childPos + 1;
			if (rightPos < _heapSize)
			{
				final int rightIdx = _heap[rightPos];
				final int rightF = _fCost[rightIdx];
				if ((rightF < childF) || ((rightF == childF) && (_hCost[rightIdx] < _hCost[childIdx])))
				{
					childPos = rightPos;
					childIdx = rightIdx;
					childF = rightF;
				}
			}
			
			if ((fCost < childF) || ((fCost == childF) && (hCost <= _hCost[childIdx])))
			{
				break;
			}
			
			_heap[pos] = childIdx;
			_heapPos[childIdx] = pos;
			pos = childPos;
		}
		
		_heap[pos] = idx;
		_heapPos[idx] = pos;
	}
	
	/**
	 * Walks the parent chain from the result cell backward, emitting a waypoint only when the movement direction changes.<br>
	 * Build is forward-then-reverse instead of {@code ArrayList.addFirst}, which is O(N) per insert.
	 * @param endIdx the cell index of the target node
	 * @return the path as a list of {@link GeoLocation} waypoints in forward order
	 */
	private List<GeoLocation> constructPath(int endIdx)
	{
		final List<GeoLocation> reversed = new ArrayList<>();
		int prevDirX = Integer.MIN_VALUE;
		int prevDirY = Integer.MIN_VALUE;
		int curr = endIdx;
		
		while (_parent[curr] != -1)
		{
			final int parent = _parent[curr];
			final int grand = _parent[parent];
			final int currX = idxToX(curr);
			final int currY = idxToY(curr);
			final int parentX = idxToX(parent);
			final int parentY = idxToY(parent);
			
			int dirX;
			int dirY;
			if (!_diagonalStrategy && (grand != -1))
			{
				final int tmpX = currX - idxToX(grand);
				final int tmpY = currY - idxToY(grand);
				if (Math.abs(tmpX) == Math.abs(tmpY))
				{
					dirX = tmpX;
					dirY = tmpY;
				}
				else
				{
					dirX = currX - parentX;
					dirY = currY - parentY;
				}
			}
			else
			{
				dirX = currX - parentX;
				dirY = currY - parentY;
			}
			
			if ((dirX != prevDirX) || (dirY != prevDirY))
			{
				prevDirX = dirX;
				prevDirY = dirY;
				reversed.add(new GeoLocation(currX, currY, _z[curr]));
			}
			
			curr = parent;
		}
		
		Collections.reverse(reversed);
		return reversed;
	}
}

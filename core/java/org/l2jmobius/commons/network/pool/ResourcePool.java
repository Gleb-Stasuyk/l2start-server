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
package org.l2jmobius.commons.network.pool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.l2jmobius.commons.network.ConnectionConfig;

/**
 * Manages multiple {@link BufferPool}s indexed by buffer size for efficient {@link ByteBuffer} reuse.<br>
 * <br>
 * Two concurrent data structures are maintained in parallel:
 * <ul>
 * <li>A {@link ConcurrentSkipListMap} keyed by size for {@code ceiling} lookups (finding the smallest pool whose buffer capacity is &ge; the requested size).</li>
 * <li>A {@link ConcurrentHashMap} keyed by exact capacity for O(1) recycle path (returning a buffer whose capacity exactly matches a known pool).</li>
 * </ul>
 * Both structures are written together in {@link #addBufferPool}, so they are always consistent.
 * @author JoeAlisson, Mobius
 */
public class ResourcePool
{
	/** Ordered map for ceiling-entry lookups (find smallest pool >= requested size). */
	private final ConcurrentSkipListMap<Integer, BufferPool> _sizedPools = new ConcurrentSkipListMap<>();
	
	/** Fast O(1) exact-capacity lookup used by the recycle path. */
	private final ConcurrentHashMap<Integer, BufferPool> _exactPools = new ConcurrentHashMap<>();
	
	private volatile boolean _autoExpandCapacity = true;
	private volatile boolean _initBufferPools = false;
	private volatile float _initBufferPoolFactor = 0;
	private volatile int _bufferSegmentSize = 64;
	
	public ResourcePool()
	{
	}
	
	/**
	 * Returns a {@link ByteBuffer} sized for the packet header (2 bytes).
	 * @return a header-sized buffer
	 */
	public ByteBuffer getHeaderBuffer()
	{
		return getSizedBuffer(ConnectionConfig.HEADER_SIZE);
	}
	
	/**
	 * Returns a {@link ByteBuffer} with at least {@code size} bytes of capacity.
	 * @param size the minimum required capacity
	 * @return a buffer with capacity &ge; {@code size}
	 */
	public ByteBuffer getBuffer(int size)
	{
		return getSizedBuffer(determineBufferSize(size));
	}
	
	/**
	 * Recycles {@code buffer} (if non-null) and returns a new buffer of {@code newSize}.<br>
	 * If {@code buffer} already has the right capacity, it is cleared and reused directly.
	 * @param buffer the buffer to recycle, or {@code null}
	 * @param newSize the required capacity of the returned buffer
	 * @return a buffer with capacity exactly matching the pool size for {@code newSize}
	 */
	public ByteBuffer recycleAndGetNew(ByteBuffer buffer, int newSize)
	{
		final int poolSize = determineBufferSize(newSize);
		if (buffer != null)
		{
			if (buffer.clear().limit() == poolSize)
			{
				return buffer.limit(newSize);
			}
			
			recycleBuffer(buffer);
		}
		
		return getSizedBuffer(poolSize).limit(newSize);
	}
	
	/**
	 * Returns a buffer of exactly {@code size} bytes from the appropriate pool, expanding or creating pools as needed.
	 * @param size the exact capacity to serve (must be a pool key)
	 * @return a direct {@link ByteBuffer} with the requested capacity
	 */
	private ByteBuffer getSizedBuffer(int size)
	{
		final Entry<Integer, BufferPool> entry = _sizedPools.ceilingEntry(size);
		if (entry != null)
		{
			final BufferPool pool = entry.getValue();
			if (_autoExpandCapacity)
			{
				if (_initBufferPools)
				{
					if (pool.isEmpty())
					{
						pool.expandCapacity(_initBufferPoolFactor, pool.getMaxSize());
					}
				}
				else if (pool.isFull())
				{
					pool.expandCapacity(_initBufferPoolFactor, pool.getMaxSize());
				}
			}
			
			final ByteBuffer buffer = pool.get();
			if (buffer != null)
			{
				return buffer;
			}
		}
		
		// No pool or pool was empty and no expansion produced a buffer - allocate directly.
		if (entry == null)
		{
			// Unknown size: register a new pool so future requests are served from the pool.
			final BufferPool pool = new BufferPool(10, size);
			if (_initBufferPools)
			{
				pool.initialize(_initBufferPoolFactor);
			}
			
			_sizedPools.putIfAbsent(size, pool);
			_exactPools.putIfAbsent(size, _sizedPools.get(size));
		}
		
		return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
	}
	
	/**
	 * Returns the pool key (capacity) to use for the given {@code size}.
	 * @param size the minimum required capacity
	 * @return the capacity of the smallest pool that can satisfy {@code size}
	 */
	private int determineBufferSize(int size)
	{
		final Entry<Integer, BufferPool> entry = _sizedPools.ceilingEntry(size);
		if (entry != null)
		{
			return entry.getKey();
		}
		
		// Unknown size - register a new pool.
		final BufferPool pool = new BufferPool(10, size);
		if (_initBufferPools)
		{
			pool.initialize(_initBufferPoolFactor);
		}
		
		_sizedPools.putIfAbsent(size, pool);
		_exactPools.putIfAbsent(size, _sizedPools.get(size));
		return size;
	}
	
	/**
	 * Returns {@code buffer} to its pool using an O(1) exact-capacity lookup.<br>
	 * Silently ignores {@code null} or buffers whose capacity has no matching pool.
	 * @param buffer the buffer to recycle
	 */
	public void recycleBuffer(ByteBuffer buffer)
	{
		if (buffer == null)
		{
			return;
		}
		
		final BufferPool pool = _exactPools.get(buffer.capacity());
		if (pool != null)
		{
			pool.recycle(buffer);
		}
	}
	
	/**
	 * Registers a {@link BufferPool} for the given buffer size.<br>
	 * If a pool for that size already exists it is not replaced.
	 * @param bufferSize the capacity (in bytes) of buffers managed by the pool
	 * @param bufferPool the pool to register
	 */
	public void addBufferPool(int bufferSize, BufferPool bufferPool)
	{
		_sizedPools.putIfAbsent(bufferSize, bufferPool);
		_exactPools.putIfAbsent(bufferSize, bufferPool);
	}
	
	/**
	 * Returns the number of registered buffer pools.
	 * @return pool count
	 */
	public int bufferPoolSize()
	{
		return _sizedPools.size();
	}
	
	/**
	 * Finalises pool configuration and optionally pre-allocates buffers.
	 * @param autoExpandCapacity whether pools should grow automatically when exhausted
	 * @param initBufferPoolFactor fraction of each pool to pre-allocate (0 = none)
	 */
	public void initializeBuffers(boolean autoExpandCapacity, float initBufferPoolFactor)
	{
		_autoExpandCapacity = autoExpandCapacity;
		_initBufferPoolFactor = initBufferPoolFactor;
		_initBufferPools = initBufferPoolFactor > 0;
		if (_initBufferPools)
		{
			_sizedPools.values().forEach(pool -> pool.initialize(initBufferPoolFactor));
		}
	}
	
	/**
	 * Returns the configured buffer segment size (default 64).
	 * @return segment size in bytes
	 */
	public int getSegmentSize()
	{
		return _bufferSegmentSize;
	}
	
	/**
	 * Sets the buffer segment size used as the default initial size for dynamic packet buffers.
	 * @param size the new segment size in bytes
	 */
	public void setBufferSegmentSize(int size)
	{
		_bufferSegmentSize = size;
	}
	
	/**
	 * Returns a diagnostic string listing all registered pools.
	 * @return multi-line pool statistics
	 */
	public String stats()
	{
		final StringBuilder sb = new StringBuilder();
		for (BufferPool pool : _sizedPools.values())
		{
			sb.append(pool.toString()).append(System.lineSeparator());
		}
		
		return sb.toString();
	}
}

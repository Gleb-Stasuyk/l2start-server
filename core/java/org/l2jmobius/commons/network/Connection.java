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
package org.l2jmobius.commons.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.l2jmobius.commons.network.handler.ReadHandler;
import org.l2jmobius.commons.network.handler.WriteHandler;
import org.l2jmobius.commons.network.pool.ResourcePool;

/**
 * Manages a single network connection backed by an {@link AsynchronousSocketChannel}.<br>
 * <br>
 * Owns the lifecycle of both the read buffer (single {@link ByteBuffer} obtained from the {@link ResourcePool}) and the write buffer array (supplied per-packet by the {@link Client}).<br>
 * All asynchronous I/O is dispatched from here; completion logic lives in {@link ReadHandler} and {@link WriteHandler}.<br>
 * <br>
 * Thread-safety: the {@code _client} reference is declared {@code volatile} because it is read by NIO completion-handler threads.<br>
 * An {@link AtomicBoolean} guards {@link #close()} against double-close races that can occur when a read failure and a write failure fire concurrently.
 * @param <T> the client type associated with this connection
 * @author JoeAlisson, Mobius
 */
public class Connection<T extends Client<Connection<T>>>
{
	private final AsynchronousSocketChannel _channel;
	private final ReadHandler<T> _readHandler;
	private final WriteHandler<T> _writeHandler;
	private final ConnectionConfig _config;
	
	/** Volatile because NIO completion-handler threads read this field. */
	private volatile T _client;
	
	/** Ensures {@link #close()} body executes at most once. */
	private final AtomicBoolean _closed = new AtomicBoolean();
	
	private ByteBuffer _readingBuffer;
	private ByteBuffer[] _writingBuffers;
	
	/**
	 * Creates a new connection around an already-accepted channel.
	 * @param channel the open async socket channel
	 * @param readHandler completion handler for read operations
	 * @param writeHandler completion handler for write operations
	 * @param config shared configuration (resource pool, thresholds, etc.)
	 */
	public Connection(AsynchronousSocketChannel channel, ReadHandler<T> readHandler, WriteHandler<T> writeHandler, ConnectionConfig config)
	{
		_channel = channel;
		_readHandler = readHandler;
		_writeHandler = writeHandler;
		_config = config;
	}
	
	/**
	 * Binds a client to this connection. Must be called exactly once, immediately after construction.
	 * @param client the owning client
	 */
	public void setClient(T client)
	{
		_client = client;
	}
	
	// ------------------------------------------------------------------
	// Read operations.
	// ------------------------------------------------------------------
	
	/**
	 * Submits an async read into the current reading buffer.<br>
	 * No-op if the channel has already been closed.
	 */
	public void read()
	{
		if (_channel.isOpen())
		{
			_channel.read(_readingBuffer, _client, _readHandler);
		}
	}
	
	/**
	 * Prepares for the next packet by recycling the old reading buffer, obtaining a fresh header-sized buffer from the pool and starting a read.
	 */
	public void readHeader()
	{
		if (_channel.isOpen())
		{
			recycleReadBuffer();
			_readingBuffer = _config.resourcePool.getHeaderBuffer();
			read();
		}
	}
	
	/**
	 * Swaps the reading buffer for one that can hold {@code size} bytes and starts a read.
	 * @param size required capacity in bytes
	 */
	public void read(int size)
	{
		if (_channel.isOpen())
		{
			_readingBuffer = _config.resourcePool.recycleAndGetNew(_readingBuffer, size);
			read();
		}
	}
	
	/**
	 * Returns the buffer that is currently receiving inbound data.
	 * @return the reading buffer, or {@code null} between packets
	 */
	public ByteBuffer getReadingBuffer()
	{
		return _readingBuffer;
	}
	
	// ------------------------------------------------------------------
	// Write operations.
	// ------------------------------------------------------------------
	
	/**
	 * Begins a gather-write of the supplied buffers to the channel.
	 * @param buffers one or more buffers containing the outgoing packet data
	 * @return {@code true} if the write was submitted; {@code false} if the channel is closed
	 */
	public boolean write(ByteBuffer[] buffers)
	{
		if (!_channel.isOpen())
		{
			return false;
		}
		
		_writingBuffers = buffers;
		write();
		return true;
	}
	
	/**
	 * Continues (or completes) a previously started gather-write.<br>
	 * If the channel is closed or no buffers remain, signals the client via {@link Client#finishWriting()} so it can dequeue the next packet.
	 */
	public void write()
	{
		if (_channel.isOpen() && (_writingBuffers != null))
		{
			_channel.write(_writingBuffers, 0, _writingBuffers.length, -1, TimeUnit.MILLISECONDS, _client, _writeHandler);
		}
		else if (_client != null)
		{
			_client.finishWriting();
		}
	}
	
	/**
	 * Returns every write buffer to the resource pool.
	 * @return {@code true} if at least one buffer was recycled
	 */
	public boolean releaseWritingBuffer()
	{
		final ByteBuffer[] buffers = _writingBuffers;
		if (buffers == null)
		{
			return false;
		}
		
		_writingBuffers = null;
		for (ByteBuffer buf : buffers)
		{
			_config.resourcePool.recycleBuffer(buf);
		}
		
		return true;
	}
	
	// ------------------------------------------------------------------
	// Lifecycle.
	// ------------------------------------------------------------------
	
	/**
	 * Closes the connection, releasing all pooled buffers and shutting down the channel.<br>
	 * Safe to call from multiple threads - only the first invocation performs cleanup.
	 */
	public void close()
	{
		if (!_closed.compareAndSet(false, true))
		{
			return;
		}
		
		recycleReadBuffer();
		releaseWritingBuffer();
		
		try
		{
			if (_channel.isOpen())
			{
				_channel.close();
			}
		}
		catch (IOException ignored)
		{
			// Channel was already broken - nothing useful to do.
		}
		finally
		{
			_client = null;
		}
	}
	
	/**
	 * Returns {@code true} if the underlying channel is still open.
	 * @return channel open state
	 */
	public boolean isOpen()
	{
		return _channel.isOpen();
	}
	
	// ------------------------------------------------------------------
	// Accessors.
	// ------------------------------------------------------------------
	
	/**
	 * Resolves the remote peer's IP address.
	 * @return dotted-quad (or IPv6) address string, or {@code ""} if the channel is closed
	 */
	public String getRemoteAddress()
	{
		try
		{
			final InetSocketAddress remote = (InetSocketAddress) _channel.getRemoteAddress();
			return remote.getAddress().getHostAddress();
		}
		catch (IOException e)
		{
			return "";
		}
	}
	
	/**
	 * Returns the {@link ResourcePool} shared by all connections on this acceptor.
	 * @return the resource pool
	 */
	public ResourcePool getResourcePool()
	{
		return _config.resourcePool;
	}
	
	/**
	 * Indicates whether the server is configured to silently drop expendable packets when a client's outbound queue grows too large.
	 * @return {@code true} if packet dropping is enabled
	 */
	public boolean dropPackets()
	{
		return _config.dropPackets;
	}
	
	/**
	 * Returns the queue depth at which expendable packets begin to be dropped.
	 * @return the drop threshold
	 */
	public int dropPacketThreshold()
	{
		return _config.dropPacketThreshold;
	}
	
	// ------------------------------------------------------------------
	// Internal helpers.
	// ------------------------------------------------------------------
	
	/**
	 * Returns the current reading buffer to the pool and nulls the reference.
	 */
	private void recycleReadBuffer()
	{
		final ByteBuffer buf = _readingBuffer;
		if (buf != null)
		{
			_readingBuffer = null;
			_config.resourcePool.recycleBuffer(buf);
		}
	}
}

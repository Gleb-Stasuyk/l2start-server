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
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.l2jmobius.commons.network.handler.ReadHandler;
import org.l2jmobius.commons.network.handler.WriteHandler;
import org.l2jmobius.commons.network.packet.PacketExecutor;
import org.l2jmobius.commons.network.packet.PacketHandler;
import org.l2jmobius.commons.network.packet.PacketThreadFactory;
import org.l2jmobius.commons.network.packet.ReadablePacket;

/**
 * Binds a server socket, accepts incoming TCP connections and initialises a {@link Client} for each one via a caller-supplied factory.<br>
 * <br>
 * The underlying {@link AsynchronousChannelGroup} uses a bounded thread pool sized by {@link ConnectionConfig#threadPoolSize}. A {@link LinkedBlockingQueue} work queue allows the pool to buffer short bursts of connection-accept events without spawning unbounded threads.
 * @param <T> the concrete client type created for each accepted connection
 * @author Mobius
 */
public class ConnectionManager<T extends Client<Connection<T>>>
{
	private final AsynchronousChannelGroup _group;
	private final AsynchronousServerSocketChannel _socketChannel;
	private final ConnectionConfig _config;
	private final WriteHandler<T> _writeHandler;
	private final ReadHandler<T> _readHandler;
	private final Function<Connection<T>, T> _clientFactory;
	
	/**
	 * Binds the server to {@code address} and starts accepting connections.
	 * @param address the local address and port to listen on
	 * @param clientFactory function that creates a {@link Client} for a given {@link Connection}
	 * @param packetHandler handler that maps incoming bytes to concrete {@link ReadablePacket}s
	 * @throws IOException if the server socket cannot be opened or bound
	 */
	public ConnectionManager(InetSocketAddress address, Function<Connection<T>, T> clientFactory, PacketHandler<T> packetHandler) throws IOException
	{
		_config = new ConnectionConfig(address);
		_clientFactory = clientFactory;
		_readHandler = new ReadHandler<>(packetHandler, new PacketExecutor<>(_config));
		_writeHandler = new WriteHandler<>();
		
		// Bounded thread pool: core == max == threadPoolSize; idle threads kept alive for 1 minute.
		// LinkedBlockingQueue buffers short accept bursts without spawning extra threads.
		final ThreadPoolExecutor threadPool = new ThreadPoolExecutor(_config.threadPoolSize, _config.threadPoolSize, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), new PacketThreadFactory("Server", _config.threadPriority));
		threadPool.allowCoreThreadTimeOut(true);
		
		_group = AsynchronousChannelGroup.withThreadPool(threadPool);
		_socketChannel = _group.provider().openAsynchronousServerSocketChannel(_group);
		_socketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		_socketChannel.bind(_config.address);
		
		// Begin the accept loop.
		_socketChannel.accept(null, new AcceptConnectionHandler());
	}
	
	/**
	 * Stops accepting new connections and shuts down the channel group.<br>
	 * Waits up to {@link ConnectionConfig#shutdownWaitTime} ms for in-flight I/O to complete.
	 */
	public void shutdown()
	{
		try
		{
			_socketChannel.close();
			_group.shutdown();
			_group.awaitTermination(_config.shutdownWaitTime, TimeUnit.MILLISECONDS);
			_group.shutdownNow();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception e)
		{
			// Best-effort shutdown; errors here are not actionable.
		}
	}
	
	private class AcceptConnectionHandler implements CompletionHandler<AsynchronousSocketChannel, Void>
	{
		@Override
		public void completed(AsynchronousSocketChannel clientChannel, Void attachment)
		{
			// Re-arm the accept loop before processing the new channel so that further connections are not delayed by client initialisation.
			if (_socketChannel.isOpen())
			{
				_socketChannel.accept(null, this);
			}
			
			processNewConnection(clientChannel);
		}
		
		@Override
		public void failed(Throwable t, Void attachment)
		{
			// Re-arm even on failure so transient errors do not kill the accept loop.
			if (_socketChannel.isOpen())
			{
				_socketChannel.accept(null, this);
			}
		}
		
		private void processNewConnection(AsynchronousSocketChannel channel)
		{
			if ((channel == null) || !channel.isOpen())
			{
				return;
			}
			
			try
			{
				channel.setOption(StandardSocketOptions.TCP_NODELAY, !_config.useNagle);
				
				final Connection<T> connection = new Connection<>(channel, _readHandler, _writeHandler, _config);
				final T client = _clientFactory.apply(connection);
				connection.setClient(client);
				
				client.onConnected();
				client.read();
			}
			catch (ClosedChannelException e)
			{
				// Channel was closed between accept and setup - nothing to do.
			}
			catch (Exception e)
			{
				try
				{
					channel.close();
				}
				catch (IOException ioe)
				{
					// Ignore; channel is already unusable.
				}
			}
		}
	}
}

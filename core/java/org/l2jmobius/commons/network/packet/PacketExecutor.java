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
package org.l2jmobius.commons.network.packet;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.network.Client;
import org.l2jmobius.commons.network.Connection;
import org.l2jmobius.commons.network.ConnectionConfig;

/**
 * Thread-pool-based executor for incoming network packets.<br>
 * Received packets are submitted here after successful parsing so that their {@link ReadablePacket#run()} logic executes off the I/O thread.
 * @param <T> the client type associated with the packets being executed
 * @author Mobius
 */
public class PacketExecutor<T extends Client<Connection<T>>>
{
	private static final Logger LOGGER = Logger.getLogger(PacketExecutor.class.getName());
	
	private final ThreadPoolExecutor _executor;
	
	/**
	 * Creates an executor sized according to {@link ConnectionConfig#threadPoolSize}.
	 * @param config the connection configuration supplying pool size and thread priority
	 */
	public PacketExecutor(ConnectionConfig config)
	{
		_executor = new ThreadPoolExecutor(config.threadPoolSize, Integer.MAX_VALUE, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), new PacketThreadFactory("PacketExecutor", config.threadPriority));
	}
	
	/**
	 * Submits a packet for execution on a worker thread.
	 * @param packet the packet to execute
	 */
	public void execute(ReadablePacket<T> packet)
	{
		try
		{
			_executor.execute(new PacketRunnable<>(packet));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "Failed to submit " + packet.getClass().getSimpleName(), e);
		}
	}
	
	private static class PacketRunnable<T extends Client<Connection<T>>> implements Runnable
	{
		private final ReadablePacket<T> _packet;
		
		public PacketRunnable(ReadablePacket<T> packet)
		{
			_packet = packet;
		}
		
		@Override
		public void run()
		{
			try
			{
				_packet.run();
			}
			catch (Throwable t)
			{
				final Thread currentThread = Thread.currentThread();
				final UncaughtExceptionHandler handler = currentThread.getUncaughtExceptionHandler();
				if (handler != null)
				{
					handler.uncaughtException(currentThread, t);
				}
				else
				{
					LOGGER.log(Level.SEVERE, "Uncaught exception in " + _packet.getClass().getSimpleName(), t);
				}
			}
		}
	}
}

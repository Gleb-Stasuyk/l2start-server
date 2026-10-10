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
package org.l2jmobius.commons.network.handler;

import java.nio.channels.CompletionHandler;

import org.l2jmobius.commons.network.Client;
import org.l2jmobius.commons.network.Connection;

/**
 * {@link CompletionHandler} for async scatter-write operations.<br>
 * Invoked by the NIO framework when a {@link Connection#write()} completes (fully or partially).<br>
 * <ul>
 * <li>Negative result -> peer disconnected; disconnect the client.</li>
 * <li>Partial write -> call {@link Client#resumeSend} so the remaining bytes are retried.</li>
 * <li>Full write -> call {@link Client#finishWriting} to release buffers and send the next queued packet.</li>
 * </ul>
 * @param <T> the client type associated with this handler
 * @author JoeAlisson, Mobius
 */
public class WriteHandler<T extends Client<Connection<T>>> implements CompletionHandler<Long, T>
{
	@Override
	public void completed(Long result, T client)
	{
		if (client == null)
		{
			return;
		}
		
		final int bytesWritten = result.intValue();
		if (bytesWritten < 0)
		{
			if (client.isConnected())
			{
				client.disconnect();
			}
			return;
		}
		
		if ((bytesWritten > 0) && (bytesWritten < client.getDataSentSize()))
		{
			// Partial write - continue sending the remaining data.
			client.resumeSend(bytesWritten);
		}
		else
		{
			// All bytes sent - release buffers and process the next queued packet.
			client.finishWriting();
		}
	}
	
	@Override
	public void failed(Throwable e, T client)
	{
		if (client != null)
		{
			client.disconnect();
		}
	}
}

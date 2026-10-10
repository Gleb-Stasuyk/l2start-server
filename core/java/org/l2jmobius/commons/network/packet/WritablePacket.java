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

import org.l2jmobius.commons.network.Client;
import org.l2jmobius.commons.network.Connection;
import org.l2jmobius.commons.network.ConnectionConfig;
import org.l2jmobius.commons.network.buffer.WriteBuffer;

/**
 * Base class for all packets sent to clients.<br>
 * All packets carry a 2-byte little-endian header that encodes the total packet length.<br>
 * <br>
 * <b>Broadcast optimisation:</b> when a packet will be sent to multiple clients, call {@link #sendInBroadcast()} before the first {@link Client#writePacket} call.<br>
 * The packet data is then written once into a {@link WriteBuffer}, a heap {@code byte[]} snapshot is cached on this packet instance, and every subsequent recipient gets a fresh {@link WriteBuffer} seeded from that snapshot. Each client still encrypts its own copy independently.
 * @param <T> the client type associated with this packet
 * @author Mobius
 */
public abstract class WritablePacket<T extends Client<Connection<T>>>
{
	private volatile boolean _broadcast;
	
	/**
	 * Cached snapshot of the serialized packet bytes for broadcast reuse.<br>
	 * Populated on the first send when {@link #_broadcast} is {@code true}; every later send bulk-copies these bytes into a fresh per-client {@link WriteBuffer}. Guarded by {@code this} (synchronized on the WritablePacket instance).
	 */
	private byte[] _broadcastCacheBytes;
	private int _broadcastCacheLength;
	
	protected WritablePacket()
	{
	}
	
	/**
	 * Produces the buffer containing this packet's encoded data for the given client.<br>
	 * For broadcast packets the first call populates a shared byte-array cache; subsequent calls seed a fresh per-client {@link WriteBuffer} from that cache so each client can encrypt independently.
	 * @param client the recipient client
	 * @return a {@link WriteBuffer} whose logical limit is the packet length
	 * @throws Exception if the {@link #write} implementation signals a failure
	 */
	public WriteBuffer writeData(T client) throws Exception
	{
		if (_broadcast)
		{
			return writeDataWithCache(client);
		}
		
		return writeDataToBuffer(client);
	}
	
	/**
	 * Broadcast path: returns a fresh per-client buffer seeded from the shared cache, building the cache on the first call.
	 * @param client the recipient client
	 * @return per-client buffer populated with the cached bytes
	 * @throws Exception if the underlying write fails on first cache population
	 */
	private synchronized WriteBuffer writeDataWithCache(T client) throws Exception
	{
		if (_broadcastCacheBytes != null)
		{
			return new WriteBuffer(_broadcastCacheBytes, _broadcastCacheLength, client.getResourcePool(), getClass());
		}
		
		final WriteBuffer buffer = writeDataToBuffer(client);
		_broadcastCacheBytes = buffer.toByteArray();
		_broadcastCacheLength = buffer.limit();
		return buffer;
	}
	
	/**
	 * Writes packet data into a fresh {@link WriteBuffer} sized by the per-class historical maximum.
	 * @param client the recipient client
	 * @return buffer containing the written packet data, positioned at 0 with limit at packet end
	 * @throws Exception if {@link #write} returns {@code false}
	 */
	private WriteBuffer writeDataToBuffer(T client) throws Exception
	{
		final WriteBuffer buffer = new WriteBuffer(client.getResourcePool(), getClass());
		buffer.position(ConnectionConfig.HEADER_SIZE);
		if (write(client, buffer))
		{
			buffer.mark();
			return buffer;
		}
		
		buffer.releaseResources();
		throw new Exception("WritablePacket.write() returned false for " + getClass().getSimpleName());
	}
	
	/**
	 * Writes the 2-byte packet-length header at offset 0 of {@code buffer}.
	 * @param buffer the buffer whose first 2 bytes receive the total length
	 * @param header the total packet length (including the 2-byte header itself)
	 */
	public void writeHeader(WriteBuffer buffer, int header)
	{
		buffer.writeShort(0, (short) header);
	}
	
	/**
	 * Marks this packet as a broadcast packet.<br>
	 * Must be called <em>before</em> the first {@link Client#writePacket} invocation.<br>
	 * After this call, packet data is written once and the resulting bytes are cached on this instance for reuse across all recipients.
	 */
	public void sendInBroadcast()
	{
		_broadcast = true;
	}
	
	/**
	 * Returns whether this packet may be silently discarded when the client's send queue exceeds the configured drop threshold.<br>
	 * The default implementation always returns {@code false} (never drop).
	 * @param client the recipient client
	 * @return {@code true} if the packet is expendable and may be dropped under pressure
	 */
	public boolean canBeDropped(T client)
	{
		return false;
	}
	
	/**
	 * Writes the packet's payload into {@code buffer}.<br>
	 * The buffer's position is already set to {@link ConnectionConfig#HEADER_SIZE} on entry; the implementation should write all payload bytes and return {@code true} on success.
	 * @param client the recipient client (available for per-client customisation)
	 * @param buffer the buffer to write payload data into
	 * @return {@code true} if the packet was written successfully; {@code false} to cancel sending
	 */
	protected abstract boolean write(T client, WriteBuffer buffer);
	
	@Override
	public String toString()
	{
		return getClass().getSimpleName();
	}
}

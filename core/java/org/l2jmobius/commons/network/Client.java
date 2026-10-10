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

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.commons.network.buffer.ReadBuffer;
import org.l2jmobius.commons.network.buffer.WriteBuffer;
import org.l2jmobius.commons.network.handler.ReadHandler;
import org.l2jmobius.commons.network.handler.WriteHandler;
import org.l2jmobius.commons.network.packet.WritablePacket;
import org.l2jmobius.commons.network.pool.ResourcePool;

/**
 * Abstract client entity that owns a {@link Connection} and manages packet I/O.<br>
 * <br>
 * <b>Fair-send mechanism:</b> a single global {@link ConcurrentLinkedQueue} ({@code PENDING_CLIENTS}) is used across all active clients. Whenever a client wants to send it atomically acquires the {@code _writing} flag, then adds itself to the pending queue.<br>
 * The client polled from the front of the queue sends its next packet. This round-robin approach ensures no single client can monopolise the I/O threads when many clients are active simultaneously.<br>
 * <br>
 * <b>Packet dropping:</b> when {@link ConnectionConfig#dropPackets} is enabled, packets that return {@code true} from {@link WritablePacket#canBeDropped} are silently discarded once the estimated outbound queue exceeds {@link ConnectionConfig#dropPacketThreshold}.
 * @param <T> the concrete {@link Connection} subtype bound to this client
 * @author JoeAlisson, Mobius
 */
public abstract class Client<T extends Connection<?>>
{
	/**
	 * Global fair-send queue shared across all client instances.<br>
	 * Unbounded; clients add themselves when they start sending and are polled by the completing write to find the next sender.
	 */
	private static final ConcurrentLinkedQueue<Client<?>> PENDING_CLIENTS = new ConcurrentLinkedQueue<>();
	
	private final T _connection;
	private final Queue<WritablePacket<? extends Client<T>>> _packetsToWrite = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean _writing = new AtomicBoolean();
	private final AtomicBoolean _disconnecting = new AtomicBoolean();
	private final AtomicBoolean _closing = new AtomicBoolean();
	private final AtomicInteger _estimateQueueSize = new AtomicInteger();
	private final AtomicInteger _dataSentSize = new AtomicInteger();
	
	// Read-side state (accessed only on the single async-read thread for this client).
	private boolean _readingPayload;
	private int _expectedReadSize;
	
	/**
	 * Constructs a client bound to the given connection.
	 * @param connection the open connection; must not be {@code null} or closed
	 * @throws IllegalArgumentException if {@code connection} is {@code null} or already closed
	 */
	protected Client(T connection)
	{
		if ((connection == null) || !connection.isOpen())
		{
			throw new IllegalArgumentException("The connection is null or closed.");
		}
		
		_connection = connection;
	}
	
	/**
	 * Queues {@code packet} for transmission and triggers the fair-send loop if needed.
	 * @param packet the packet to send; ignored if {@code null}
	 */
	protected void writePacket(WritablePacket<? extends Client<T>> packet)
	{
		if (!isConnected() || (packet == null) || packetCanBeDropped(packet))
		{
			return;
		}
		
		_estimateQueueSize.incrementAndGet();
		_packetsToWrite.add(packet);
		writeFairPacket();
	}
	
	/**
	 * Queues multiple packets and triggers the fair-send loop if needed.
	 * @param packets the collection of packets to send; ignored if {@code null} or empty
	 */
	protected void writePackets(Collection<WritablePacket<? extends Client<T>>> packets)
	{
		if (!isConnected() || (packets == null) || packets.isEmpty())
		{
			return;
		}
		
		_estimateQueueSize.addAndGet(packets.size());
		_packetsToWrite.addAll(packets);
		writeFairPacket();
	}
	
	/**
	 * Determines if a packet can be dropped based on the connection's drop packet settings.
	 * @param packet The packet to check.
	 * @return True if the packet can be dropped, false otherwise.
	 */
	@SuppressWarnings(
	{
		"unchecked",
		"rawtypes"
	})
	private boolean packetCanBeDropped(WritablePacket packet)
	{
		return _connection.dropPackets() && (_estimateQueueSize.get() > _connection.dropPacketThreshold()) && packet.canBeDropped(this);
	}
	
	/**
	 * Enters the send loop for this client if it is not already active.
	 */
	private void writeFairPacket()
	{
		if (_writing.compareAndSet(false, true))
		{
			sendFairPacket();
		}
	}
	
	/**
	 * Adds this client to the global pending queue, then polls one client to send its next packet.<br>
	 * The polled client may be {@code this} or any other client waiting to send.
	 */
	private void sendFairPacket()
	{
		PENDING_CLIENTS.offer(this);
		final Client<?> nextClient = PENDING_CLIENTS.poll();
		if (nextClient != null)
		{
			nextClient.writeNextPacket();
		}
	}
	
	/**
	 * Sends the next queued packet, or releases the write lock if the queue is empty.
	 */
	private void writeNextPacket()
	{
		final WritablePacket<? extends Client<T>> packet = _packetsToWrite.poll();
		if (packet == null)
		{
			releaseWritingResource();
			
			// A packet may have been queued by another thread between the empty poll above and the release of the writing flag; that thread's CAS failed, so this thread must restart the send loop.
			if (!_packetsToWrite.isEmpty())
			{
				writeFairPacket();
				return;
			}
			
			if (_closing.get())
			{
				// A close(packet) may have queued its final packet concurrently, or another thread may be writing it right now; that thread's completion path will come back here and disconnect.
				if (!_packetsToWrite.isEmpty() || _writing.get())
				{
					return;
				}
				
				disconnect();
			}
		}
		else
		{
			_estimateQueueSize.decrementAndGet();
			write(packet);
		}
	}
	
	/**
	 * Writes a specified packet to the connection. Encrypts the data, writes headers and manages the buffer.<br>
	 * If the packet cannot be written, it handles resource release and retries.
	 * @param packet The packet to be written.
	 */
	@SuppressWarnings(
	{
		"unchecked",
		"rawtypes"
	})
	private void write(WritablePacket packet)
	{
		boolean written = false;
		WriteBuffer buffer = null;
		try
		{
			buffer = packet.writeData(this);
			
			final int payloadSize = buffer.limit() - ConnectionConfig.HEADER_SIZE;
			if (payloadSize <= 0)
			{
				return;
			}
			
			if (encrypt(buffer, ConnectionConfig.HEADER_SIZE, payloadSize))
			{
				final int bufferLimit = buffer.limit();
				_dataSentSize.set(bufferLimit);
				if (bufferLimit <= ConnectionConfig.HEADER_SIZE)
				{
					return;
				}
				
				packet.writeHeader(buffer, bufferLimit);
				written = _connection.write(buffer.toByteBuffers());
			}
		}
		catch (Exception e)
		{
			// Intentionally silent - a broken packet must not crash the I/O thread.
		}
		finally
		{
			if (!written)
			{
				handleNotWritten(buffer);
			}
		}
	}
	
	/**
	 * Handles scenarios where a packet could not be written successfully.<br>
	 * Releases any associated buffer resources and re-attempts the packet send if the client is still connected.
	 * @param buffer The buffer containing packet data, which may need resource release.
	 */
	private void handleNotWritten(WriteBuffer buffer)
	{
		if (!releaseWritingResource() && (buffer != null))
		{
			buffer.releaseResources();
		}
		
		if (isConnected())
		{
			writeFairPacket();
		}
	}
	
	/**
	 * Starts reading the next packet header from the connection.
	 */
	public void read()
	{
		_expectedReadSize = ConnectionConfig.HEADER_SIZE;
		_readingPayload = false;
		_connection.readHeader();
	}
	
	/**
	 * Transitions to payload-reading mode for the given data size.
	 * @param dataSize the number of payload bytes to read
	 */
	public void readPayload(int dataSize)
	{
		_expectedReadSize = dataSize;
		_readingPayload = true;
		_connection.read(dataSize);
	}
	
	/**
	 * Closes the connection immediately, discarding all pending outbound packets.
	 */
	public void close()
	{
		close(null);
	}
	
	/**
	 * Sends {@code packet} (if non-null) and then closes the connection.<br>
	 * All other queued packets are discarded.
	 * @param packet a final packet to transmit before closing, or {@code null}
	 */
	public void close(WritablePacket<? extends Client<T>> packet)
	{
		if (!isConnected())
		{
			return;
		}
		
		_packetsToWrite.clear();
		if (packet != null)
		{
			_packetsToWrite.add(packet);
		}
		
		_closing.set(true);
		
		writeFairPacket();
	}
	
	/**
	 * Called by {@link WriteHandler} after a partial write; adjusts the remaining-bytes counter and retries the write with the remainder.
	 * @param result the number of bytes that were successfully sent
	 */
	public void resumeSend(int result)
	{
		_dataSentSize.addAndGet(-result);
		_connection.write();
	}
	
	/**
	 * Called by {@link WriteHandler} after a complete write; releases buffers and sends the next packet.
	 */
	public void finishWriting()
	{
		_connection.releaseWritingBuffer();
		sendFairPacket();
	}
	
	private boolean releaseWritingResource()
	{
		final boolean released = _connection.releaseWritingBuffer();
		_writing.set(false);
		return released;
	}
	
	/**
	 * Disconnects the client: calls {@link #onDisconnection()}, clears all pending packets, and closes the underlying channel. Guaranteed to execute at most once.
	 */
	public void disconnect()
	{
		if (_disconnecting.compareAndSet(false, true))
		{
			try
			{
				onDisconnection();
			}
			finally
			{
				_packetsToWrite.clear();
				_connection.close();
			}
		}
	}
	
	/**
	 * Returns the connection bound to this client.
	 * @return the connection
	 */
	public T getConnection()
	{
		return _connection;
	}
	
	/**
	 * Returns the total size of the data being sent in the current write operation.
	 * @return bytes currently in flight
	 */
	public int getDataSentSize()
	{
		return _dataSentSize.get();
	}
	
	/**
	 * Returns the remote peer's IP address.
	 * @return IP string, or an empty string if unavailable
	 */
	public String getHostAddress()
	{
		return _connection == null ? "" : _connection.getRemoteAddress();
	}
	
	/**
	 * Returns {@code true} if the connection is open and a close has not been requested.
	 * @return {@code true} if connected
	 */
	public boolean isConnected()
	{
		return _connection.isOpen() && !_closing.get();
	}
	
	/**
	 * Returns the estimated number of packets waiting in the outbound queue.
	 * @return estimated queue depth
	 */
	public int getEstimateQueueSize()
	{
		return _estimateQueueSize.get();
	}
	
	/**
	 * Returns the {@link ResourcePool} used by the underlying connection.
	 * @return the resource pool
	 */
	public ResourcePool getResourcePool()
	{
		return _connection.getResourcePool();
	}
	
	/**
	 * Returns {@code true} when the client is in the middle of reading a packet payload (as opposed to reading a header).
	 * @return {@code true} if reading payload
	 */
	public boolean isReadingPayload()
	{
		return _readingPayload;
	}
	
	/**
	 * Called by {@link ReadHandler} after a partial read; subtracts the bytes already received and issues another read for the remainder.
	 * @param bytesRead the number of bytes received in the partial read
	 */
	public void resumeRead(int bytesRead)
	{
		_expectedReadSize -= bytesRead;
		_connection.read();
	}
	
	/**
	 * Returns the number of bytes still expected from the current read operation.
	 * @return remaining expected bytes
	 */
	public int getExpectedReadSize()
	{
		return _expectedReadSize;
	}
	
	/**
	 * Encrypts the packet data in-place within {@code data}.
	 * @param data the outbound buffer containing the data to encrypt
	 * @param offset byte offset at which the encryptable region starts
	 * @param size number of bytes to encrypt
	 * @return {@code true} if encryption succeeded; {@code false} to abort sending
	 */
	public abstract boolean encrypt(WriteBuffer data, int offset, int size);
	
	/**
	 * Decrypts the packet data in-place within {@code data}.
	 * @param data the inbound buffer containing the data to decrypt
	 * @param offset byte offset at which the decryptable region starts
	 * @param size number of bytes to decrypt
	 * @return {@code true} if decryption succeeded; {@code false} to abort processing
	 */
	public abstract boolean decrypt(ReadBuffer data, int offset, int size);
	
	/**
	 * Called once when the client disconnects.<br>
	 * Implementations must persist state and release all application-level resources.<br>
	 * No further packets can be sent after this method returns.
	 */
	protected abstract void onDisconnection();
	
	/**
	 * Called once immediately after the connection is accepted.<br>
	 * Implementations should not block; outbound packets may be sent from this method onward.
	 */
	public abstract void onConnected();
}

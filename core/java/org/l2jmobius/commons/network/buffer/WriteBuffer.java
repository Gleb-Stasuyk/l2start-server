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
package org.l2jmobius.commons.network.buffer;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.network.pool.ResourcePool;

/**
 * Outbound network buffer backed by a single pooled direct {@link ByteBuffer} from a {@link ResourcePool}.<br>
 * <br>
 * Provides sequential cursor-based writes (advancing an internal position) plus absolute indexed writes (used for header and encryption patches).<br>
 * <br>
 * All multi-byte values are stored in <b>little-endian</b> order, matching the protocol convention.<br>
 * <br>
 * <b>Growth:</b> when a write would exceed the current capacity, a larger buffer is acquired from the pool, the existing bytes are copied into it, and the old buffer is returned to the pool.<br>
 * <br>
 * <b>Per-class size hints:</b> the maximum observed {@code limit()} for each packet class is tracked so that the initial allocation for subsequent sends is right-sized on the first try, avoiding realloc cycles.<br>
 * <br>
 * <b>Broadcast cache:</b> {@link #toByteArray()} snapshots the written content for reuse; {@link #WriteBuffer(byte[], int, ResourcePool, Class)} creates a per-recipient copy seeded from that snapshot.
 * @author Mobius
 */
public class WriteBuffer
{
	/** Per-packet-class largest observed packet size, used to right-size the initial pooled buffer. */
	private static final Map<Class<?>, Integer> MAXIMUM_PACKET_SIZE = new ConcurrentHashMap<>();
	
	private final ResourcePool _resourcePool;
	private final Class<?> _packetClass;
	private final int _initialSize;
	
	private ByteBuffer _buf;
	private int _position;
	private int _limit;
	
	/**
	 * Creates an empty buffer sized to the historical maximum for the given packet class, falling back to the pool's segment size.
	 * @param resourcePool pool for acquiring direct {@link ByteBuffer}s
	 * @param packetClass the packet class; used for size-hint tracking
	 */
	public WriteBuffer(ResourcePool resourcePool, Class<?> packetClass)
	{
		_resourcePool = resourcePool;
		_packetClass = packetClass;
		_initialSize = MAXIMUM_PACKET_SIZE.getOrDefault(packetClass, resourcePool.getSegmentSize());
		_buf = resourcePool.getBuffer(_initialSize);
		_limit = _buf.capacity();
	}
	
	/**
	 * Creates a buffer seeded from a broadcast-cache snapshot.<br>
	 * The pooled buffer is sized to hold {@code cachedLength} bytes, the snapshot is bulk-copied in, and the logical limit is set to {@code cachedLength}.
	 * @param cached the byte snapshot produced by {@link #toByteArray()}
	 * @param cachedLength the number of bytes in {@code cached} that are meaningful
	 * @param resourcePool pool for acquiring direct {@link ByteBuffer}s
	 * @param packetClass the packet class; used for size-hint tracking
	 */
	public WriteBuffer(byte[] cached, int cachedLength, ResourcePool resourcePool, Class<?> packetClass)
	{
		_resourcePool = resourcePool;
		_packetClass = packetClass;
		_initialSize = MAXIMUM_PACKET_SIZE.getOrDefault(packetClass, Math.max(cachedLength, resourcePool.getSegmentSize()));
		_buf = resourcePool.getBuffer(Math.max(_initialSize, cachedLength));
		_buf.position(0);
		_buf.put(cached, 0, cachedLength);
		_buf.position(0);
		_position = cachedLength;
		_limit = cachedLength;
	}
	
	/**
	 * Grows the underlying buffer if it cannot hold at least {@code required} bytes.<br>
	 * Growth factor is 1.5x, clamped to at least {@code required}.
	 * @param required the minimum capacity needed
	 */
	private void ensureSize(int required)
	{
		final int capacity = _buf.capacity();
		if (capacity < required)
		{
			final int newSize = Math.max(required, (int) (capacity * 1.5));
			final ByteBuffer newBuf = _resourcePool.getBuffer(newSize);
			newBuf.position(0);
			_buf.position(0).limit(Math.min(_position, capacity));
			newBuf.put(_buf);
			newBuf.position(0);
			_resourcePool.recycleBuffer(_buf);
			_buf = newBuf;
		}
	}
	
	// -------------------------------------------------------------------------
	// Sequential writes (cursor-based).
	// -------------------------------------------------------------------------
	
	/**
	 * Writes a single byte at the current position.
	 * @param value the byte to write
	 */
	public void writeByte(byte value)
	{
		ensureSize(_position + 1);
		_buf.put(_position++, value);
	}
	
	/**
	 * Writes the lowest 8 bits of the given {@code int} as a single byte.
	 * @param value the value whose low byte is written
	 */
	public void writeByte(int value)
	{
		writeByte((byte) value);
	}
	
	/**
	 * Writes a boolean as a single byte - {@code 0x01} for {@code true}, {@code 0x00} for {@code false}.
	 * @param value the boolean to encode
	 */
	public void writeByte(boolean value)
	{
		writeByte((byte) (value ? 1 : 0));
	}
	
	/**
	 * Writes a variable-length byte sequence at the current position.
	 * @param value the bytes to write
	 */
	public void writeBytes(byte... value)
	{
		if ((value == null) || (value.length == 0))
		{
			return;
		}
		
		ensureSize(_position + value.length);
		_buf.put(_position, value, 0, value.length);
		_position += value.length;
	}
	
	/**
	 * Writes a little-endian {@code short} (2 bytes) at the current position.
	 * @param value the short to write
	 */
	public void writeShort(short value)
	{
		ensureSize(_position + 2);
		_buf.putShort(_position, value);
		_position += 2;
	}
	
	/**
	 * Writes the lowest 16 bits of the given {@code int} as a little-endian short.
	 * @param value the value whose low 16 bits are written
	 */
	public void writeShort(int value)
	{
		writeShort((short) value);
	}
	
	/**
	 * Writes a boolean as a little-endian short - {@code 1} for {@code true}, {@code 0} for {@code false}.
	 * @param value the boolean to encode
	 */
	public void writeShort(boolean value)
	{
		writeShort((short) (value ? 1 : 0));
	}
	
	/**
	 * Writes a little-endian {@code int} (4 bytes) at the current position.
	 * @param value the int to write
	 */
	public void writeInt(int value)
	{
		ensureSize(_position + 4);
		_buf.putInt(_position, value);
		_position += 4;
	}
	
	/**
	 * Writes a boolean as a little-endian int - {@code 1} for {@code true}, {@code 0} for {@code false}.
	 * @param value the boolean to encode
	 */
	public void writeInt(boolean value)
	{
		writeInt(value ? 1 : 0);
	}
	
	/**
	 * Writes a little-endian {@code long} (8 bytes) at the current position.
	 * @param value the long to write
	 */
	public void writeLong(long value)
	{
		ensureSize(_position + 8);
		_buf.putLong(_position, value);
		_position += 8;
	}
	
	/**
	 * Writes a little-endian IEEE 754 {@code float} (4 bytes) at the current position.
	 * @param value the float to write
	 */
	public void writeFloat(float value)
	{
		writeInt(Float.floatToRawIntBits(value));
	}
	
	/**
	 * Writes a little-endian IEEE 754 {@code double} (8 bytes) at the current position.
	 * @param value the double to write
	 */
	public void writeDouble(double value)
	{
		writeLong(Double.doubleToRawLongBits(value));
	}
	
	/**
	 * Writes a little-endian UTF-16 {@code char} (2 bytes) at the current position.
	 * @param value the character to write
	 */
	public void writeChar(char value)
	{
		writeShort((short) value);
	}
	
	/**
	 * Encodes a string as null-terminated UTF-16LE.<br>
	 * A {@code null} input produces only the null terminator (2 zero bytes).
	 * @param text the string to write, or {@code null}
	 */
	public void writeString(CharSequence text)
	{
		if (text != null)
		{
			final int len = text.length();
			ensureSize(_position + (len << 1) + 2);
			for (int i = 0; i < len; i++)
			{
				_buf.putShort(_position, (short) text.charAt(i));
				_position += 2;
			}
			
			// Null terminator - capacity already reserved above.
			_buf.putShort(_position, (short) 0);
			_position += 2;
			return;
		}
		
		writeChar('\000');
	}
	
	/**
	 * Encodes a string as a length-prefixed UTF-16LE sequence (no null terminator).
	 * @param text the string to write, or {@code null}
	 */
	public void writeSizedString(CharSequence text)
	{
		if ((text != null) && !text.isEmpty())
		{
			final int len = text.length();
			ensureSize(_position + 2 + (len << 1));
			_buf.putShort(_position, (short) len);
			_position += 2;
			for (int i = 0; i < len; i++)
			{
				_buf.putShort(_position, (short) text.charAt(i));
				_position += 2;
			}
			return;
		}
		
		writeShort(0);
	}
	
	// -------------------------------------------------------------------------
	// Indexed (absolute) writes and reads - used for header patching and encryption.
	// -------------------------------------------------------------------------
	
	public void writeByte(int index, byte value)
	{
		ensureSize(index + 1);
		_buf.put(index, value);
	}
	
	public void writeShort(int index, short value)
	{
		ensureSize(index + 2);
		_buf.putShort(index, value);
	}
	
	public void writeInt(int index, int value)
	{
		ensureSize(index + 4);
		_buf.putInt(index, value);
	}
	
	public byte readByte(int index)
	{
		return _buf.get(index);
	}
	
	public short readShort(int index)
	{
		return _buf.getShort(index);
	}
	
	public int readInt(int index)
	{
		return _buf.getInt(index);
	}
	
	// -------------------------------------------------------------------------
	// Position / limit / mark.
	// -------------------------------------------------------------------------
	
	/**
	 * Returns the current sequential write position.
	 * @return write cursor
	 */
	public int position()
	{
		return _position;
	}
	
	/**
	 * Moves the sequential write cursor.
	 * @param pos the new position
	 */
	public void position(int pos)
	{
		_position = pos;
	}
	
	public int limit()
	{
		return _limit;
	}
	
	public void limit(int newLimit)
	{
		ensureSize(newLimit);
		_limit = newLimit;
	}
	
	/**
	 * Marks the current write position as the logical end of the buffer's content, setting the limit to the current position.
	 */
	public void mark()
	{
		_limit = _position;
	}
	
	// -------------------------------------------------------------------------
	// Export / recycle.
	// -------------------------------------------------------------------------
	
	/**
	 * Prepares the underlying {@link ByteBuffer} for a channel write and exports it as a single-element array.<br>
	 * Updates the per-class size hint if this packet was larger than previously seen.
	 * @return a single-element array containing the underlying buffer, positioned at 0 with limit set to the logical packet length
	 */
	public ByteBuffer[] toByteBuffers()
	{
		if (_limit > _initialSize)
		{
			MAXIMUM_PACKET_SIZE.put(_packetClass, Math.min(_limit, 65535));
		}
		
		_buf.position(0);
		_buf.limit(_limit);
		return new ByteBuffer[]
		{
			_buf
		};
	}
	
	/**
	 * Snapshots the written bytes as a fresh heap {@code byte[]} for broadcast caching.<br>
	 * The returned array has length {@link #limit()} and contains exactly the logical packet content.
	 * @return a new heap byte array holding the packet data
	 */
	public byte[] toByteArray()
	{
		final byte[] snapshot = new byte[_limit];
		_buf.get(0, snapshot, 0, _limit);
		return snapshot;
	}
	
	/**
	 * Returns the pooled {@link ByteBuffer} back to the {@link ResourcePool}.
	 */
	public void releaseResources()
	{
		if (_buf != null)
		{
			_resourcePool.recycleBuffer(_buf);
			_buf = null;
		}
		
		_position = 0;
		_limit = 0;
	}
}

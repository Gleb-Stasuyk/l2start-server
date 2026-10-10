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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Inbound network buffer backed by a single NIO {@link ByteBuffer} handed in by the read handler.<br>
 * <br>
 * Provides sequential cursor-based reads (advancing the buffer's native position) plus absolute indexed accessors (used during decryption).<br>
 * <br>
 * All multi-byte values are read in <b>little-endian</b> order, matching the protocol convention. The wrapped buffer's byte order and position are used as-is; its limit defines the end of the decrypted payload.
 * @author Mobius
 */
public class ReadBuffer
{
	private final ByteBuffer _buf;
	
	/**
	 * Wraps the supplied buffer. Its byte order and position are used as-is.
	 * @param buffer the NIO buffer to wrap (must not be {@code null})
	 * @throws NullPointerException if {@code buffer} is {@code null}
	 */
	public ReadBuffer(ByteBuffer buffer)
	{
		_buf = Objects.requireNonNull(buffer);
	}
	
	/**
	 * Factory that wraps a standard NIO {@link ByteBuffer} as a {@link ReadBuffer}.
	 * @param buffer the NIO buffer to adapt
	 * @return a {@link ReadBuffer} backed by {@code buffer}
	 */
	public static ReadBuffer of(ByteBuffer buffer)
	{
		return new ReadBuffer(buffer);
	}
	
	// -------------------------------------------------------------------------
	// Sequential reads (cursor-based).
	// -------------------------------------------------------------------------
	
	/**
	 * Reads a little-endian UTF-16 {@code char} (2 bytes) and advances the position by 2.
	 * @return the char value
	 */
	public char readChar()
	{
		return _buf.getChar();
	}
	
	/**
	 * Reads a single byte and advances the position by 1.
	 * @return the byte value
	 */
	public byte readByte()
	{
		return _buf.get();
	}
	
	/**
	 * Reads a little-endian {@code short} (2 bytes) and advances the position by 2.
	 * @return the short value
	 */
	public short readShort()
	{
		return _buf.getShort();
	}
	
	/**
	 * Reads a little-endian {@code int} (4 bytes) and advances the position by 4.
	 * @return the int value
	 */
	public int readInt()
	{
		return _buf.getInt();
	}
	
	/**
	 * Reads a little-endian {@code long} (8 bytes) and advances the position by 8.
	 * @return the long value
	 */
	public long readLong()
	{
		return _buf.getLong();
	}
	
	/**
	 * Reads a little-endian IEEE 754 {@code float} (4 bytes) and advances the position by 4.
	 * @return the float value
	 */
	public float readFloat()
	{
		return _buf.getFloat();
	}
	
	/**
	 * Reads a little-endian IEEE 754 {@code double} (8 bytes) and advances the position by 8.
	 * @return the double value
	 */
	public double readDouble()
	{
		return _buf.getDouble();
	}
	
	/**
	 * Allocates a new array of the given length, fills it from the current position, and advances the position by {@code length}.
	 * @param length number of bytes to read
	 * @return newly allocated array containing the bytes
	 */
	public byte[] readBytes(int length)
	{
		// Validate before allocating, so a forged length cannot exhaust the heap.
		if ((length < 0) || (length > _buf.remaining()))
		{
			throw new BufferUnderflowException();
		}
		
		final byte[] dst = new byte[length];
		_buf.get(dst);
		return dst;
	}
	
	/**
	 * Reads exactly {@code dst.length} bytes into the supplied array and advances the position.
	 * @param dst destination array
	 */
	public void readBytes(byte[] dst)
	{
		_buf.get(dst);
	}
	
	/**
	 * Reads {@code length} bytes into {@code dst} starting at {@code offset} and advances the position.
	 * @param dst destination array
	 * @param offset start index within {@code dst}
	 * @param length number of bytes to transfer
	 */
	public void readBytes(byte[] dst, int offset, int length)
	{
		_buf.get(dst, offset, length);
	}
	
	/**
	 * Returns the number of bytes between the current position and the limit.
	 * @return remaining bytes available for reading
	 */
	public int remaining()
	{
		return _buf.remaining();
	}
	
	// -------------------------------------------------------------------------
	// Indexed (absolute) access - used during decryption.
	// -------------------------------------------------------------------------
	
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
	
	public void writeByte(int index, byte value)
	{
		_buf.put(index, value);
	}
	
	public void writeShort(int index, short value)
	{
		_buf.putShort(index, value);
	}
	
	public void writeInt(int index, int value)
	{
		_buf.putInt(index, value);
	}
	
	public int limit()
	{
		return _buf.limit();
	}
	
	public void limit(int newLimit)
	{
		_buf.limit(newLimit);
	}
}

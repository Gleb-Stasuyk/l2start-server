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
package org.l2jmobius.gameserver.network;

import org.l2jmobius.commons.network.buffer.ReadBuffer;
import org.l2jmobius.commons.network.buffer.WriteBuffer;

/**
 * Stateful XOR cipher used by the game protocol.<br>
 * Uses a 16-byte session key and advances a rolling offset stored in key bytes [8..11].
 * <ul>
 * <li>{@link #setKey(byte[])} seeds inbound and outbound keys.</li>
 * <li>First {@link #encrypt(WriteBuffer, int, int)} call only enables the cipher.</li>
 * <li>{@link #decrypt(ReadBuffer, int, int)} mirrors encryption with running XOR.</li>
 * </ul>
 * <br>
 * The key bytes are mirrored in {@code _inKeyMasked} / {@code _outKeyMasked} as pre-zero-extended {@code int}s so the hot encrypt / decrypt loops avoid the byte->int sign-extend per byte. The masked mirror of bytes [8..11] is refreshed by {@link #advanceOffset} after every call.
 * @author BazookaRpm, Mobius
 */
public class Encryption
{
	// Constants.
	private static final int KEY_LENGTH = 16;
	private static final int BYTE_MASK = 0xFF;
	private static final int NIBBLE_MASK = 0x0F;
	private static final int SHIFT_8 = 8;
	private static final int SHIFT_16 = 16;
	private static final int SHIFT_24 = 24;
	
	// Rolling offset is stored at key[8..11] (little-endian).
	private static final int OFFSET_INDEX = 8;
	
	// Session keys (references are immutable; contents are intentionally mutable).
	private final byte[] _inKey = new byte[KEY_LENGTH];
	private final byte[] _outKey = new byte[KEY_LENGTH];
	
	// Pre-zero-extended mirrors of _inKey / _outKey for the hot XOR loops.
	private final int[] _inKeyMasked = new int[KEY_LENGTH];
	private final int[] _outKeyMasked = new int[KEY_LENGTH];
	
	// Enabled state.
	private boolean _isEnabled;
	
	/**
	 * Copies the provided 16-byte key into inbound and outbound key buffers.<br>
	 * Fails fast if the input is null or shorter than 16 bytes.
	 * @param key
	 * @throws IllegalArgumentException if key is null or key.length &lt; 16.
	 */
	public void setKey(byte[] key)
	{
		if ((key == null) || (key.length < KEY_LENGTH))
		{
			throw new IllegalArgumentException("Encryption key must be at least 16 bytes.");
		}
		
		System.arraycopy(key, 0, _inKey, 0, KEY_LENGTH);
		System.arraycopy(key, 0, _outKey, 0, KEY_LENGTH);
		for (int i = 0; i < KEY_LENGTH; i++)
		{
			final int v = key[i] & BYTE_MASK;
			_inKeyMasked[i] = v;
			_outKeyMasked[i] = v;
		}
	}
	
	/**
	 * Encrypts in place using running XOR and advances the outbound key offset.<br>
	 * The first call only enables the cipher.
	 * @param data
	 * @param offset
	 * @param size
	 */
	public void encrypt(WriteBuffer data, int offset, int size)
	{
		if (!_isEnabled)
		{
			_isEnabled = true;
			return;
		}
		
		if (size <= 0)
		{
			return;
		}
		
		final int[] key = _outKeyMasked;
		int prev = 0;
		for (int i = 0; i < size; i++)
		{
			final int raw = data.readByte(offset + i) & BYTE_MASK;
			prev = raw ^ key[i & NIBBLE_MASK] ^ prev;
			data.writeByte(offset + i, (byte) prev);
		}
		
		advanceOffset(_outKey, _outKeyMasked, size);
	}
	
	/**
	 * Decrypts in place using running XOR and advances the inbound key offset.
	 * @param data
	 * @param offset
	 * @param size
	 */
	public void decrypt(ReadBuffer data, int offset, int size)
	{
		if (!_isEnabled)
		{
			return;
		}
		
		if (size <= 0)
		{
			return;
		}
		
		final int[] key = _inKeyMasked;
		int last = 0;
		for (int i = 0; i < size; i++)
		{
			final int enc = data.readByte(offset + i) & BYTE_MASK;
			data.writeByte(offset + i, (byte) (enc ^ key[i & NIBBLE_MASK] ^ last));
			last = enc;
		}
		
		advanceOffset(_inKey, _inKeyMasked, size);
	}
	
	private static void advanceOffset(byte[] key, int[] keyMasked, int size)
	{
		// Advance rolling offset at key[8..11] by size (little-endian int).
		int old = (key[OFFSET_INDEX] & BYTE_MASK);
		old |= (key[OFFSET_INDEX + 1] & BYTE_MASK) << SHIFT_8;
		old |= (key[OFFSET_INDEX + 2] & BYTE_MASK) << SHIFT_16;
		old |= (key[OFFSET_INDEX + 3] & BYTE_MASK) << SHIFT_24;
		
		old += size;
		
		final byte b0 = (byte) (old & BYTE_MASK);
		final byte b1 = (byte) ((old >> SHIFT_8) & BYTE_MASK);
		final byte b2 = (byte) ((old >> SHIFT_16) & BYTE_MASK);
		final byte b3 = (byte) ((old >> SHIFT_24) & BYTE_MASK);
		
		key[OFFSET_INDEX] = b0;
		key[OFFSET_INDEX + 1] = b1;
		key[OFFSET_INDEX + 2] = b2;
		key[OFFSET_INDEX + 3] = b3;
		
		// Keep the masked mirror in sync - only [8..11] change.
		keyMasked[OFFSET_INDEX] = b0 & BYTE_MASK;
		keyMasked[OFFSET_INDEX + 1] = b1 & BYTE_MASK;
		keyMasked[OFFSET_INDEX + 2] = b2 & BYTE_MASK;
		keyMasked[OFFSET_INDEX + 3] = b3 & BYTE_MASK;
	}
}

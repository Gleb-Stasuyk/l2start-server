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
package org.l2jmobius.loginserver.network;

import org.l2jmobius.commons.crypt.NewCrypt;
import org.l2jmobius.commons.network.buffer.ReadBuffer;
import org.l2jmobius.commons.network.buffer.WriteBuffer;
import org.l2jmobius.commons.util.Rnd;

/**
 * Blowfish cipher used by the login protocol.<br>
 * Uses a 16-byte static key for initial handshake, then switches to session-specific key.<br>
 * Handles XOR headers for static phase and checksums for session phase.
 * @author BazookaRpm, Mobius
 */
public class LoginEncryption
{
	// Static Blowfish key used for the initial handshake.
	private static final byte[] STATIC_BLOWFISH_KEY =
	{
		(byte) 0x6b,
		(byte) 0x60,
		(byte) 0xcb,
		(byte) 0x5b,
		(byte) 0x82,
		(byte) 0xce,
		(byte) 0x90,
		(byte) 0xb1,
		(byte) 0xcc,
		(byte) 0x2b,
		(byte) 0x6c,
		(byte) 0x55,
		(byte) 0x6c,
		(byte) 0x6c,
		(byte) 0x6c,
		(byte) 0x6c
	};
	
	// Static crypt instance used while the protocol is in the bootstrap phase.
	private static final NewCrypt STATIC_CRYPT = new NewCrypt(STATIC_BLOWFISH_KEY);
	
	// Blowfish block size and protocol overhead constants.
	private static final int BLOWFISH_BLOCK_SIZE = 8;
	private static final int STATIC_HEADER_SIZE = 8;
	private static final int DYNAMIC_HEADER_SIZE = 4;
	private static final int CHECKSUM_SIZE = 8;
	
	// Session-specific crypt instance, set once the client key is negotiated.
	private NewCrypt _sessionCrypt;
	
	// Tracks whether the connection is still using the static key for encryption.
	private boolean _usingStaticKey = true;
	
	/**
	 * Sets the session-specific Blowfish key used after the static phase.
	 * @param key the negotiated session Blowfish key
	 */
	public void setKey(byte[] key)
	{
		_sessionCrypt = new NewCrypt(key);
	}
	
	/**
	 * Decrypts in place using session key and verifies checksum.
	 * @param data the backing buffer
	 * @param offset the start offset of the packet
	 * @param size the packet size in bytes
	 * @return {@code true} if the checksum is valid, {@code false} otherwise
	 */
	public boolean decrypt(ReadBuffer data, int offset, int size)
	{
		// Extract to byte array.
		final byte[] raw = new byte[size];
		for (int i = 0; i < size; i++)
		{
			raw[i] = data.readByte(offset + i);
		}
		
		// Decrypt and verify.
		_sessionCrypt.decrypt(raw, 0, size);
		final boolean valid = NewCrypt.verifyChecksum(raw, 0, size);
		
		// Write back to buffer if valid.
		if (valid)
		{
			for (int i = 0; i < size; i++)
			{
				data.writeByte(offset + i, raw[i]);
			}
		}
		
		return valid;
	}
	
	/**
	 * Computes the encrypted packet size for a given plaintext payload size, based on the current protocol phase (static vs. session key).
	 * @param dataSize the plaintext payload size
	 * @return the encrypted packet size including headers, padding and checksum/XOR data
	 */
	private int encryptedSize(int dataSize)
	{
		final int headerSize = _usingStaticKey ? STATIC_HEADER_SIZE : DYNAMIC_HEADER_SIZE;
		int sizeWithHeader = dataSize + headerSize;
		
		// Align to Blowfish block size (always pad at least one block).
		final int remainder = sizeWithHeader % BLOWFISH_BLOCK_SIZE;
		sizeWithHeader += (BLOWFISH_BLOCK_SIZE - remainder);
		
		// Append checksum/XOR header size.
		return sizeWithHeader + CHECKSUM_SIZE;
	}
	
	/**
	 * Encrypts in place using static or session key.<br>
	 * First call uses static key with XOR pass.
	 * @param data the backing buffer
	 * @param offset the start offset of the plaintext payload
	 * @param size the plaintext payload size
	 * @return {@code true} once encryption is completed
	 */
	public boolean encrypt(WriteBuffer data, int offset, int size)
	{
		final int packetSize = encryptedSize(size);
		
		// Extract to byte array.
		final byte[] raw = new byte[packetSize];
		for (int i = 0; i < size; i++)
		{
			raw[i] = data.readByte(offset + i);
		}
		
		// Encrypt.
		if (_usingStaticKey)
		{
			NewCrypt.encXORPass(raw, 0, packetSize, Rnd.nextInt());
			STATIC_CRYPT.crypt(raw, 0, packetSize);
			_usingStaticKey = false;
		}
		else
		{
			NewCrypt.appendChecksum(raw, 0, packetSize);
			_sessionCrypt.crypt(raw, 0, packetSize);
		}
		
		// Write back to buffer.
		data.limit(offset + packetSize);
		for (int i = 0; i < packetSize; i++)
		{
			data.writeByte(offset + i, raw[i]);
		}
		
		return true;
	}
}

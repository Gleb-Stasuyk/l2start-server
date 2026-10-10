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
package org.l2jmobius.loginserver.network.gameserverpackets.receive;

import org.l2jmobius.commons.network.packet.SimpleReadablePacket;
import org.l2jmobius.loginserver.GameServerThread;

/**
 * Reads the account list reported by a connected game server.<br>
 * Consumes the packet payload and registers each decoded account in the login server state.
 * <ul>
 * <li>Skips the packet opcode already handled by the packet dispatcher.</li>
 * <li>Reads the reported account count from the packet body.</li>
 * <li>Registers each non-empty account on the target game server thread.</li>
 * <li>Performs basic packet validation before processing.</li>
 * </ul>
 * @author BazookaRpm
 */
public class PlayerInGame extends SimpleReadablePacket
{
	public PlayerInGame(byte[] decrypt, GameServerThread server)
	{
		super(decrypt);
		
		if (decrypt == null)
		{
			throw new IllegalArgumentException("PlayerInGame packet data cannot be null.");
		}
		
		if (server == null)
		{
			throw new IllegalArgumentException("PlayerInGame target GameServerThread cannot be null.");
		}
		
		if (getRemainingLength() < 3)
		{
			throw new IllegalArgumentException("PlayerInGame packet is too short. Length: " + getLength() + ".");
		}
		
		readByte(); // Packet id, it is already processed.
		
		final int accountCount = readShort();
		if (accountCount <= 0)
		{
			return;
		}
		
		for (int i = 0; i < accountCount; i++)
		{
			if (getRemainingLength() < 2)
			{
				throw new IllegalArgumentException("PlayerInGame packet ended before account index " + i + ".");
			}
			
			final String accountName = readString();
			if (!accountName.isEmpty())
			{
				server.addAccountOnGameServer(accountName);
			}
		}
	}
}

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
import org.l2jmobius.commons.network.buffer.ReadBuffer;

/**
 * Packet-identification strategy.<br>
 * <br>
 * Implementations inspect the leading opcode byte(s) of a decrypted payload buffer and return the {@link ReadablePacket} subclass that knows how to parse the remaining fields, or {@code null} when the opcode is not recognised (the caller will silently discard the data).<br>
 * <br>
 * Because exactly one method is required, this interface is marked {@link FunctionalInterface} and can be supplied as a lambda or method reference.
 * @param <T> the client type bound to the connection
 * @author JoeAlisson, Mobius
 */
@FunctionalInterface
public interface PacketHandler<T extends Client<Connection<T>>>
{
	/**
	 * Maps raw buffer data to a concrete packet instance.<br>
	 * The buffer is positioned at the first opcode byte; the implementation should read only the bytes it needs to identify the packet and leave the rest for<br>
	 * {@link ReadablePacket#read()}.
	 * @param buffer the decrypted payload, positioned at the first opcode byte
	 * @param client the client that sent this data (available for per-client branching)
	 * @return the matching {@link ReadablePacket}, or {@code null} if the opcode is unknown
	 */
	ReadablePacket<T> handle(ReadBuffer buffer, T client);
}

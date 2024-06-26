/* 
 * Copyright (c) 2001 by Matt Welsh and The Regents of the University of 
 * California. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this software and its
 * documentation for any purpose, without fee, and without written agreement is
 * hereby granted, provided that the above copyright notice and the following
 * two paragraphs appear in all copies of this software.
 * 
 * IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR
 * DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES ARISING OUT
 * OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF THE UNIVERSITY OF
 * CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS FOR A PARTICULAR PURPOSE.  THE SOFTWARE PROVIDED HEREUNDER IS
 * ON AN "AS IS" BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATION TO
 * PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
 *
 * Author: Matt Welsh <mdw@cs.berkeley.edu>
 * 
 */

package org.jcyclone.ext.asocket;

import org.jcyclone.core.event.BufferElement;
import org.jcyclone.core.queue.IElement;
import org.jcyclone.core.queue.ISink;
import org.jcyclone.core.queue.ISource;
import org.jcyclone.core.queue.SinkClosedException;
import org.jcyclone.util.FastLinkedList;

import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Internal class used to represent state of an active datagram socket.
 */
public abstract class DatagramSockState implements ASocketConst {

	private static final boolean DEBUG = false;

	protected AUdpSocket udpsock;
	protected ISink readCompQ;
	protected IElement clogged_qel;
	protected int clogged_numtries;
	protected int readClogTries, writeClogThreshold, maxPacketSize;

	protected byte readBuf[];
	protected boolean closed = false;
	protected long seqNum = 1;
	protected AUdpInPacket pkt;

	protected int outstanding_writes, numEmptyWrites;
	protected FastLinkedList writeReqList;
	protected AUdpWriteRequest cur_write_req;
	protected BufferElement cur_write_buf;

	protected abstract void readInit(SelectSourceIF read_selsource, ISink compQ, int readClogTries);

	protected abstract void doRead();

	protected abstract boolean addWriteRequest(ASocketRequest req, ISource write_selsource);

	protected abstract boolean tryWrite() throws SinkClosedException;

	protected abstract void writeMaskEnable();

	protected abstract void writeMaskDisable();

	protected abstract void close(ISink closeEventQueue);

	protected abstract DatagramSocket getSocket();

	protected abstract void connect(InetAddress addr, int port);

	void initWrite(AUdpWriteRequest req) {
		this.cur_write_req = req;
		this.cur_write_buf = req.buf;
	}

	void writeReset() {
		this.cur_write_req = null;
		this.outstanding_writes--;
	}

	boolean isClosed() {
		return closed;
	}


}


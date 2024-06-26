/*
 * Copyright (c) 2000 by Matt Welsh and The Regents of the University of 
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

package org.jcyclone.ext.gnutella;

import org.jcyclone.core.cfg.IConfigData;
import org.jcyclone.core.cfg.ISystemConfig;
import org.jcyclone.core.event.BufferElement;
import org.jcyclone.core.handler.IEventHandler;
import org.jcyclone.core.profiler.IProfiler;
import org.jcyclone.core.queue.*;
import org.jcyclone.core.stage.IStageManager;
import org.jcyclone.ext.asocket.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;

/**
 * A GnutellaServer is a SandStorm stage which allows outgoing connections
 * to be established to the Gnutella network, and accepts incoming
 * connections. The server has a client sink associated with it, onto which
 * GnutellaConnection and GnutellaPacket events are pushed.
 * When a connection is closed, a SinkClosedEvent is pushed, with the
 * sink pointer set to the GnutellaConnection that closed. If a an
 * outgoing connection fails, a GnutellaConnectFailedevent is pushed.
 *
 * @author Matt Welsh (mdw@cs.berkeley.edu)
 * @see org.jcyclone.ext.gnutella.GnutellaConnection
 * @see org.jcyclone.ext.gnutella.GnutellaPacket
 */
public class GnutellaServer implements IEventHandler, GnutellaConst {

	private static final boolean DEBUG = false;

	private boolean acceptIncoming;
	private boolean connectUpstream;
	private String hostname;
	private int port;
	private int listenPort;

	private ATcpServerSocket servsock;
	private ATcpClientSocket clisock;
	private IStageManager mgr;
	private ISink mySink, clientSink;

	// ATcpConnection -> GnutellaPacketReader
	private Hashtable readerTable;
	// ATcpConnection -> GnutellaConnection
	private Hashtable connTable;
	// ATcpConnection -> connectionState
	private Hashtable newConnTable;
	// InetAddress -> ATcpClientSocket (self)
	private Hashtable pendingConnTable;

	private Vector activeConnections;

	private static int num_svrs;
	private static byte connectMsg[];
	private static byte connectReplyMsg[];

	// Get byte arrays for the handshake messages
	static {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter ps = new PrintWriter(baos);
		ps.print(GNUTELLA_CONNECT);
		ps.flush();
		connectMsg = baos.toByteArray();
		baos = new ByteArrayOutputStream();
		ps = new PrintWriter(baos);
		ps.print(GNUTELLA_OK);
		ps.flush();
		connectReplyMsg = baos.toByteArray();
	}

	/**
	 * Create a Gnutella server listening for incoming connections on
	 * the default port of 6346.
	 */
	public GnutellaServer(IStageManager mgr, ISink clientSink) throws Exception {
		this(mgr, clientSink, DEFAULT_GNUTELLA_PORT);
	}

	/**
	 * Create a Gnutella server listening for incoming connections on
	 * the given listenPort. If listenPort == 0, no incoming connections
	 * will be accepted. (Outgoing connections can still be established
	 * using openConnection.)
	 */
	public GnutellaServer(IStageManager mgr, ISink clientSink, int listenPort) throws Exception {
		this.mgr = mgr;
		this.clientSink = clientSink;
		this.listenPort = listenPort;

		if (listenPort == 0) {
			acceptIncoming = false;
		} else {
			acceptIncoming = true;
		}
		this.readerTable = new Hashtable(1);
		this.connTable = new Hashtable(1);
		this.newConnTable = new Hashtable(1);
		this.pendingConnTable = new Hashtable(1);
		this.activeConnections = new Vector(1);

		// Create the stage and register it
		mgr.createStage("GnutellaServer " + num_svrs + " <port " + listenPort + ">",
		    this, null);
	}

	public void init(IConfigData config) throws IOException {
		mySink = config.getStage().getSink();

		if (connectUpstream) {
			clisock = new ATcpClientSocket(hostname, port, mySink, WRITE_CLOG_THRESHOLD, -1);
		}

		if (acceptIncoming) {
			servsock = new ATcpServerSocket(listenPort, mySink, WRITE_CLOG_THRESHOLD);
		}
	}

	public void destroy() {
	}

	/**
	 * Open a connection to the given hostname and port. When the
	 * connection is established, a GnutellaConnection will be pushed to
	 * this server's client sink.
	 */
	public void openConnection(String hostname, int port) throws UnknownHostException {
		if (DEBUG) System.err.println("GnutellaServer: Opening connection to " + hostname + ":" + port);
		ATcpClientSocket clisock = new ATcpClientSocket(hostname, port, mySink, WRITE_CLOG_THRESHOLD, -1);
		pendingConnTable.put(clisock, clisock);
	}

	/**
	 * Open a connection to the given address and port. When the
	 * connection is established, a GnutellaConnection will be pushed to
	 * this server's client sink.
	 */
	public void openConnection(InetAddress address, int port) {
		if (DEBUG) System.err.println("GS: Opening connection to " + address + ":" + port);
		ATcpClientSocket clisock = new ATcpClientSocket(address, port, mySink, WRITE_CLOG_THRESHOLD, -1);
		pendingConnTable.put(clisock, clisock);
	}

	// Main event handler
	public void handleEvent(IElement qel) {
		if (DEBUG) System.err.println("GnutellaServer got qel: " + qel);

		if (qel instanceof ATcpInPacket) {
			ATcpInPacket pkt = (ATcpInPacket) qel;
			if (newConnTable.get(pkt.getConnection()) != null) {
				// New connection - handle handshake
				handleHandshake(pkt);
			} else {
				continuePacket((ATcpInPacket) qel);
			}

		} else if (qel instanceof ATcpConnection) {
			ATcpConnection conn = (ATcpConnection) qel;
			handleIncomingConnection(conn);

		} else if (qel instanceof ASocketErrorEvent) {
			System.err.println("GnutellaServer got error: " + qel.toString());

			if (qel instanceof ATcpConnectFailedEvent) {
				ATcpConnectFailedEvent failed = (ATcpConnectFailedEvent) qel;
				pendingConnTable.remove(failed.getSocket());
				GnutellaConnectFailedEvent cfe = new
				    GnutellaConnectFailedEvent((ATcpClientSocket) failed.getSocket());
				clientSink.enqueueLossy(cfe);
			}

		} else if (qel instanceof SinkDrainedEvent) {
			// Ignore

		} else if (qel instanceof SinkCloggedEvent) {
			// Some connection is clogged; tell the user
			SinkCloggedEvent sce = (SinkCloggedEvent) qel;
			GnutellaConnection gc = (GnutellaConnection) connTable.get(sce.sink);
			if (gc != null) clientSink.enqueueLossy(new SinkCloggedEvent(gc, null));

		} else if (qel instanceof SinkClosedEvent) {
			// Some connection closed; tell the user
			SinkClosedEvent sce = (SinkClosedEvent) qel;
			GnutellaConnection gc = (GnutellaConnection) connTable.get(sce.sink);
			if (gc != null) clientSink.enqueueLossy(new SinkClosedEvent(gc));
			cleanupConnection((ATcpConnection) sce.sink, gc);
		}
	}

	public void handleEvents(List events) {
		for (int i = 0; i < events.size(); i++) {
			handleEvent((IElement) events.get(i));
		}
	}

	private void continuePacket(ATcpInPacket pkt) {
		GnutellaConnection gc = (GnutellaConnection) connTable.get(pkt.getConnection());
		if (gc == null) {
			System.err.println("GS: Warning: continuePacket got packet for bad connection: " + pkt);
			return;
		}
		GnutellaPacketReader gpr = gc.getReader();

		try {
			gpr.pushPacket(pkt);
			GnutellaPacket gp = gpr.getGnutellaPacket();

			// May have multiple GnutellaPackets pending
			while (gp != null) {
				if (DEBUG) System.err.println("GnutellaServer: Finished reading packet");
				gp.setConnection(gc);
				if (!clientSink.enqueueLossy(gp)) {
					//System.err.println("GS: Warning: Cannot enqueue_lossy packet "+gp);
				}

				gp = gpr.getGnutellaPacket();
			}

		} catch (IOException e) {
			//System.err.println("GnutellaServer: Got exception reading packet: "+e);
			// XXX SHould drop packet and close connection
			return;
		}
	}

	private void handleIncomingConnection(ATcpConnection conn) {
		if (DEBUG) System.err.println("GnutellaServer: handleIncomingConnection called on " + conn);

		if (conn.getServerSocket() != null) {
			// Incoming connection on server socket
			if (DEBUG) System.err.println("GnutellaServer: new connection on server socket");
			newConnTable.put(conn, new connectionState(true));
		} else {
			// Upstream connection established
			if (DEBUG) System.err.println("GnutellaServer: upstream connection established");
			pendingConnTable.remove(conn.getClientSocket());
			newConnTable.put(conn, new connectionState(false));
			ISink upstream = (ISink) conn;
			// Send the connect message

			if (DEBUG) System.err.println("GnutellaServer: Sending handshake " + new String(connectMsg));
			sendBytes(upstream, connectMsg);
		}

		// Profile the connection if profiling enabled
		IProfiler profiler = mgr.getProfiler();
		ISystemConfig cfg = mgr.getConfig();
		if ((profiler != null) && (cfg.getBoolean("global.profile.sockets"))) profiler.add(conn.toString(), conn);

		if (DEBUG) System.err.println("GnutellaServer: handleIncomingConnection doing startReader");
		conn.startReader(mySink);
	}

	// Inform user of connection
	private void pushNewConnection(ATcpConnection conn) {
		GnutellaConnection gc = new GnutellaConnection(this, conn);

		connTable.put(conn, gc);
		activeConnections.addElement(gc);
		if (!clientSink.enqueueLossy(gc)) {
			System.err.println("GS: Warning: Cannot enqueue_lossy " + gc);
		}
	}

	void closeConnection(ATcpConnection tcpconn, ISink compQ) {
		try {
			tcpconn.close(compQ);
		} catch (SinkClosedException e) {
			// Ignore
		}
	}

	void cleanupConnection(ATcpConnection tcpconn, GnutellaConnection gc) {
		readerTable.remove(tcpconn);
		connTable.remove(tcpconn);
		newConnTable.remove(tcpconn);
		if (gc != null) activeConnections.removeElement(gc);
	}

	private void handleHandshake(ATcpInPacket pkt) {
		if (DEBUG) System.err.println("GnutellaServer: handleHandshake for " + pkt + ", conn " + pkt.getConnection());
		connectionState cs = (connectionState) newConnTable.get(pkt.getConnection());
		if (DEBUG) System.err.println("GnutellaServer: cs.is_incoming is " + cs.is_incoming);

		boolean done;
		try {
			done = cs.process(pkt);
		} catch (IOException e) {
			// Got back packet
			if (DEBUG) System.err.println("GnutellaServer: got bad handshake");
			try {
				pkt.getConnection().close(null);
			} catch (SinkClosedException sde) {
				// Ignore
			}
			newConnTable.remove(pkt.getConnection());
			return;
		}
		if (done) {
			if (DEBUG) System.err.println("GnutellaServer: handshake complete");

			// Finished handshake
			if (cs.is_incoming) {
				// Is an incoming connection - got connect message
				ISink sink = (ISink) pkt.getConnection();
				sendBytes(sink, connectReplyMsg);
				if (DEBUG) System.err.println("GnutellaServer: send connect reply msg");
				newConnTable.remove(pkt.getConnection());
				pushNewConnection(pkt.getConnection());

			} else {
				// Upstream connection - got the reply message
				newConnTable.remove(pkt.getConnection());
				pushNewConnection(pkt.getConnection());
			}
		}
	}

	private void sendBytes(ISink sink, byte msg[]) {
		BufferElement buf = new BufferElement(msg);
		try {
			sink.enqueue(buf);
		} catch (SinkFullException sfe) {
			System.err.println("GnutellaServer: Got sink full exception in sendBytes");
		} catch (SinkException sde) {
			System.err.println("GnutellaServer: Got sink exception in sendBytes");
			// XXX MDW: Need to close connection?
		}

	}

	public String toString() {
		String s = "GnutellaServer ";
		if (connectUpstream) {
			s += "[" + hostname + ":" + port + "]";
		}
		if (acceptIncoming) {
			s += "[listen=" + listenPort + "]";
		}
		return s;
	}

	/**
	 * Register a sink to receive incoming packets on this
	 * connection.
	 */
	public void registerSink(ISink sink) {
		this.clientSink = sink;
	}

	// Return my sink so that GnutellaConnection can redirect
	// packet completions to it
	ISink getSink() {
		return mySink;
	}

	/**
	 * Send a packet to all nodes but the given node. Useful for packet
	 * routing.
	 */
	public void sendToAllButOne(GnutellaPacket pkt, GnutellaConnection exclude) {

		for (int i = 0; i < activeConnections.size(); i++) {
			GnutellaConnection gc = (GnutellaConnection) activeConnections.elementAt(i);
			if (!gc.equals(exclude)) {
				if (!gc.enqueueLossy(pkt)) {
					System.err.println("GS: Warning: Could not enqueue_lossy packet to " + gc);
				}
			}
		}
	}

	/**
	 * Internal class used to monitor state of connections during
	 * handshake phase
	 */
	class connectionState {
		boolean is_incoming;
		byte barr[];
		byte target[];
		int cur_offset, cur_length_target;

		connectionState(boolean is_incoming) {
			this.is_incoming = is_incoming;
			if (is_incoming) {
				barr = new byte[connectMsg.length];
				cur_offset = 0;
				cur_length_target = barr.length;
				target = connectMsg;
			} else {
				barr = new byte[connectReplyMsg.length];
				cur_offset = 0;
				cur_length_target = barr.length;
				target = connectReplyMsg;
			}
		}

		// Process a packet and see if it matches the target
		boolean process(ATcpInPacket packet) throws IOException {
			byte in[] = packet.getBytes();
			if (DEBUG) System.err.println("GnutellaServer: process got bytes: " + new String(in));

			int c;
			if (DEBUG) System.err.println("GnutellaServer: in.length=" + in.length + ", cur_off=" + cur_offset + ", lt=" + cur_length_target);

			if (in.length < cur_length_target - cur_offset) {
				c = in.length;
			} else {
				c = cur_length_target - cur_offset;
			}
			System.arraycopy(in, 0, barr, cur_offset, c);
			cur_offset += c;

			if (cur_offset == cur_length_target) {
				boolean match = true;
				for (int i = 0; i < barr.length; i++) {
					if (barr[i] != target[i]) match = false;
				}
				if (match)
					return true;
				else
					throw new IOException("process got bad handshake packet");
			}

			return false;
		}

	}

}

/*
 * Copyright (c) 2002 by The Regents of the University of California. 
 * All rights reserved.
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
 * Author: Dennis Chi <denchi@uclink4.berkeley.edu> 
 *
 */

package org.jcyclone.ext.atls;

import COM.claymoresystems.ptls.SSLHandshakeServer;
import COM.claymoresystems.ptls.SSLOutputStream;
import org.jcyclone.core.cfg.IConfigData;
import org.jcyclone.core.event.BufferElement;
import org.jcyclone.core.handler.IEventHandler;
import org.jcyclone.core.queue.IElement;
import org.jcyclone.core.queue.ISink;
import org.jcyclone.core.queue.SinkClosedEvent;
import org.jcyclone.core.queue.SinkClosedException;
import org.jcyclone.core.stage.IStageManager;
import org.jcyclone.ext.asocket.ATcpClientSocket;
import org.jcyclone.ext.asocket.ATcpConnection;
import org.jcyclone.ext.asocket.ATcpInPacket;
import org.jcyclone.ext.asocket.ATcpServerSocket;
import org.jcyclone.ext.atls.protocol.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;

/**
 * aTLSRecordStage is the stage in which all data is received. Each packet will be parsed
 * by aTLSPacketReader and appropriately dealt with based on the type of message it is.
 * If it is a handshake packet, then will be posted to the handshake stage. If it is an
 * application data packet, then will be posted to the user's SinkIF.
 * This stage is multithreaded, but can change to single threaded by swapping
 * the class definitions below.
 */

//class aTLSRecordStage implements EventHandlerIF, aTLSConst, SingleThreadedEventHandlerIF{
class ATLSRecordStage implements IEventHandler, ATLSConst {
	private static final boolean DEBUG = true;
	private static final boolean DEBUG2 = false;
	private IStageManager mgr;
	private ISink mySink, handshakeSink, encryptSink;

	// this is added to profile to see how long things take in this stage:
	private static final boolean PROFILE = false;

	/**
	 * Make sure that this Hashtable is locked when being modified because
	 * multiple threads could be adding/retrieving/removing from the hashTable.
	 * This hash table will map ATcpConnections to aTLSConnections.
	 */
	Hashtable connTable;

	/**
	 * This hash table will map ATcpClientSockets to aTLSClientSockets so that
	 * any ATcpConnections that arrive will be associated with the appropriate user
	 * sink.
	 */
	Hashtable clientSocketTable;

	/**
	 * This hash table will map ATcpServerSockets to aTLSServerSockets so that
	 * any ATcpConnections that arrive will be associated with the appropriate user
	 * sink.
	 */
	Hashtable serverSocketTable;

	/**
	 * Creates the aTLSRecordStage. Needs the SinkIF of both the handshake stage and the
	 * encrypt stage because packets will be routed to those stages from here.
	 */
	public ATLSRecordStage(IStageManager mgr, ISink handshakeSink, ISink encryptSink) throws Exception {
		this.mgr = mgr;
		this.handshakeSink = handshakeSink;
		this.encryptSink = encryptSink;

		connTable = new Hashtable();
		clientSocketTable = new Hashtable();
		serverSocketTable = new Hashtable();

		mgr.createStage("aTLSRecordStage", this, null);
	}

	/**
	 * The Sandstorm stage initialization method.
	 */
	public void init(IConfigData config) {
		mySink = config.getStage().getSink();
	}

	/**
	 * This method handles the following different QueueElementIF types:
	 * aTLSConnectRequest - posted by the aTLSClientSocket. Will create an ATcpClientSocket that will try to
	 * connect to the associated host/port.
	 * <p/>
	 * aTLSListenRequest - posted by the aTLSServerSocket. Will create an ATcpServerSocket that will wait
	 * for incoming client connections.
	 * <p/>
	 * ATcpConnection - indicates that a connection should begin. Creates an aTLSConnection, and posts a
	 * handshakePacket to the handshakeStage to start the handshake process.
	 * <p/>
	 * SinkClosedEvent - will clean up the hash tables, etc...
	 * <p/>
	 * ATcpInPacket - will call aTLSPacketReader of the connection to parse the packet and appropriately
	 * deal with the data based on what type of message it is.
	 */
	public void handleEvent(IElement element) {
		if (DEBUG) System.err.println("aTLSRecordStage GOT QEL: " + element);

		if (element instanceof ATLSConnectRequest) {
			// this request had to have been sent from the aTLSClientSocket class
			ATLSConnectRequest req = (ATLSConnectRequest) element;
			ATLSClientSocket atlscs = req.atlscs;
			if (PROFILE) {
				atlscs.prof.addMeasurements(System.currentTimeMillis(), "aTLSRecordStage just received connect request from clientsocket, Now going to create atcpclientsocket and wait for atcpconnection");
			}

			if (DEBUG) System.err.println("aTLSRecordStage: creating an ATcpClientSocket.");

			// setting up the ATcpClientSocket so that it will try to connect to server
			ATcpClientSocket atcpcs = new ATcpClientSocket(atlscs.clientHost, atlscs.clientPort, mySink);

			// sets a reference to the ATcpClientSocket for the aTLSClientSocket
			atlscs.setATcpClientSocket(atcpcs);

			clientSocketTable.put(atcpcs, atlscs);
		} else if (element instanceof ATLSListenRequest) {
			// this request must be sent from aTLSServerSocket class
			ATLSListenRequest req = (ATLSListenRequest) element;
			ATLSServerSocket atlsss = req.atlsss;

			try {
				if (DEBUG) System.err.println("aTLSRecordStage: creating an ATcpServerSocket");
				ATcpServerSocket atcpss = new ATcpServerSocket(atlsss.serverPort, mySink);

				atlsss.setATcpServerSocket(atcpss);

				serverSocketTable.put(atcpss, atlsss);
			} catch (IOException ioe) {
				System.err.println("aTLSRecordStage: Exception trying to create ATcpServerSocket: " + ioe);
			}
		} else if (element instanceof ATcpConnection) {
			// so a "connection" has been established, so create an aTLSConnection and start handshake
			ATcpConnection atcpconn = (ATcpConnection) element;
			ATLSConnection atlsconn;
			if (atcpconn.getClientSocket() != null) {
				if (DEBUG) System.err.println("aTLSRecordStage: Creating an aTLSConnection.");
				ATLSClientSocket cs = (ATLSClientSocket) clientSocketTable.get(atcpconn.getClientSocket());

				if (PROFILE) {
					cs.prof.addMeasurements(System.currentTimeMillis(), "aTLSRecordStage just received ATcpConnection for client, going to startReader and start the handshake");
				}

				atlsconn = new ATLSConnection(cs, atcpconn, cs.getSink(), encryptSink, mySink, false, ATLSClientSocket.ctx, cs.aTLSSessionID);
				// allow the client socket to have a reference to the aTLSConnection to gain
				// acces to the sessionID of this connection for resuming purposes.
				cs.setaTLSConn(atlsconn);

				if (PROFILE) {
					atlsconn.prof = cs.prof;
				}
			} else {
				ATLSServerSocket ss = (ATLSServerSocket) serverSocketTable.get(atcpconn.getServerSocket());
				atlsconn = new ATLSConnection(ss, atcpconn, ss.getSink(), encryptSink, mySink, true, ss.ctx, null);
				if (PROFILE) {
					long time = System.currentTimeMillis();
					atlsconn.prof = new ATLSprofiler(time);
					atlsconn.prof.addMeasurements(time, "aTLSRecordStage: new connection for the server, alerting handshake stage");
				}
			}

			connTable.put(atcpconn, atlsconn);

			// this will now get the handshake rolling, do this regardless if client/server
			handshakeSink.enqueueLossy(new ATLSHandshakePacket(atlsconn));

			// now I'm going to start the atcp reader so that all messages on this connection pass through this class
			atcpconn.startReader(mySink);
		} else if (element instanceof SinkClosedEvent) {
			// Some connection closed; tell the user
			SinkClosedEvent sce = (SinkClosedEvent) element;

			if (!(sce.sink instanceof ATcpConnection)) {
				System.err.println("aTLSRecordStage: Bad SinkClosedEvent. Internal Error, contact mdw@cs.berkeley.edu");
			}
			ATLSConnection atlsconn = (ATLSConnection) connTable.get((ATcpConnection) sce.sink);
			if (atlsconn != null) {
				// profile stuff
				if (PROFILE)
					System.err.println("aTLSRecordStage received the sinkclosedevent, sending closedevent back to user");

				// if we can, pass it to the dataSink, but if startReader hasn't been called,
				// then pass it to the newconnsink because only other place to alert the user.
				if (atlsconn.getDataSink() != null) {
					atlsconn.getDataSink().enqueueLossy(new SinkClosedEvent(atlsconn));
				} else {
					atlsconn.getNewConnSink().enqueueLossy(new SinkClosedEvent(atlsconn));
				}
				cleanupConnection(atlsconn);
			}
		} else if (element instanceof ATcpInPacket) {
			// need to add the packet to the aTLSPacketReader so that it can make sure a whole
			// record has been received, and so it can order the packets based on the sequeunce number.
			// This ordering and synchronizing is necessary for multithreading because for a ssl/tls connection
			// order is the most important thing.

			ATcpInPacket pkt = (ATcpInPacket) element;
			ATcpConnection atcpconn = pkt.getConnection();
			ATLSConnection atlsconn = (ATLSConnection) connTable.get(atcpconn);

			ATLSPacketReader reader;

			synchronized (atlsconn) {
				if (PROFILE) {
					atlsconn.prof.addMeasurements(System.currentTimeMillis(), "aTLSRecordStage just received an ATcpInPacket for " + atlsconn.type);
				}

				reader = atlsconn.getReader();

				(reader.asis).addPacket(pkt);

				if (DEBUG) System.err.println("aTLSRecordStage: Calling recordReader()");

				LinkedList records = null;

				try {
					records = reader.readPacket();
				} catch (Exception e) {
					System.err.println("aTLSRecordStage: Exception trying to read packet: " + e);
					e.printStackTrace();
					for (int i = 0; i < 5; i++) {
						System.err.println();
					}
					try {
						atlsconn.getConnection().close(mySink);
					} catch (SinkClosedException sce) {
						System.err.println("aTLSRecordStage: Exception trying to close the connection " + sce);
					}
				}

				if (DEBUG) System.err.println("aTLSRecordStage: DONE Calling recordReader()");

				if (records.isEmpty()) {
					if (DEBUG) System.err.println("aTLSRecordStage: recordReader returned null, so not enough info to generate a Record yet.");
					// not enough data to generate an entire record yet.
					return;
				}

				while (!records.isEmpty()) {
					// multiple records could have been sent in one packet, so need to handle all of them individually
					ATLSRecord record = (ATLSRecord) records.removeFirst();

					if (record instanceof ATLSCipherSpecRecord) {
						changeCipherSpecMessage(atlsconn, record);
					} else if (record instanceof ATLSAlertRecord) {
						alertMessage(atlsconn, record);
					} else if (record instanceof ATLSHandshakeRecord) {
						handshakeMessage(atlsconn, record);
					} else if (record instanceof ATLSAppDataRecord) {
						dataMessage(atlsconn, record);
					} else {
						System.err.println("aTLSRecordStage: Received Bad Packet. Internal Error, contact mdw@cs.berkeley.edu");
					}
				}
			}

		}
	}

	public void handleEvents(List events) {
		for (int i = 0; i < events.size(); i++) {
			handleEvent((IElement) events.get(i));
		}
	}

	/**
	 * Helper function to deal with alert messages. Calls PureTLS to decode the alert message and to handle is
	 * appropriately in SSLRecordReader.readRecord().
	 */
	private void alertMessage(ATLSConnection atlsconn, ATLSRecord record) {
		if (DEBUG) System.err.println("aTLSRecordStage: received an alert message.");

		ByteArrayInputStream bais = new ByteArrayInputStream(record.data);
		atlsconn.conn.sock_in = new PushbackInputStream(bais);

		try {
			atlsconn.rr.readRecord();
		} catch (Exception e) {
			System.err.println("aTLSRecordStage: Exception trying to readrecord " + e);
			try {
				atlsconn.getConnection().close(mySink);
			} catch (SinkClosedException sce) {
				System.err.println("aTLSRecordStage: Exception trying to close the connection " + sce);
			}
		}
	}

	/**
	 * Helper function that posts handshake messages to the handshake stage.
	 * If the server is attemptiing to resume a session, then handleResume is called.
	 */
	private void handshakeMessage(ATLSConnection atlsconn, ATLSRecord record) {
		if (DEBUG) System.err.println("aTLSREcordStage: received a handshake message.");

		if ((atlsconn.isServer) && ((SSLHandshakeServer) atlsconn.conn.hs).resume) {
			handleResume(atlsconn, record);
		} else {
			if (PROFILE) {
				atlsconn.prof.addMeasurements(System.currentTimeMillis(), "aTLSRecordStage sending handshake message to handshake stage");
			}

			handshakeSink.enqueueLossy(new ATLSHandshakePacket(atlsconn, (ATLSHandshakeRecord) record));
		}
	}

	/**
	 * Helper function that does the same things has handshake message, but needs to differentiate
	 * between a cipher spec message and a regular handshake message because must be handled differently.
	 */
	private void changeCipherSpecMessage(ATLSConnection atlsconn, ATLSRecord record) {
		if (DEBUG) System.err.println("aTLSRecordStage: dealing with a changeCipherSpec Message");

		if ((atlsconn.isServer) && ((SSLHandshakeServer) atlsconn.conn.hs).resume) {
			handleResume(atlsconn, record);
		} else {
			handshakeSink.enqueueLossy(new ATLSCipherSpecPacket(atlsconn, (ATLSCipherSpecRecord) record));
		}
	}

	/**
	 * This function will decrypt the application data record by calling PureTLS, and will then
	 * pass the decrypted record back to the user. But if startReader for the connection has not been called,
	 * then must enqueue onto the dataQueue rather than post to the user sink. The dataSinkLock needed to
	 * synchronize actions with aTLSConnection.
	 */
	private void dataMessage(ATLSConnection atlsconn, ATLSRecord record) {
		ByteArrayInputStream bais = new ByteArrayInputStream(record.data);
		atlsconn.conn.sock_in = new PushbackInputStream(bais);

		try {
			atlsconn.rr.readRecord();
		} catch (Exception e) {
			System.err.println("aTLSRecordStage: Exception trying to readrecord " + e + " ");
			byte[] temp = atlsconn.conn.hs.session_id;
			for (int i = 0; i < temp.length; i++) {
				System.err.print(temp[i] + " ");
			}

			e.printStackTrace();
			try {
				atlsconn.getConnection().close(mySink);
			} catch (SinkClosedException sce) {
				System.err.println("aTLSRecordStage: Exception trying to close the connection " + sce);
			}
		}
// after read record is called, all the data resides on the sock_in_data stream of PureTLS.
// so need to read all the data off of that stream and pass it to the user.
		int length = ((ATLSAppDataRecord) record).length;
		byte[] data = new byte[length];

		try {
			atlsconn.conn.sock_in_data.read(data, 0, length);
		} catch (Exception e) {
			System.err.println("aTLSRecordStage: Exception trying to read data from sock_in_data");
			try {
				atlsconn.getConnection().close(mySink);
			} catch (SinkClosedException sce) {
				System.err.println("aTLSRecordStage: Exception trying to close the connection " + sce);
			}
		}

		record.data = data;

// if startReader hasn't been called, then no dataSink has been assigned, so just enqueue onto
// the temporary queue until user has called startReader().
		synchronized (atlsconn.dataSinkLock) {
			if (atlsconn.getDataSink() != null) {
				atlsconn.getDataSink().enqueueLossy(new ATcpInPacket(atlsconn, new BufferElement(record.data)));
			} else {
				atlsconn.dataQueue.add(record);
			}
		}
	}

	/**
	 * PureTLS will decide if a connection can be resumed once the clientHello has been received. If
	 * the sessionID is valid, then will set the boolean resume to true in SSLHandshakeServer. So
	 * a resumed session does not need to go through the same handshake process, so no need to
	 * enqueue anything to handshake stage. Best way is to handle it right here because only a few steps,
	 * and decryption can occur here too, rather than in the handshake stage.
	 * For a resume, only need a change cipher spec record and a finished record.
	 */
	private void handleResume(ATLSConnection atlsconn, ATLSRecord record) {
		if (DEBUG) System.err.println("aTLSRecordStage: Server is going to resume session with client");

		ByteArrayInputStream bais = new ByteArrayInputStream(record.data);
		atlsconn.conn.sock_in = new PushbackInputStream(bais);

		try {
			atlsconn.rr.readRecord();
		} catch (Exception e) {
			System.err.println("aTLSRecordStage: Exception trying to readrecord " + e);
			try {
				atlsconn.getConnection().close(mySink);
			} catch (SinkClosedException sce) {
				System.err.println("aTLSRecordStage: Exception trying to close the connection " + sce);
			}
		}

		if (record instanceof ATLSCipherSpecRecord) {
			return;
			// because readRecord already handled it for us.
		} else if (record instanceof ATLSHandshakeRecord) {
			// then it better have been a finished message!
			try {
				if (DEBUG) System.err.println("aTLSRecordStage: Calling handshakecontinue()");
				atlsconn.conn.hs.handshakeContinue();
			} catch (Exception e) {
				System.err.println("aTLSRecordStage: Exception in handshakeContinue " + e);
				try {
					atlsconn.getConnection().close(mySink);
				} catch (SinkClosedException sce) {
					System.err.println("aTLSRecordStage: Exception trying to close the connection " + sce);
				}
			}

			if (atlsconn.conn.hs.state == SSL_HANDSHAKE_FINISHED) {
				atlsconn.conn.sock_out_external = new SSLOutputStream(atlsconn.conn);
				// creating the output stream for the data

				// pass the atlsconn back up to the user who created the serversocket
				atlsconn.getNewConnSink().enqueueLossy(atlsconn);
			} else {
				System.err.println("aTLSRecordStage: Error, trying to resume in bad state with bad packet." +
				    " Internal error, contact mdw@cs.berkeley.edu");
			}
		} else {
			System.err.println("aTLSRecordStage: Internal Error, contact mdw@cs.berkeley.edu");
		}
	}

	/**
	 * This function cleans up the state for a closed connection. Necessary to remove from
	 * hash tables to avoid memory leaks.
	 * <p/>
	 * NOTE: if the server receives a sink closed event, should not remove itself from the
	 * hash tables. The sinkclosedevent is simply used to notify server of a closed connection,
	 * no need to clean up state because needs to remain intact.
	 */
	void cleanupConnection(ATLSConnection atlsconn) {
		if (DEBUG) System.err.println("aTLSRecordStage: Closing and cleaning up connection.");
// have to clean up two hashtables
		ATcpConnection atcpconn = atlsconn.getConnection();
		connTable.remove(atcpconn);

		if (!(atlsconn.isServer))
			clientSocketTable.remove(atcpconn.getClientSocket());
	}

	/**
	 * Return mySink
	 */
	ISink getSink() {
		return mySink;
	}

	/**
	 * The Sandstorm stage destroy method.
	 */
	public void destroy() {
	}
}

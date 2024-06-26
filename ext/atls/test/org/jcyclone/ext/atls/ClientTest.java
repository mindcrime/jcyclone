package org.jcyclone.ext.atls;

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


import org.jcyclone.core.cfg.IConfigData;
import org.jcyclone.core.event.BufferElement;
import org.jcyclone.core.handler.IEventHandler;
import org.jcyclone.core.queue.IElement;
import org.jcyclone.core.queue.ISink;
import org.jcyclone.core.queue.SinkClosedEvent;
import org.jcyclone.ext.asocket.ATcpInPacket;

import java.util.List;

/**
 * Simulates a client using the aTLS library, making a secure connection with a server.
 * Allows the option of resuming, but can only resume once an aTLSConnection has been received,
 * indicating that the handshake has been completed, and the session has actually been
 * stored by pureTLS.
 */
public class ClientTest implements IEventHandler, ATLSConst {

	private static final boolean DEBUG = true;
	private static final boolean DEBUGTEST = false;
	private static final int PORT = 8096;

	private ISink mysink;
	private IConfigData config;
	ATLSClientSocket client;

	int i = 0;
	int total = 0;

	public void init(IConfigData config) throws Exception {
		if (DEBUG) System.err.println("clientTest: inside init()");

		mysink = config.getStage().getSink();
		this.config = config;
		System.err.println("clientTest: Started");

// the first connection, I can't resume, so just pass null to indicate no resuming
		client = new ATLSClientSocket("localhost", PORT, mysink);
//  for (int i = 0; i < 25; i++) {

//  	    client = new aTLSClientSocket ("localhost", PORT, mysink);
//  	}
//aTLSClientSocket client = new aTLSClientSocket ("localhost", 4433, mysink);
//aTLSClientSocket client = new aTLSClientSocket ("www.amazon.com", 443, mysink);
//aTLSClientSocket client = new aTLSClientSocket ("www.verisign.com", 443, mysink);
//aTLSClientSocket client = new aTLSClientSocket ("banking.uboc.com", 443, mysink);
	}

	public void destroy() {
	}

	public void handleEvent(IElement item) {
		if (DEBUG) System.err.println("CLIENT TEST GOT QEL: " + item);

		if (item instanceof ATLSConnection) {
			if (DEBUG) System.err.println("clientTest: received a connection.");
			// start the reader
			((ATLSConnection) item).startReader(mysink);
			sendRequest((ATLSConnection) item);
		} else if (item instanceof ATcpInPacket) {
			handleResponse((ATcpInPacket) item);
		} else if (item instanceof SinkClosedEvent) {
			if (DEBUG) System.err.println("clientTest: CONNECTION HAS BEEN CLOSED");
			closeConnection();
		}
	}

	public void handleEvents(List items) {
		if (DEBUG) System.err.println("GOT " + items.size() + " ELEMENTS");
		for (int i = 0; i < items.size(); i++) {
			handleEvent((IElement) items.get(i));
		}
	}

	void closeConnection() {
// if the user gets a closed, that means the server closed, and that means this client is no longer needed
// so close this sink

	}

	// This is just to test the encryption/decryption of sending actual data between client/server
	public void sendRequest(ATLSConnection atlsconn) {
		String request = "GET / HTTP/1.0\r\n\r\n\r\n";
		byte[] buf = request.getBytes();

		atlsconn.enqueueLossy(new BufferElement(buf));

//  try {
//  	    atlsconn.close(atlsconn.recordStageSink);
//  	}
//  	catch (Exception e) {
//  	    System.err.println ("ClientTest: doesn't matter, just a stest");
//  	}
	}

	public void handleResponse(ATcpInPacket resp) {
		byte[] data = resp.getBytes();
		String response = new String(data);
		System.err.println("Client just received a response: \n" + response);

		if (DEBUGTEST) {
			if (total != 10) {
				if (i == 0) {
					i++;
				} else {
					for (int j = 0; j < 6; j++) {
						System.err.println();
					}
					try {
						System.err.println("clientTEST: CREATING another client!");
						ATLSClientSocket client1 = new ATLSClientSocket("localhost", PORT, mysink, client.getSessionID());
						//aTLSClientSocket client1 = new aTLSClientSocket ("localhost", PORT, mysink);
						client = client1;
					} catch (Exception e) {
						System.err.println("client test who cares");
					}
					i = 0;
				}
				total++;
			}
		}
	}
}




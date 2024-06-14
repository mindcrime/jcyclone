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

package org.jcyclone.ext.asocket;

import org.jcyclone.core.queue.IElement;
import org.jcyclone.core.queue.ISink;

import java.net.InetAddress;

/**
 * Request to establish a connection.
 */
public class ATcpConnectRequest extends ASocketRequest implements IElement {

	public ATcpClientSocket clisock;
	public ISink compQ;
	public InetAddress addr;
	public int port;
	public int writeClogThreshold;
	public int connectClogTries;

	public ATcpConnectRequest(ATcpClientSocket clisock, InetAddress addr, int port,
	                          ISink compQ, int writeClogThreshold, int connectClogTries) {
		this.clisock = clisock;
		this.compQ = compQ;
		this.addr = addr;
		this.port = port;
		this.writeClogThreshold = writeClogThreshold;
		this.connectClogTries = connectClogTries;
	}

}

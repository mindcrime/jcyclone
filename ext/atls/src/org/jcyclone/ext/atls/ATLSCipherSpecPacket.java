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

import org.jcyclone.core.queue.IElement;
import org.jcyclone.ext.atls.protocol.ATLSCipherSpecRecord;

/**
 * An aTLSChangeCipherSpecPacket is passed to the handshake stage from the
 * record stage to indicate that a change cipher spec handshake message was
 * just received. This type of packet is necessary to differentiate
 * from a normal handshake packet (aTLSHandshakePacket) because a
 * change cipher spec message must be dealt with differently.
 */

class ATLSCipherSpecPacket implements IElement {
	private ATLSConnection atlsconn;
	private ATLSCipherSpecRecord record;

	ATLSCipherSpecPacket(ATLSConnection atlsconn, ATLSCipherSpecRecord record) {
		this.atlsconn = atlsconn;
		this.record = record;
	}

	/**
	 * Returns the aTLSConnection that this packet was received on.
	 */
	ATLSConnection getConnection() {
		return atlsconn;
	}

	/**
	 * Returns the record that was actually received.
	 */
	ATLSCipherSpecRecord getRecord() {
		return record;
	}
} 
    

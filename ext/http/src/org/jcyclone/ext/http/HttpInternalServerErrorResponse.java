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

package org.jcyclone.ext.http;

import org.jcyclone.core.event.BufferElement;
import org.jcyclone.core.queue.IElement;

/**
 * An httpResponse corresponding to a '500 Internal Server Error'
 * Use httpNotFoundResponse for a '404 Not Found'.
 *
 * @author Matt Welsh
 * @see HttpNotFoundResponse
 */
public class HttpInternalServerErrorResponse extends HttpResponse implements HttpConst, IElement {

	private static final boolean DEBUG = false;

	public HttpInternalServerErrorResponse(HttpRequest request, String reason) {
		super(HttpResponse.RESPONSE_INTERNAL_SERVER_ERROR, "text/html");

		String str = "<html><head><title>500 Internal Server Error</title></head><body bgcolor=white><font face=\"helvetica\"><big><big><b>500 Internal Server Error</b></big></big><p>The URL you requested:<p><blockquote><tt>" + request.getURL() + "</tt></blockquote><p>generated an internal server error. The reason given by the server was:<p><blockquote><tt>" + reason + "</tt></blockquote></body></html>\n";
		BufferElement mypayload = new BufferElement(str.getBytes());
		setPayload(mypayload);
	}

	protected String getEntityHeader() {
		return null;
	}
}

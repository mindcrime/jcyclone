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

package org.jcyclone.haboob.http;

import org.jcyclone.core.cfg.IConfigData;
import org.jcyclone.core.event.BufferElement;
import org.jcyclone.core.handler.IEventHandler;
import org.jcyclone.core.profiler.JCycloneProfiler;
import org.jcyclone.core.queue.IElement;
import org.jcyclone.core.queue.ISink;
import org.jcyclone.core.queue.SinkClosedEvent;
import org.jcyclone.core.queue.SinkClosedException;
import org.jcyclone.core.stage.IStageManager;
import org.jcyclone.core.stage.NoSuchStageException;
import org.jcyclone.core.timer.ITimer;
import org.jcyclone.core.timer.JCycloneTimer;
import org.jcyclone.ext.http.*;
import org.jcyclone.haboob.HaboobConst;
import org.jcyclone.haboob.HaboobStats;
import org.jcyclone.haboob.hdapi.DynamicHttp;
import org.jcyclone.util.Util;

import java.util.List;

/**
 * This stage is responsible for accepting new HTTP requests and forwarding
 * them to the page cache (or else, responding with a dynamically-generated
 * page for statistics gathering).
 */
public class HttpRecv implements IEventHandler, HaboobConst {

	private static final boolean DEBUG = false;
	private static final boolean VERBOSE = false;

	// If true, enable ATLS support
	private static final boolean USE_ATLS = false;

	private static final long TIMER_DELAY = 2000;
	private int HTTP_PORT, HTTP_SECURE_PORT;
	private HttpServer server, secureServer;
	private IStageManager mgr;
	private ISink mysink, cacheSink, bottleneckSink, sendSink, dynSink;
	private int maxConns, maxSimReqs, numConns = 0, numSimReqs = 0;
	private int lastNumConns = 0;
	private String SPECIAL_URL;
	private String BOTTLENECK_URL;
	private ITimer timer;

	// Empty class representing timer event
	class timerEvent implements IElement {
	}

	public HttpRecv() {
		if (HaboobStats.httpRecv != null) {
			throw new Error("HttpRecv: More than one HttpRecv running?");
		}
		HaboobStats.httpRecv = this;
	}

	public void init(IConfigData config) throws Exception {
		mysink = config.getStage().getSink();
		this.mgr = config.getManager();
		cacheSink = mgr.getStage(CACHE_STAGE).getSink();
		sendSink = mgr.getStage(HTTP_SEND_STAGE).getSink();

		// These are optional
		try {
			dynSink = mgr.getStage(DYNAMIC_HTTP_STAGE).getSink();
		} catch (NoSuchStageException nsse) {
			dynSink = null;
		}

		try {
			bottleneckSink = mgr.getStage(BOTTLENECK_STAGE).getSink();
		} catch (NoSuchStageException nsse) {
			bottleneckSink = null;
		}

		timer = new JCycloneTimer();
		timer.registerEvent(TIMER_DELAY, new timerEvent(), mysink);

		SPECIAL_URL = config.getString("specialURL");
		if (SPECIAL_URL == null) throw new IllegalArgumentException("Must specify specialURL");
		BOTTLENECK_URL = config.getString("bottleneckURL");

		String serverName = config.getString("serverName");
		if (serverName != null) HttpResponse.setDefaultHeader("Server: " + serverName + HttpConst.CRLF);
		HTTP_PORT = config.getInt("httpPort");

		if (USE_ATLS == false) {
			if (HTTP_PORT == -1) {
				throw new IllegalArgumentException("Must specify httpPort");
			}
		}
		HTTP_SECURE_PORT = config.getInt("httpSecurePort");
		if (USE_ATLS == true) {
			if ((HTTP_PORT == -1) && (HTTP_SECURE_PORT == -1)) {
				throw new IllegalArgumentException("Must specify either httpPort or httpSecurePort");
			}
		}

		maxConns = config.getInt("maxConnections");
		maxSimReqs = config.getInt("maxSimultaneousRequests");
		System.err.println("HttpRecv: Starting, maxConns=" + maxConns + ", maxSimReqs=" + maxSimReqs);

		if (HTTP_PORT != -1) {
			server = new HttpServer(mgr, mysink, HTTP_PORT);
		}

		/* Uncomment the following lines if you want to enable SSL/TLS support */
		/* if (USE_ATLS && (HTTP_SECURE_PORT != -1)) {
		 *   secureServer = new seda.sandstorm.lib.atls.http.httpSecureServer(mgr, mysink, HTTP_SECURE_PORT);
		 *
		 * }
		 */

	}

	public void destroy() {
	}

	public void handleEvent(IElement item) {
		if (DEBUG) System.err.println("HttpRecv: GOT QEL: " + item);

		if (item instanceof HttpConnection) {
			HaboobStats.numConnectionsEstablished++;
			numConns++;
			if (VERBOSE) System.err.println("HttpRecv: Got connection " + (HaboobStats.numConnectionsEstablished - HaboobStats.numConnectionsClosed));

			if ((maxConns != -1) && (numConns == maxConns)) {
				System.err.println("Suspending accept() after " + numConns + " connections");
				server.suspendAccept();
			}

		} else if (item instanceof HttpRequest) {
			if (DEBUG) System.err.println("HttpRecv: Got request " + item);

			HttpRequest req = (HttpRequest) item;

			// Record time for controller
			req.timestamp = System.currentTimeMillis();

			// Check for special URL
			if (DEBUG) System.err.println("HttpRecv: URL is [" + req.getURL() + "]");
			if (req.getURL().startsWith(SPECIAL_URL)) {
				if (DEBUG) System.err.println("HttpRecv: Doing special");
				doSpecial(req);
				return;
			}

			// Check for bottleneck URL
			if ((bottleneckSink != null) && (BOTTLENECK_URL != null) && (req.getURL().startsWith(BOTTLENECK_URL))) {
				if (!bottleneckSink.enqueueLossy(req)) {
					//System.err.println("HttpRecv: Warning: Could not enqueue_lossy to bottleneck stage: "+item);
					// Send not available response
					HttpSend.sendResponse(new HttpResponder(new HttpServiceUnavailableResponse(req, "Bottleneck stage is busy!"), req, true));
				}
				return;
			}

			// Check for dynamic URLs
			if (dynSink != null) {
				try {
					if (DynamicHttp.handleRequest(req)) return;
				} catch (Exception e) {
					// Send not available response
					//System.err.println("*************** Haboob: Could not enqueue request to HDAPI: "+e);
					HttpSend.sendResponse(new HttpResponder(new HttpServiceUnavailableResponse(req, "Could not enqueue request to HDAPI [" + req.getURL() + "]: " + e), req, true));
					return;
				}
			}

			// Threshold maximum number of in-flight requests
			if (maxSimReqs != -1) {
				synchronized (this) {
					numSimReqs++;
					while (numSimReqs >= maxSimReqs) {
						try {
							this.wait();
						} catch (InterruptedException ie) {
							// Ignore
						}
					}
				}
			}

			if (DEBUG) System.err.println("HttpRecv: Sending to cacheSink");
			if (!cacheSink.enqueueLossy(item)) {
				System.err.println("HttpRecv: Warning: Could not enqueue_lossy " + item);
			}

		} else if (item instanceof SinkClosedEvent) {
			// Connection closed by remote peer
			if (DEBUG) System.err.println("HttpRecv: Closed connection " + item);
			HaboobStats.numConnectionsClosed++;

			numConns--;
			if ((maxConns != -1) && (numConns == maxConns - 1)) {
				System.err.println("Resuming accept() for " + numConns + " connections");
				server.resumeAccept();
			}
			cacheSink.enqueueLossy(item);

			if (VERBOSE) System.err.println("HttpRecv: Closed connection " + (HaboobStats.numConnectionsEstablished - HaboobStats.numConnectionsClosed));

		} else if (item instanceof timerEvent) {

			int nc = (HaboobStats.numConnectionsEstablished - HaboobStats.numConnectionsClosed);
			if (nc != lastNumConns) {
				System.err.println("Haboob: " + (HaboobStats.numConnectionsEstablished - HaboobStats.numConnectionsClosed) + " active connections");
			}
			lastNumConns = nc;
			timer.registerEvent(TIMER_DELAY, item, mysink);

		} else {
			if (DEBUG) System.err.println("HttpRecv: Got unknown event type: " + item);
		}

	}

	public void handleEvents(List events) {
		for (int i = 0; i < events.size(); i++) {
			handleEvent((IElement) events.get(i));
		}
	}

	// Indicate that we are done with a request; used by HttpSend
	void doneWithReq() {
		if (maxSimReqs == -1) return;

		synchronized (this) {
			numSimReqs--;
			if (numSimReqs < maxSimReqs) this.notify();
		}
	}

	// Close the given connection; used by HttpSend
	void closeConnection(HttpConnection conn) {
		HaboobStats.numConnectionsClosed++;
		numConns--;
		try {
			conn.close(cacheSink);
		} catch (SinkClosedException sce) {
			if (DEBUG) System.err.println("Warning: Tried to close connection " + conn + " multiple times");
		}
		if ((maxConns != -1) && (numConns == maxConns - 1)) {
			System.err.println("Resuming accept() for " + numConns + " connections");
			server.resumeAccept();
		}
	}

	private void doSpecial(HttpRequest req) {
		double pct;

		if (req.getURL().endsWith("?graph")) {
			((JCycloneProfiler) mgr.getProfiler()).getGraphProfiler().dumpGraph();
		}

		String repl = "<html><head><title>Haboob Web Server Admin Page</title></head><body bgcolor=white><font face=helvetica><h2>Haboob Admin Page</h2>\n";

		if (req.getURL().endsWith("?graph")) {
			((JCycloneProfiler) mgr.getProfiler()).getGraphProfiler().dumpGraph();
			repl += "<p><b><font color=red>Graph dumped.</font></b>";
		}

		repl += "<p><b>Server Statistics</b>\n";
		Runtime r = Runtime.getRuntime();
		double totalmemkb = r.totalMemory() / 1024.0;
		double freememkb = r.freeMemory() / 1024.0;
		repl += "<br>Total memory in use: " + Util.format(totalmemkb) + " KBytes\n";
		repl += "<br>Free memory: " + Util.format(freememkb) + " KBytes\n";

		repl += "<p><b>HTTP Request Statistics</b>\n";
		repl += "<br>Total requests: " + HaboobStats.numRequests + "\n";
		pct = (HaboobStats.numErrors * 100.0 / HaboobStats.numRequests);
		repl += "<br>Errors: " + HaboobStats.numErrors + " (" + Util.format(pct) + "%)\n";

		repl += "\n<p><b>Cache Statistics</b>\n";
		double cacheSizeKb = HaboobStats.cacheSizeBytes / 1024.0;
		repl += "<br>Current size of page cache: " + HaboobStats.cacheSizeEntries + " files, " + Util.format(cacheSizeKb) + " KBytes\n";
		pct = (HaboobStats.numCacheHits * 100.0 / HaboobStats.numRequests);
		repl += "<br>Cache hits: " + HaboobStats.numCacheHits + " (" + Util.format(pct) + "%)\n";
		pct = (HaboobStats.numCacheMisses * 100.0 / HaboobStats.numRequests);
		repl += "<br>Cache misses: " + HaboobStats.numCacheMisses + " (" + Util.format(pct) + "%)\n";

		repl += "\n<p><b>Connection Statistics</b>\n";
		int numconns = HaboobStats.numConnectionsEstablished - HaboobStats.numConnectionsClosed;
		repl += "<br>Number of connections: " + numconns + "\n";
		repl += "<br>Total connections: " + HaboobStats.numConnectionsEstablished + "\n";

		repl += "\n<p><b>Profiling Information</b>\n";
		double cacheLookupTime = 0, cacheAllocateTime = 0, cacheRejectTime = 0,
		    fileReadTime = 0;
		if (HaboobStats.numCacheLookup != 0)
			cacheLookupTime = (HaboobStats.timeCacheLookup * 1.0) / HaboobStats.numCacheLookup;
		if (HaboobStats.numCacheAllocate != 0)
			cacheAllocateTime = (HaboobStats.timeCacheAllocate * 1.0) / HaboobStats.numCacheAllocate;
		if (HaboobStats.numCacheReject != 0)
			cacheRejectTime = (HaboobStats.timeCacheReject * 1.0) / HaboobStats.numCacheReject;
		if (HaboobStats.numFileRead != 0)
			fileReadTime = (HaboobStats.timeFileRead * 1.0) / HaboobStats.numFileRead;
		repl += "<br>Cache lookup time: " + Util.format(cacheLookupTime) + " ms avg (" + HaboobStats.timeCacheLookup + " total, " + HaboobStats.numCacheLookup + " times)\n";
		repl += "<br>Cache allocate time: " + Util.format(cacheAllocateTime) + " ms avg (" + HaboobStats.timeCacheAllocate + " total, " + HaboobStats.numCacheAllocate + " times)\n";
		repl += "<br>Cache reject time: " + Util.format(cacheRejectTime) + " ms avg (" + HaboobStats.timeCacheReject + " total, " + HaboobStats.numCacheReject + " times)\n";
		repl += "<br>File read time: " + Util.format(fileReadTime) + " ms avg (" + HaboobStats.timeFileRead + " total, " + HaboobStats.numFileRead + " times)\n";

		repl += "<p></font></body></html>" + HttpConst.CRLF;

		HttpOKResponse resp = new HttpOKResponse("text/html", new BufferElement(repl.getBytes()));
		HttpSend.sendResponse(new HttpResponder(resp, req, true));

	}

}


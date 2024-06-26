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

package org.jcyclone.core.cfg;

import org.jcyclone.core.stage.StageNameAlreadyBoundException;

import java.io.*;
import java.util.*;

/**
 * This class is used to pass configuration parameters into JCyclone
 * at startup time. It reads initial configuration parameters from a
 * file, using an XML-like format. Various operations can be performed
 * upon this class to modify the configuration of the JCyclone runtime.
 *
 * @author Matt Welsh and Jean Morissette
 * @see org.jcyclone.core.boot.JCyclone
 * @see org.jcyclone.core.boot.Main
 */
public class JCycloneConfig implements Cloneable, IMutableSystemConfig {

	// XXX JM: all config values are stored as string.
	// It would be more memory efficient to store them as their real type.
	// By exemple, it's better to do put(new Integer(..)) instead of
	// put(Integer.toString(..))


	static protected final boolean DEBUG = false;
	private static final String[] EMPTY_STRING_ARRAY = new String[0];
	protected static final String DELIM_CHAR = ".";
	public static final String LIST_ELEMENT_DELIMITER = " ";


	protected ConfigSection root;

	/**
	 * Value for defaultThreadMgr to use the thread-per-CPU thread manager.
	 */
	public static final String THREADMGR_TPPTM = "TPPTM";
	/**
	 * Value for defaultThreadMgr to use the thread-per-stage thread manager.
	 */
	public static final String THREADMGR_TPSTM = "TPSTM";
    /**
     * Value for defaultThreadMgr to use the thread-per-stage thread manager.
     */
    public static final String THREADMGR_TPSTM_CONCURRENT = "TPSTM-Concurrent";
	/**
	 * Value for defaultThreadMgr to use the aggregating TPSTM.
	 */
	public static final String THREADMGR_AggTPSTM = "AggTPSTM";

	/**
	 * String value for setting boolean configuration entries to true.
	 */
	public static final String CONFIG_TRUE = "true";
	/**
	 * String value for setting boolean configuration entries to false.
	 */
	public static final String CONFIG_FALSE = "false";

	/**
	 * The set of default values for the JCyclone configuration.
	 * In order to modify the default configuration used by JCyclone,
	 * edit JCycloneConfig.java and recompile.
	 */
	public static final String[] defaults = {
		"global.defaultThreadManager", THREADMGR_TPSTM,

		"global.threadPool.initialThreads", "1",
		"global.threadPool.minThreads", "1",
		"global.threadPool.maxThreads", "20",
		"global.threadPool.blockTime", "1000",
		"global.threadPool.sizeController.enable", CONFIG_FALSE,
		"global.threadPool.sizeController.delay", "2000",
		"global.threadPool.sizeController.threshold", "1000",
		"global.threadPool.sizeController.idleTimeThreshold", "1000",

		"global.batchController.enable", CONFIG_FALSE,
		"global.batchController.minBatch", "1",
		"global.batchController.maxBatch", "-1",

		"global.profile.enable", CONFIG_FALSE,
		"global.profile.delay", "1000",
		"global.profile.filename", "jcyclone-profile.txt",
		"global.profile.sockets", CONFIG_FALSE,
		"global.profile.graph", CONFIG_FALSE,
		"global.profile.graphfilename", "jcyclone-graph.txt",

		/* Deprecated */
		"global.AggTPSTM.governor.enable", CONFIG_FALSE,
		"global.AggTPSTM.governor.delay", "2000",
		"global.AggTPSTM.governor.threshold", "1000",

		/* Deprecated */
		"global.TPPTM.numCpus", "1",
		"global.TPPTM.maxThreads", "1",

		"global.aSocket.enable", CONFIG_TRUE,
		"global.aSocket.rateController.enable", CONFIG_FALSE,
		"global.aSocket.rateController.rate", "100000.0",

		"global.aDisk.enable", CONFIG_FALSE,
		"global.aDisk.threadPool.initialThreads", "1",
		"global.aDisk.threadPool.minThreads", "1",
		"global.aDisk.threadPool.maxThreads", "20",
		"global.aDisk.threadPool.sizeController.enable", CONFIG_TRUE,
		"global.aDisk.threadPool.sizeController.delay", "2000",
		"global.aDisk.threadPool.sizeController.threshold", "20",
	};

	protected Hashtable cmdLineArgs;

	/**
	 * Default initialization arguments passed to every stage.
	 */
	public Hashtable defaultInitArgs;

	/**
	 * Create a new JCycloneConfig with the default settings.
	 */
	public JCycloneConfig() {
		root = new ConfigSection("jcyclone");

		// Set default values
		for (int i = 0; i < defaults.length; i += 2) {
			String key = defaults[i];
			String val = defaults[i + 1];
			if (getString(key) == null) {
				putString(key, val);
			}
		}
	}

	/**
	 * Create a new JCycloneConfig with the default settings, with
	 * the given default init args, which will be passed to every stage.
	 * Each element of defaultArgs[] is a String in the format
	 * "key=value". If "key" contains a dot ("."), then it will be
	 * treated as a key to be added to the JCyclone configuration's
	 * global parameters. Otherwise, the key-value pair will be added to
	 * the "initargs" section for each stage.
	 */
	public JCycloneConfig(String defaultArgs[]) throws IOException {
		this();
		if (defaultArgs != null) this.cmdLineArgs = stringArrayToHT(defaultArgs);
	}



	// Return the value associated with the given key in cs; recursive.
	private Object getVal(ConfigSection cs, String key) {
		String car, cdr;
		int c = key.indexOf(DELIM_CHAR);
		if (c == -1) {
			car = key;
			cdr = null;
		} else {
			car = key.substring(0, c);
			cdr = key.substring(c + 1, key.length());
		}
		if (DEBUG) System.err.println("getVal: cs=" + cs + " key=" + key + " car=" + car + ", cdr=" + cdr);

		if (cdr == null) {
			// OK, we are at a terminal node
			return cs.getVal(car);
		} else {
			// We are at an intermediate node
			ConfigSection subsec = cs.getSubsection(car);
			if (subsec == null)
				return null;
			else
				return getVal(subsec, cdr);
		}
	}

	// Set the given value in cs; recursive.
	private void putVal(ConfigSection cs, String key, Object val) {
		String car, cdr;
		int c = key.indexOf(DELIM_CHAR);
		if (c == -1) {
			car = key;
			cdr = null;
		} else {
			car = key.substring(0, c);
			cdr = key.substring(c + 1, key.length());
		}

		if (cdr == null) {
			// OK, we are at a terminal node
			cs.putVal(key, val);
			return;
		} else {
			// We are at an intermediate node
			ConfigSection subsec = cs.getSubsection(car);
			if (subsec == null) {
				subsec = new ConfigSection(car);
				cs.addSubsection(subsec);
			}
			putVal(subsec, cdr, val);
			return;
		}
	}

	/**
	 * Return the configuration option associated with the given key
	 * as a String. Returns null if not set.
	 */
	public String getString(String key) {
		return getString(key, null);
	}

	/**
	 * Return the string associated with the given key.
	 * Return null if not set or the value is not a string.
	 */
	String getStringVal(ConfigSection cs, String key) {
		String val = null;
		try {
			val = (String) getVal(cs, key);
		} catch (ClassCastException cce) {
		}
		return val;
	}

	/**
	 * Return the configuration option associated with the given key
	 * as a String. Returns default if not set.
	 */
	public String getString(String key, String defaultval) {
		String val = getStringVal(root, key);
		if (val == null)
			return defaultval;
		else
			return val;
	}

	public void putString(String key, String val) {
		putVal(root, key, val);
	}

	/**
	 * Return the configuration option associated with the given key
	 * as a boolean. Returns false if not set.
	 */
	public boolean getBoolean(String key) {
		return getBoolean(key, false);
	}

	/**
	 * Return the configuration option associated with the given key
	 * as a boolean. Returns default if not set.
	 */
	public boolean getBoolean(String key, boolean defaultval) {
		String val = getStringVal(root, key);
		if (val == null) return defaultval;
		return (val.equalsIgnoreCase(CONFIG_TRUE));
	}

	public void putBoolean(String key, boolean val) {
		putVal(root, key, (val) ? (CONFIG_TRUE) : (CONFIG_FALSE));
	}

	/**
	 * Return the configuration option associated with the given key
	 * as an int. Returns -1 if not set or if the value of the key cannot
	 * be expressed as an int.
	 */
	public int getInt(String key) {
		return getInt(key, -1);
	}

	/**
	 * Return the configuration option associated with the given key
	 * as an int. Returns default if not set or if the value of the
	 * key cannot be expressed as an int.
	 */
	public int getInt(String key, int defaultval) {
		String val = getStringVal(root, key);
		if (val == null) return defaultval;
		try {
			return Integer.parseInt(val);
		} catch (NumberFormatException nfe) {
			return defaultval;
		}
	}

	public long getLong(String key) {
		return getLong(key, -1);
	}

	public long getLong(String key, long defaultval) {
		String val = getStringVal(root, key);
		if (val == null) return defaultval;
		try {
			return Long.parseLong(val);
		} catch (NumberFormatException nfe) {
			return defaultval;
		}
	}

	public void putInt(String key, int val) {
		putVal(root, key, Integer.toString(val));
	}

	public void putLong(String key, long val) {
		putVal(root, key, Long.toString(val));
	}

	/**
	 * Return the configuration option associated with the given key
	 * as a double. Returns -1 if not set or if the value of the key cannot
	 * be expressed as a double.
	 */
	public double getDouble(String key) {
		return getDouble(key, -1);
	}

	/**
	 * Return the configuration option associated with the given key
	 * as a double. Returns default if not set or if the value of the
	 * key cannot be expressed as a double.
	 */
	public double getDouble(String key, double defaultval) {
		String val = getStringVal(root, key);
		if (val == null) return defaultval;
		try {
			return Double.parseDouble(val);
		} catch (NumberFormatException nfe) {
			return defaultval;
		}
	}

	/**
	 * Get the string list value corresponding to the given key.
	 * Returns null if not set.
	 */
	public String[] getStringList(String key) {
		String ret[];
		String val = (String) getVal(root, key);
		if (val == null) return null;
		StringTokenizer st = new StringTokenizer(val, JCycloneConfig.LIST_ELEMENT_DELIMITER);
		ret = new String[st.countTokens()];
		for (int i = 0; st.hasMoreElements(); i++) {
			ret[i] = (String) st.nextElement();
		}
		return ret;
	}

	public Object getObject(String key) {
		return getObject(key, null);
	}

	public Object getObject(String key, Object defaultval) {
		Object val = getVal(root, key);
		if (val == null) return defaultval;
		return val;
	}

	public void putDouble(String key, double val) {
		putVal(root, key, Double.toString(val));
	}

	public void putObject(String key, Object val) {
		putVal(root, key, val);
	}

	public void putStringList(String key, String valarr[]) {
		String s = "";
		for (int i = 0; i < valarr.length; i++) {
			s += valarr[i];
			if (i != valarr.length - 1) s += JCycloneConfig.LIST_ELEMENT_DELIMITER;
		}
		putVal(root, key, s);
	}

	// Return enumeration of keys matching prefix starting with cs.
	// Recursive.
	private Enumeration getKeys(ConfigSection cs, String prefix) {
		if (DEBUG)
			System.err.println("JCycloneConfig.getKeys: cs = " + cs +
			    "; prefix = " + prefix);
		// We are at the end of the prefix
		if (prefix == null) {
			Vector v = new Vector(1);
			Enumeration e = cs.getKeys();
			if (e != null) {
				while (e.hasMoreElements()) {
					v.addElement(e.nextElement());
				}
			}
			e = cs.getSubsections();
			if (e != null) {
				while (e.hasMoreElements()) {
					ConfigSection subsec = (ConfigSection) e.nextElement();
					v.addElement(subsec.getName() + DELIM_CHAR);
				}
			}
			return v.elements();
		}

		// First look for single item matching prefix
		int dlimx = prefix.indexOf(DELIM_CHAR);
		if (dlimx < 0) {
			Object val = cs.getVal(prefix);
			if (val != null) {
				Vector v = new Vector(1);
				v.addElement(prefix);
				return v.elements();
			}
		}
		// Look for subsection matching prefix
		String tok = dlimx < 0 ? prefix : prefix.substring(0, dlimx);
		ConfigSection subsec = cs.getSubsection(tok);
		if (subsec == null)
			return null;
		String tok2 = dlimx < 0 ? null : prefix.substring(dlimx + 1);
		return getKeys(subsec, tok2);
	}

	/**
	 * Return an enumeration of the keys matching the given prefix.
	 * A given key maps onto a set of child keys if it ends in a
	 * "." character (that is, it is an internal node within the tree).
	 * A key not ending in "." is a terminal node and maps onto a
	 * value that may be obtained using getString, getInt, or getDouble.
	 */
	public Enumeration getKeys(String prefix) {
		return getKeys(root, prefix);
	}

	/**
	 * Return an enumeration of the top-level keys in this configuration.
	 */
	public Enumeration getKeys() {
		return getKeys(root, null);
	}

	public ISystemConfig getCopy() {
		try {
			// XXX JM: do a deep copy?
			return (JCycloneConfig) (this.clone());
		} catch (CloneNotSupportedException e) {
			throw new Error("Internal error: JCycloneConfig must support clone!");
		}
	}

	/**
	 * Add a stage to this JCycloneConfig.
	 *
	 * @param stageName The name of the stage as it should be registered.
	 * @param className The fully-qualified class name of the stage event
	 *                  handler.
	 * @param initargs  The initial arguments to pass into the stage.
	 */
	public void addStage(String stageName, String className, String[] initargs)
	    throws StageNameAlreadyBoundException, IOException {
		String tag = "stages." + stageName;
		if (contains(tag)) {
			throw new StageNameAlreadyBoundException("Stage " + stageName + " already registered in JCycloneConfig");
		}

		putString(tag + ".class", className);

		Map args = stringArrayToHT(initargs);
		Iterator keys = args.keySet().iterator();
		while (keys.hasNext()) {
			String key = (String) keys.next();
			String value = (String) args.get(key);
			putString(tag + ".initargs." + key, value);
		}
	}

	public String[] getStageNames() {
		String[] names = EMPTY_STRING_ARRAY;
		ConfigSection stagesec = root.getSubsection("stages");
		if (stagesec != null) {
			int secCount = stagesec.subsections.size();
			names = new String[secCount];
			for (int i = 0; i < secCount; i++) {
				ConfigSection sec = (ConfigSection) stagesec.subsections.get(i);
				names[i] = sec.getName();
			}
		}
		return names;
	}

	public String[] getPluginNames() {
		String[] names = EMPTY_STRING_ARRAY;
		ConfigSection stagesec = root.getSubsection("plugins");
		if (stagesec != null) {
			int secCount = stagesec.subsections.size();
			names = new String[secCount];
			for (int i = 0; i < secCount; i++) {
				ConfigSection sec = (ConfigSection) stagesec.subsections.get(i);
				names[i] = sec.getName();
			}
		}
		return names;
	}

	public boolean contains(String key) {
		return getVal(root, key) != null;
	}

	// Convert an array of "key=value" strings to a Hashtable
	private Hashtable stringArrayToHT(String arr[]) throws IOException {
		if (arr == null) return null;
		Hashtable ht = new Hashtable(1);
		for (int i = 0; i < arr.length; i++) {
			StringTokenizer st = new StringTokenizer(arr[i], "=");
			String key;
			String val;
			try {
				key = st.nextToken();
				val = st.nextToken();
				while (st.hasMoreTokens()) val += "=" + st.nextToken();
			} catch (NoSuchElementException e) {
				throw new IOException("Could not convert string '" + arr[i] + "' to key=value pair");
			}
			ht.put(key, val);
		}
		return ht;
	}

	// Internal class to represent configuration file format
	static class ConfigSection {
		private String secname;
		private Vector subsections;
		private Hashtable vals;
		private ConfigSection parent;

		ConfigSection(String name) {
			this.secname = name;
			subsections = null;  // lazy loading
			vals = new Hashtable(1);    // TODO: lazy loading ?
		}

		String getName() {
			return secname;
		}

		ConfigSection getSubsection(String name) {
			if (subsections == null)
				return null;
			for (int i = 0; i < subsections.size(); i++) {
				ConfigSection sec = (ConfigSection) subsections.elementAt(i);
				if (sec.getName().equals(name)) return sec;
			}
			return null;
		}

		void addSubsection(ConfigSection newSubsec) {
			if (newSubsec == null) {
				throw new IllegalArgumentException("newSubsec is null");
			} else if (isSectionAncestor(newSubsec)) {
				throw new IllegalArgumentException("newSubsec is an ancestor");
			}
			ConfigSection oldParent = newSubsec.getParent();
			if (oldParent != null) {
				oldParent.remove(newSubsec);
			}
			newSubsec.setParent(this);
			if (subsections == null) {
				subsections = new Vector();
			}
			subsections.addElement(newSubsec);
		}

		boolean isSectionAncestor(ConfigSection anotherSec) {
			if (anotherSec == null) {
				return false;
			}
			ConfigSection ancestor = this;
			do {
				if (ancestor == anotherSec) {
					return true;
				}
			} while ((ancestor = ancestor.getParent()) != null);
			return false;
		}

		boolean isSubsection(ConfigSection aSec) {
			boolean retval;
			if (aSec == null) {
				retval = false;
			} else {
				if (getSubsectionCount() == 0) {
					retval = false;
				} else {
					retval = (aSec.getParent() == this);
				}
			}
			return retval;
		}

		int getSubsectionCount() {
			if (subsections == null) {
				return 0;
			} else {
				return subsections.size();
			}
		}

		String getConfigPath() {
			StringBuffer buf = new StringBuffer();
			ConfigSection[] path = getPath();
			int lastIndex = path.length - 1;
			for (int i = 0; i < lastIndex; i++) {
				buf.append(path[i].getName()).append('.');
			}
			buf.append(path[lastIndex].getName());
			return buf.toString();
		}

		ConfigSection[] getPath() {
			return getPathToRoot(this, 0);
		}

		ConfigSection[] getPathToRoot(ConfigSection aSec, int depth) {
			ConfigSection[] retSecs;
			/* Check for null, in case someone passed in a null node, or
			they passed in an element that isn't rooted at root. */
			if (aSec == null) {
				if (depth == 0)
					return null;
				else
					retSecs = new ConfigSection[depth];
			} else {
				depth++;
				retSecs = getPathToRoot(aSec.getParent(), depth);
				retSecs[retSecs.length - depth] = aSec;
			}
			return retSecs;
		}

		void remove(ConfigSection aSubsec) {
			if (aSubsec == null) {
				throw new IllegalArgumentException("argument is null");
			}
			if (!isSubsection(aSubsec)) {
				throw new IllegalArgumentException("argument is not a subsection");
			}
			subsections.remove(aSubsec);
		}

		void setParent(ConfigSection newParent) {
			this.parent = newParent;
		}

		ConfigSection getParent() {
			return parent;
		}

		// Return the object associated with key in this section
		// If not specified, null is returned
		Object getVal(String key) {
			return vals.get(key);
		}

		Enumeration getSubsections() {
			if (subsections == null) return null;
			return subsections.elements();
		}

		Enumeration getKeys() {
			return vals.keys();
		}

		int numKeys() {
			return vals.size();
		}

		Hashtable getVals() {
			return vals;
		}

		void putVal(String key, Object val) {
			vals.put(key, val);
		}

		// Debugging only
		void dump() {
			System.err.println("<" + secname + ">");
			Enumeration e = vals.keys();
			while (e.hasMoreElements()) {
				String key = (String) e.nextElement();
				String val = (String) vals.get(key);
				System.err.println("   " + key + " " + val);
			}

			for (int i = 0; i < subsections.size(); i++) {
				ConfigSection sec = (ConfigSection) subsections.elementAt(i);
				sec.dump();
			}
			System.err.println("</" + secname + ">");
		}

		public String toString() {
			return "configSection <" + secname + ">";
		}
	}


}

/*
 * Copyright (C) 2005, 2006 Aelitis, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 * AELITIS, SAS au capital de 46,603.30 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 * 
 */
package org.gudy.azureus2.core3.logging;

import java.io.PrintStream;

import org.gudy.azureus2.core3.logging.impl.FileLogging;
import org.gudy.azureus2.core3.logging.impl.LoggerImpl;
import org.gudy.azureus2.core3.util.Constants;
import org.gudy.azureus2.core3.util.Debug;

/**
 * A static implementation of the LoggerImpl class.
 * 
 * @note Currently, LoggerImpl and Logger could be combined, but they are split
 *        for future consideration (ie. allowing multiple LoggerImpl) 
 * 
 * @author TuxPaper
 * @since 2.3.0.7
 */
public class Logger {
	private static final LogIDs LOGID = LogIDs.LOGGER;

	private static LoggerImpl loggerImpl = null;

	private static FileLogging fileLogging = new FileLogging();

	static {
		try {
			loggerImpl = new LoggerImpl();
			loggerImpl.init();

			fileLogging.initialize();

			log(new LogEvent(LOGID, "**** Logging starts: "
					+ Constants.AZUREUS_VERSION + " ****"));

			log(new LogEvent(LOGID, "java.home=" + System.getProperty("java.home")));

			log(new LogEvent(LOGID, "java.version="
					+ System.getProperty("java.version")));

			log(new LogEvent(LOGID, "os=" + System.getProperty("os.arch") + "/"
					+ System.getProperty("os.name") + "/"
					+ System.getProperty("os.version")));

			log(new LogEvent(LOGID, "user.dir=" + System.getProperty("user.dir")));

			log(new LogEvent(LOGID, "user.home=" + System.getProperty("user.home")));
		} catch (Throwable t) {
			t.printStackTrace();
			Debug.out("Error initializing Logger", t);
			// loggerImpl will always be set, except for cases where there wasn't
			// enough memory. In that case, app will blork with null pointer exception
			// on first Logger.* call.  However, since there's not enough memory,
			// application will probably blork somewhere else in the code first. 
		}
	}

	/**
	 * Determines whether events are logged
	 * 
	 * @return true if events are logged
	 */
	public static boolean isEnabled() {
		return loggerImpl.isEnabled();
	}

	/**
	 * Log an event
	 * 
	 * @param event
	 *            event to log
	 */
	public static void log(LogEvent event) {
		loggerImpl.log(event);
	}

	public static void log(LogAlert alert) {
		loggerImpl.log(alert);
	}

	/**
	 * Log an event, loading text from out messagebundle. Fill event.text with
	 * resource id.
	 * 
	 * @param event
	 *            event to log
	 */
	public static void logTextResource(LogEvent event) {
		loggerImpl.logTextResource(event);
	}

	public static void logTextResource(LogEvent event, String params[]) {
		loggerImpl.logTextResource(event, params);
	}

	public static void logTextResource(LogAlert alert) {
		loggerImpl.logTextResource(alert);
	}

	public static void logTextResource(LogAlert alert, String params[]) {
		loggerImpl.logTextResource(alert, params);
	}

	/**
	 * Redirect stdout and stderr to Logger.
	 */
	public static void doRedirects() {
		loggerImpl.doRedirects();
	}

	/**
	 * Add a listener that's triggered when an event is logged.
	 * 
	 * @param aListener
	 *            Listener to call when an event is logged
	 */
	public static void addListener(ILoggerListener aListener) {
		loggerImpl.addListener(aListener);
	}

	/**
	 * Remove a previously added log listener
	 * 
	 * @param aListener
	 *            Listener to remove
	 */
	public static void removeListener(ILoggerListener aListener) {
		loggerImpl.removeListener(aListener);
	}

	/**
	 * Add a listener that's triggered when an event is logged.
	 * 
	 * @param aListener
	 *            Listener to call when an event is logged
	 */
	public static void addListener(ILogEventListener aListener) {
		loggerImpl.addListener(aListener);
	}

	/**
	 * Add a listener that's triggered when an alert is logged.
	 * 
	 * @param aListener
	 *            Listener to call when an alert is logged
	 */
	public static void addListener(LGAlertListener aListener) {
		loggerImpl.addListener(aListener);
	}

	/**
	 * Add a listener that's triggered when an alert is logged.
	 * 
	 * @param aListener
	 *            Listener to call when an alert is logged
	 */
	public static void addListener(ILogAlertListener aListener) {
		loggerImpl.addListener(aListener);
	}

	/**
	 * Remove a previously added log listener
	 * 
	 * @param aListener
	 *            Listener to remove
	 */
	public static void removeListener(ILogEventListener aListener) {
		loggerImpl.removeListener(aListener);
	}

	/**
	 * Remove a previously added log listener
	 * 
	 * @param aListener
	 *            Listener to remove
	 */
	public static void removeListener(LGAlertListener aListener) {
		loggerImpl.removeListener(aListener);
	}

	/**
	 * Remove a previously added log listener
	 * 
	 * @param aListener
	 *            Listener to remove
	 */
	public static void removeListener(ILogAlertListener aListener) {
		loggerImpl.removeListener(aListener);
	}

	/**
	 * Retrieve the original stderr output before we hooked it.  Handy for
	 * printing out critical errors that need to bypass the logger capture.
	 * 
	 * @return stderr
	 */
	public static PrintStream getOldStdErr() {
		return loggerImpl.getOldStdErr();
	}
}

package ebbex.eclipsemcpserver.util;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Tiny facade over the Eclipse platform log (the {@code ghidra.util.Msg} role).
 * Entries land in the workspace {@code .metadata/.log} and the Error Log view.
 * Falls back to stderr if the platform log is unreachable (e.g. very early startup).
 */
public final class Logs {

	private static final String PLUGIN_ID = "ebbex.eclipsemcpserver";

	private Logs() {
	}

	public static void info(String message) {
		log(IStatus.INFO, message, null);
	}

	public static void warn(String message) {
		log(IStatus.WARNING, message, null);
	}

	public static void error(String message, Throwable t) {
		log(IStatus.ERROR, message, t);
	}

	private static void log(int severity, String message, Throwable t) {
		try {
			Bundle bundle = FrameworkUtil.getBundle(Logs.class);
			ILog log = Platform.getLog(bundle);
			log.log(new Status(severity, PLUGIN_ID, message, t));
		}
		catch (Throwable fallback) {
			System.err.println("[eclipsemcpserver] " + message);
			if (t != null) {
				t.printStackTrace();
			}
		}
	}
}

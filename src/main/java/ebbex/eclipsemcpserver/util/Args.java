package ebbex.eclipsemcpserver.util;

import java.util.Map;

/** Readers for tool call arguments. */
public final class Args {

	private Args() {
	}

	public static String stringArg(Map<String, Object> args, String key, String defaultValue) {
		Object v = args.get(key);
		return v == null ? defaultValue : v.toString();
	}

	public static int intArg(Map<String, Object> args, String key, int defaultValue) {
		Object v = args.get(key);
		if (v == null) {
			return defaultValue;
		}
		if (v instanceof Number n) {
			return n.intValue();
		}
		return Integer.parseInt(v.toString());
	}

	public static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
		Object v = args.get(key);
		if (v == null) {
			return defaultValue;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		return Boolean.parseBoolean(v.toString());
	}
}

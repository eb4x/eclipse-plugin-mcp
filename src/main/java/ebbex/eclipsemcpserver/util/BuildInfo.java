package ebbex.eclipsemcpserver.util;

import java.io.InputStream;
import java.util.Properties;

/**
 * The build stamp Gradle writes into {@code eclipsemcp-build.properties}: the semantic
 * version from {@code version.properties} (minor bumped per feature, patch per fix)
 * plus the git commit ({@code +dirty} when the tree had uncommitted changes) and the
 * build time. Lets a client ask the running server which build it is talking to
 * instead of inferring it from restart choreography. A classpath without the resource
 * (e.g. classes run straight from an IDE) reports {@code unstamped}.
 */
public final class BuildInfo {

	private static final String RESOURCE = "/eclipsemcp-build.properties";
	private static final String UNSTAMPED_VERSION = "0.0.0";

	private static final String VERSION;
	private static final String DESCRIPTION;
	static {
		Properties props = load();
		VERSION = props.getProperty("version", UNSTAMPED_VERSION);
		DESCRIPTION = props.isEmpty()
				? UNSTAMPED_VERSION + " (unstamped: run from IDE/classes, not a built bundle)"
				: VERSION + " (git " + props.getProperty("git", "unknown") + ", built " +
					props.getProperty("built", "unknown") + ")";
	}

	private BuildInfo() {
	}

	/** The semantic version alone, e.g. {@code 0.1.0} ({@code 0.0.0} when unstamped). */
	public static String version() {
		return VERSION;
	}

	/** One-line build identity: {@code 0.1.0 (git 146750a+dirty, built 2026-08-31 11:02:03 UTC)}. */
	public static String describe() {
		return DESCRIPTION;
	}

	private static Properties load() {
		Properties props = new Properties();
		try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
			if (in != null) {
				props.load(in);
			}
		}
		catch (Exception ignored) {
			// fall through to the unstamped defaults
		}
		return props;
	}
}

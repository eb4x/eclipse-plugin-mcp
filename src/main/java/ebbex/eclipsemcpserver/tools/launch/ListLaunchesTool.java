package ebbex.eclipsemcpserver.tools.launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.Args;
import ebbex.eclipsemcpserver.util.ConsoleCapture;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Schemas;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The read side of the launch endpoint: what can be launched, and what is running.
 *
 * <p>{@code kind=configurations} lists the launch configurations the workspace knows — the
 * names {@code manage_launch} accepts. {@code kind=active} lists launches, merging two sources
 * that overlap: the launch manager's live launches and this server's console captures. A launch
 * appears with a {@code #id} exactly when its output is buffered, and that id is what
 * {@code read_console} addresses; a launch that predates the server (or produced no streams)
 * still shows up, marked {@code #-}, and a capture whose launch has been removed from the Debug
 * view still shows up because its buffer is still readable.
 */
public class ListLaunchesTool implements Tool {

	private static final List<String> KINDS = List.of("configurations", "active");

	private static final int DEFAULT_LIMIT = 100;

	private final ConsoleCapture consoleCapture;

	public ListLaunchesTool(ConsoleCapture consoleCapture) {
		this.consoleCapture = consoleCapture;
	}

	@Override
	public String name() {
		return "list_launches";
	}

	@Override
	public String description() {
		return "List launch configurations or running/finished launches. " +
			"kind=configurations shows every launch configuration in the workspace as " +
			"'<name> (<type>)' with its mapped project — the 'name' you pass to " +
			"manage_launch (e.g. \"Ghidra\"). kind=active shows launches as " +
			"'#<id> <config> [<mode>] running|terminated(exit=N) started=HH:mm:ss " +
			"buffered=<n> lines'; the #id is read_console's 'launch' argument. Launches " +
			"started before this server did have no buffer and show '#-'. Use filter to " +
			"match a substring of the configuration name.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		return Map.of(
			"type", "object",
			"properties", Map.of(
				"kind", Schemas.enumProp("What to list: configurations (launchable) or " +
					"active (launches this session; the default)", KINDS),
				"filter", Schemas.stringProp("Case-insensitive substring of the launch " +
					"configuration name"),
				"offset", Schemas.intProp("Index of the first entry to return (default 0)"),
				"limit", Schemas.intProp("Maximum entries to return (default " +
					DEFAULT_LIMIT + ")")),
			"required", List.of());
	}

	@Override
	public boolean isReadOnly() {
		return true;
	}

	@Override
	public boolean requiresWorkspace() {
		return true;
	}

	@Override
	public McpSchema.CallToolResult execute(Map<String, Object> args, IWorkspace workspace)
			throws Exception {
		String kind = Args.stringArg(args, "kind", "active");
		if (!KINDS.contains(kind)) {
			throw new IllegalArgumentException("kind must be one of " + KINDS);
		}
		String filter = Args.stringArg(args, "filter", null);
		int offset = Math.max(0, Args.intArg(args, "offset", 0));
		int limit = Math.max(1, Args.intArg(args, "limit", DEFAULT_LIMIT));

		ILaunchManager manager = ConsoleCapture.launchManager();
		if (manager == null && !"active".equals(kind)) {
			return Results.error("The Eclipse debug core is not available in this IDE instance; " +
				"launch configurations cannot be read.");
		}

		List<String> lines = switch (kind) {
			case "configurations" -> configurations(manager, filter);
			case "active" -> active(manager, filter);
			default -> throw new IllegalArgumentException(
				"Unknown kind '" + kind + "'; expected one of " + KINDS);
		};

		if (lines.isEmpty()) {
			return Results.ok(filter == null ? "(none)"
					: "(none matching filter '" + filter + "')");
		}
		return Results.ok(page(lines, offset, limit));
	}

	private static List<String> configurations(ILaunchManager manager, String filter)
			throws Exception {
		List<ILaunchConfiguration> configs = new ArrayList<>();
		for (ILaunchConfiguration config : manager.getLaunchConfigurations()) {
			if (config.isPrototype() || !matches(config.getName(), filter)) {
				continue;
			}
			configs.add(config);
		}
		configs.sort(Comparator.comparing(ILaunchConfiguration::getName,
			String.CASE_INSENSITIVE_ORDER));

		List<String> lines = new ArrayList<>(configs.size());
		for (ILaunchConfiguration config : configs) {
			StringBuilder line = new StringBuilder(config.getName());
			line.append(" (").append(typeName(config)).append(')');
			String project = mappedProject(config);
			if (project != null) {
				line.append(" project=").append(project);
			}
			lines.add(line.toString());
		}
		return lines;
	}

	private List<String> active(ILaunchManager manager, String filter) {
		// The two sources overlap; the capture is authoritative where one exists, so live
		// launches are keyed on their capture and captures are only listed separately when
		// their launch has left the launch manager.
		List<ConsoleCapture.Capture> captures = consoleCapture.captures();
		Set<ILaunch> capturedLive = Collections.newSetFromMap(new IdentityHashMap<>());

		record Entry(long started, String text) {
		}
		List<Entry> entries = new ArrayList<>();

		for (ConsoleCapture.Capture capture : captures) {
			if (!matches(capture.configName(), filter)) {
				continue;
			}
			capturedLive.add(capture.launch());
			entries.add(new Entry(capture.startedMillis(),
				"#" + capture.id() + " " + capture.configName() +
					" [" + capture.mode() + "] " + capture.state() +
					" started=" + ConsoleCapture.timeOfDay(capture.startedMillis()) +
					" buffered=" + capture.bufferedLines() + " lines" +
					(capture.elidedLines() > 0
							? " (" + capture.elidedLines() + " elided)" : "")));
		}

		if (manager != null) {
			for (ILaunch launch : manager.getLaunches()) {
				if (launch == null || capturedLive.contains(launch)) {
					continue;
				}
				if (consoleCapture.forLaunch(launch) != null) {
					continue; // captured but filtered out above
				}
				String configName = ConsoleCapture.configNameOf(launch);
				if (!matches(configName, filter)) {
					continue;
				}
				long started = ConsoleCapture.startTimeOf(launch);
				entries.add(new Entry(started,
					"#- " + configName + " [" + ConsoleCapture.modeOf(launch) + "] " +
						ConsoleCapture.describeState(launch) +
						" started=" + ConsoleCapture.timeOfDay(started) +
						" buffered=none (started before this server, or no output streams)"));
			}
		}

		entries.sort(Comparator.comparingLong(Entry::started).reversed());
		return entries.stream().map(Entry::text).toList();
	}

	private static String typeName(ILaunchConfiguration config) {
		try {
			return config.getType().getName();
		}
		catch (Exception e) {
			// The contributing bundle may not be installed any more.
			return "unknown type";
		}
	}

	/** The first mapped resource's project, or null — the mapping is optional and may throw. */
	private static String mappedProject(ILaunchConfiguration config) {
		try {
			IResource[] mapped = config.getMappedResources();
			if (mapped == null) {
				return null;
			}
			for (IResource resource : mapped) {
				if (resource != null && resource.getProject() != null) {
					return resource.getProject().getName();
				}
			}
		}
		catch (Exception ignored) {
			// Attribute absent or unreadable; the mapping is a nicety, not a requirement.
		}
		return null;
	}

	private static boolean matches(String value, String filter) {
		return filter == null || filter.isEmpty() || (value != null &&
			value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)));
	}

	private static String page(List<String> lines, int offset, int limit) {
		int total = lines.size();
		int from = Math.min(offset, total);
		int to = Math.min(from + limit, total);
		StringBuilder sb = new StringBuilder();
		for (String line : lines.subList(from, to)) {
			sb.append(line).append('\n');
		}
		sb.append(Results.paginationFooter(to - from, offset, total));
		return sb.toString();
	}
}

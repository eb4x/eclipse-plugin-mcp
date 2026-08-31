package ebbex.eclipsemcpserver.tools.launch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.Args;
import ebbex.eclipsemcpserver.util.ConsoleCapture;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Schemas;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Start and stop launch configurations — the one-call relaunch loop
 * ("rebuild my plugin, restart Ghidra") that peer sessions drive this server for.
 *
 * <p><b>Two hazards shape this class.</b>
 *
 * <p>First, <i>modal dialogs</i>. We deliberately use {@link ILaunchConfiguration#launch} from
 * {@code org.eclipse.debug.core} rather than {@code DebugUITools}, but the core launch still
 * routes problems through {@code IStatusHandler}s that {@code org.eclipse.debug.ui} contributes,
 * and the common one — "errors exist in the project, launch anyway?" — is a modal dialog that
 * blocks the calling thread until a human clicks. So: we pre-check error markers on the
 * configuration's mapped projects and refuse with an explanation (overridable with
 * {@code ignore_errors=true}) instead of walking into the dialog, and the launch call itself
 * runs on a separate bounded thread so a dialog we failed to anticipate costs the caller a
 * timeout message rather than a hung MCP request.
 *
 * <p>Second, <i>waiting</i>. {@code launch(mode, monitor, build=false, register=true)} is called
 * with build disabled on purpose: Eclipse's auto-build has normally already run by the time a
 * caller asks for a launch, and a synchronous incremental build inside the launch is exactly the
 * unbounded wait that peers asked us not to impose. This tool returns as soon as the process is
 * up; it never waits for console patterns. Poll the launched service itself, or read
 * {@code read_console}, to decide when it is ready.
 */
public class ManageLaunchTool implements Tool {

	private static final List<String> OPS = List.of("launch", "terminate");

	private static final List<String> MODES = List.of("run", "debug");

	private static final int DEFAULT_WAIT_SECONDS = 20;

	/** How long we wait for the launches we terminate on the caller's behalf to actually die. */
	private static final long TERMINATE_EXISTING_TIMEOUT_MS = 5_000;

	/** How long we let the freshly launched process show up before reporting it unlabelled. */
	private static final long PROCESS_APPEARANCE_TIMEOUT_MS = 1_000;

	private static final long POLL_INTERVAL_MS = 100;

	public ManageLaunchTool() {
	}

	@Override
	public String name() {
		return "manage_launch";
	}

	@Override
	public String description() {
		return "Start or stop an Eclipse launch configuration. op=launch runs the configuration " +
			"named by 'configuration' (exact name from list_launches kind=configurations, e.g. " +
			"\"Ghidra\"); by default it first terminates any running launch of that same " +
			"configuration, so this is the whole restart loop in one call. It returns as soon as " +
			"the process is up — it does NOT wait for the program to become ready; poll the " +
			"program itself or read_console for that. It does NOT build first (auto-build has " +
			"normally already run; use manage_projects op=build when you need a fresh build). " +
			"If the configuration's project has compile errors the launch is refused with a " +
			"count — pass ignore_errors=true to launch anyway. op=terminate stops a launch " +
			"named by its configuration ('launch'), defaulting to the most recently started " +
			"running one. Output of anything launched is available from read_console.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("op", Schemas.enumProp("What to do", OPS));
		properties.put("configuration", Schemas.stringProp("For op=launch: the exact launch " +
			"configuration name, e.g. \"Ghidra\" (list_launches kind=configurations)"));
		properties.put("mode", Schemas.enumProp("For op=launch: run (default) or debug", MODES));
		properties.put("terminate_existing", Schemas.boolProp("For op=launch: first terminate " +
			"running launches of the same configuration (default true)"));
		properties.put("ignore_errors", Schemas.boolProp("For op=launch: launch even though the " +
			"configuration's project has compile errors (default false)"));
		properties.put("launch", Schemas.stringProp("For op=terminate: the launch configuration " +
			"name whose launch to stop; defaults to the most recently started running launch"));
		properties.put("wait_seconds", Schemas.intProp("Seconds to wait for the operation " +
			"(default " + DEFAULT_WAIT_SECONDS + "); on timeout the operation is reported as " +
			"unfinished, not cancelled"));

		return Map.of(
			"type", "object",
			"properties", properties,
			"required", List.of("op"));
	}

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@Override
	public boolean requiresWorkspace() {
		return true;
	}

	@Override
	public McpSchema.CallToolResult execute(Map<String, Object> args, IWorkspace workspace)
			throws Exception {
		String op = Args.stringArg(args, "op", null);
		if (op == null) {
			throw new IllegalArgumentException("op is required: one of " + OPS);
		}
		ILaunchManager manager = ConsoleCapture.launchManager();
		if (manager == null) {
			return Results.error("The Eclipse debug core is not available in this IDE instance; " +
				"nothing can be launched.");
		}
		int waitSeconds = Math.max(1, Args.intArg(args, "wait_seconds", DEFAULT_WAIT_SECONDS));

		return switch (op) {
			case "launch" -> launch(args, manager, waitSeconds);
			case "terminate" -> terminate(args, manager, waitSeconds);
			default -> throw new IllegalArgumentException(
				"Unknown op '" + op + "'; expected one of " + OPS);
		};
	}

	// ------------------------------------------------------------------ launch

	private McpSchema.CallToolResult launch(Map<String, Object> args, ILaunchManager manager,
			int waitSeconds) throws Exception {
		String name = Args.stringArg(args, "configuration", null);
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException(
				"configuration is required for op=launch (list_launches kind=configurations " +
					"shows the names)");
		}
		String mode = Args.stringArg(args, "mode", "run");
		if (!MODES.contains(mode)) {
			throw new IllegalArgumentException(
				"Unknown mode '" + mode + "'; expected one of " + MODES);
		}
		boolean terminateExisting = Args.boolArg(args, "terminate_existing", true);
		boolean ignoreErrors = Args.boolArg(args, "ignore_errors", false);

		ILaunchConfiguration config = requireConfiguration(manager, name);
		if (!config.supportsMode(mode)) {
			throw new IllegalArgumentException("Launch configuration '" + config.getName() +
				"' does not support mode '" + mode + "' (supported: " + config.getModes() + ")");
		}

		StringBuilder report = new StringBuilder();

		if (terminateExisting) {
			report.append(terminateExisting(manager, config));
		}

		if (!ignoreErrors) {
			String problems = errorMarkerReport(config);
			if (problems != null) {
				return Results.error(report + problems);
			}
		}

		// The launch runs off this HTTP thread: a status handler contributed by
		// org.eclipse.debug.ui can still raise a modal dialog inside launch(), and that would
		// otherwise block the request until a human noticed the Eclipse window.
		ClassLoader tccl = Thread.currentThread().getContextClassLoader();
		Executor executor = runnable -> {
			Thread thread = new Thread(runnable, "mcp-launch-" + config.getName());
			thread.setDaemon(true);
			thread.setContextClassLoader(tccl);
			thread.start();
		};
		CompletableFuture<ILaunch> future = CompletableFuture.supplyAsync(() -> {
			try {
				// build=false: return as soon as the process is up (auto-build has normally
				// already run). register=true: the launch shows in the Debug view and reaches
				// our ConsoleCapture listener.
				return config.launch(mode, new NullProgressMonitor(), false, true);
			}
			catch (Exception e) {
				throw new CompletionException(e);
			}
		}, executor);

		ILaunch launched;
		try {
			launched = future.get(waitSeconds, TimeUnit.SECONDS);
		}
		catch (TimeoutException e) {
			return Results.error(report + "launch of '" + config.getName() + "' did not return " +
				"within " + waitSeconds + "s. It was NOT abandoned — Eclipse is probably showing " +
				"a modal dialog that a human must dismiss in the Eclipse window (or the launch " +
				"delegate is simply slow). Check list_launches kind=active before launching " +
				"again, or the process may end up running twice.");
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof CompletionException && cause.getCause() != null) {
				cause = cause.getCause();
			}
			String detail = cause.getMessage() != null ? cause.getMessage() : cause.toString();
			return Results.error(report + "launch of '" + config.getName() + "' failed: " + detail);
		}

		report.append("Launched '").append(config.getName()).append("' [").append(mode)
				.append(']');
		String processes = describeProcesses(launched);
		if (processes != null) {
			report.append(": ").append(processes);
		}
		report.append('\n');
		report.append("read_console launch=\"").append(config.getName())
				.append("\" follows its output.");
		return Results.ok(report.toString());
	}

	/** Terminate every running launch of this configuration; returns what happened, as report text. */
	private static String terminateExisting(ILaunchManager manager, ILaunchConfiguration config) {
		List<ILaunch> running = runningLaunchesOf(manager, config.getName());
		if (running.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (ILaunch launch : running) {
			try {
				if (launch.canTerminate()) {
					launch.terminate();
				}
			}
			catch (Exception e) {
				sb.append("Could not terminate the running '").append(config.getName())
						.append("': ").append(e.getMessage()).append('\n');
			}
		}
		boolean allDead = awaitTerminated(running, TERMINATE_EXISTING_TIMEOUT_MS);
		sb.append("Terminated ").append(running.size()).append(" running launch")
				.append(running.size() == 1 ? "" : "es").append(" of '")
				.append(config.getName()).append('\'');
		if (!allDead) {
			sb.append(" (at least one had not died after ")
					.append(TERMINATE_EXISTING_TIMEOUT_MS / 1000)
					.append("s; the new launch was started anyway)");
		}
		sb.append(".\n");
		return sb.toString();
	}

	/**
	 * Error markers on the configuration's mapped projects, as a refusal message, or null when
	 * the launch may proceed. This is the pre-check that keeps us out of the debug.ui
	 * "errors exist — proceed?" modal dialog; a configuration with no mapped resources cannot
	 * be checked and is allowed through.
	 */
	private static String errorMarkerReport(ILaunchConfiguration config) {
		List<IProject> projects = mappedProjects(config);
		if (projects.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		int total = 0;
		for (IProject project : projects) {
			int errors = errorCount(project);
			if (errors > 0) {
				total += errors;
				sb.append("  project ").append(project.getName()).append(": ").append(errors)
						.append(" error").append(errors == 1 ? "" : "s").append('\n');
			}
		}
		if (total == 0) {
			return null;
		}
		return "Refusing to launch '" + config.getName() + "': its project has compile errors, " +
			"and launching would raise a modal 'errors exist, proceed?' dialog in Eclipse that " +
			"no MCP client can dismiss.\n" + sb +
			"Fix them (get_problems severity=error) or pass ignore_errors=true to launch anyway.";
	}

	private static int errorCount(IProject project) {
		try {
			if (!project.isAccessible()) {
				return 0;
			}
			IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true,
				IResource.DEPTH_INFINITE);
			int errors = 0;
			for (IMarker marker : markers) {
				if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
					errors++;
				}
			}
			return errors;
		}
		catch (Exception e) {
			// An unreadable marker store must not block a launch.
			return 0;
		}
	}

	private static List<IProject> mappedProjects(ILaunchConfiguration config) {
		List<IProject> projects = new ArrayList<>();
		try {
			IResource[] mapped = config.getMappedResources();
			if (mapped == null) {
				return projects;
			}
			for (IResource resource : mapped) {
				IProject project = resource == null ? null : resource.getProject();
				if (project != null && !projects.contains(project)) {
					projects.add(project);
				}
			}
		}
		catch (Exception ignored) {
			// The mapping attribute is optional; without it there is nothing to check.
		}
		return projects;
	}

	// --------------------------------------------------------------- terminate

	private McpSchema.CallToolResult terminate(Map<String, Object> args, ILaunchManager manager,
			int waitSeconds) throws Exception {
		String target = Args.stringArg(args, "launch", null);
		ILaunch launch = resolveTarget(manager, target);

		String configName = ConsoleCapture.configNameOf(launch);
		if (launch.isTerminated()) {
			return Results.ok("'" + configName + "' is already terminated (" +
				ConsoleCapture.describeState(launch) + "). Its output is still in read_console.");
		}
		if (!launch.canTerminate()) {
			return Results.error("'" + configName + "' cannot be terminated through the debug " +
				"model (it reports canTerminate=false); stop it from its own UI or with kill(1).");
		}
		launch.terminate();

		long timeoutMs = Math.min(waitSeconds, 10) * 1000L;
		boolean dead = awaitTerminated(List.of(launch), timeoutMs);
		if (!dead) {
			return Results.ok("Asked '" + configName + "' to terminate; it had not exited after " +
				timeoutMs / 1000 + "s. Check list_launches kind=active.");
		}
		return Results.ok("Terminated '" + configName + "' (" +
			ConsoleCapture.describeState(launch) + "). Its output is still in read_console.");
	}

	/**
	 * The launch to terminate: named by configuration, else the most recently started running
	 * launch. {@code #id}s belong to {@code read_console} (they identify console buffers, not
	 * launches) and are rejected here with that advice.
	 */
	private static ILaunch resolveTarget(ILaunchManager manager, String target) {
		List<ILaunch> running = runningLaunches(manager);
		if (target == null || target.isBlank()) {
			if (running.isEmpty()) {
				throw new IllegalArgumentException("No launch is running " +
					"(list_launches kind=active shows what this session has seen)");
			}
			return running.get(0);
		}
		String trimmed = target.trim();
		if (trimmed.startsWith("#") || trimmed.chars().allMatch(Character::isDigit)) {
			throw new IllegalArgumentException("'" + trimmed + "' looks like a read_console " +
				"buffer id; op=terminate addresses launches by launch configuration name, e.g. " +
				"launch=\"Ghidra\" (list_launches kind=active shows the names)");
		}
		for (ILaunch launch : running) {
			if (ConsoleCapture.configNameOf(launch).equals(trimmed)) {
				return launch;
			}
		}
		for (ILaunch launch : running) {
			if (ConsoleCapture.configNameOf(launch).toLowerCase(Locale.ROOT)
					.contains(trimmed.toLowerCase(Locale.ROOT))) {
				return launch;
			}
		}
		throw new IllegalArgumentException("No running launch of '" + trimmed +
			"' (list_launches kind=active shows what is running)");
	}

	// ------------------------------------------------------------------ shared

	private static ILaunchConfiguration requireConfiguration(ILaunchManager manager, String name)
			throws Exception {
		ILaunchConfiguration[] configs = manager.getLaunchConfigurations();
		for (ILaunchConfiguration config : configs) {
			if (config.getName().equals(name)) {
				return config;
			}
		}
		List<String> close = new ArrayList<>();
		String needle = name.toLowerCase(Locale.ROOT);
		for (ILaunchConfiguration config : configs) {
			if (config.getName().toLowerCase(Locale.ROOT).contains(needle)) {
				close.add(config.getName());
			}
		}
		if (close.isEmpty()) {
			throw new IllegalArgumentException("No launch configuration named '" + name +
				"' (list_launches kind=configurations shows the " + configs.length + " names)");
		}
		throw new IllegalArgumentException("No launch configuration named exactly '" + name +
			"'. Did you mean: " + String.join(", ", close.subList(0, Math.min(10, close.size()))) +
			"?");
	}

	/** Running launches, most recently started first. */
	private static List<ILaunch> runningLaunches(ILaunchManager manager) {
		List<ILaunch> running = new ArrayList<>();
		for (ILaunch launch : manager.getLaunches()) {
			if (launch != null && !launch.isTerminated()) {
				running.add(launch);
			}
		}
		running.sort(Comparator.comparingLong(ConsoleCapture::startTimeOf).reversed());
		return running;
	}

	private static List<ILaunch> runningLaunchesOf(ILaunchManager manager, String configName) {
		List<ILaunch> matching = new ArrayList<>();
		for (ILaunch launch : runningLaunches(manager)) {
			if (ConsoleCapture.configNameOf(launch).equals(configName)) {
				matching.add(launch);
			}
		}
		return matching;
	}

	private static boolean awaitTerminated(List<ILaunch> launches, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			boolean allDead = launches.stream().allMatch(ILaunch::isTerminated);
			if (allDead) {
				return true;
			}
			if (System.currentTimeMillis() >= deadline) {
				return false;
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	/**
	 * The launch's process labels, waiting briefly for them: the delegate registers the launch
	 * before it attaches the process, so an immediate read often finds none.
	 */
	private static String describeProcesses(ILaunch launch) {
		long deadline = System.currentTimeMillis() + PROCESS_APPEARANCE_TIMEOUT_MS;
		IProcess[] processes = launch.getProcesses();
		while (processes.length == 0 && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			processes = launch.getProcesses();
		}
		if (processes.length == 0) {
			return launch.getDebugTargets().length > 0 ? "debug target attached, no OS process yet"
					: null;
		}
		List<String> labels = new ArrayList<>();
		for (IProcess process : processes) {
			labels.add(process.getLabel());
		}
		return String.join(", ", labels);
	}
}

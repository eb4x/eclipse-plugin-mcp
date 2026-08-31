package ebbex.eclipsemcpserver.tools;

import java.io.File;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.BuildInfo;
import ebbex.eclipsemcpserver.util.BuildQueue;
import ebbex.eclipsemcpserver.util.Results;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Snapshot of the running IDE: which server build is serving, which workspace is open,
 * how many projects it holds, whether anything is building, and the workspace-wide
 * problem counts.
 *
 * <p>Call it first in a session (it is the cheap orientation call) and again whenever a
 * build was requested — "build in progress" plus the MCP queue state is how a caller
 * knows the marker counts from {@code get_problems} are not yet final.
 */
public class GetWorkspaceInfoTool implements Tool {

	private final BuildQueue buildQueue;

	public GetWorkspaceInfoTool(BuildQueue buildQueue) {
		this.buildQueue = buildQueue;
	}

	@Override
	public String name() {
		return "get_workspace_info";
	}

	@Override
	public String description() {
		return "Get information about the running Eclipse IDE and its workspace: the server's " +
			"build stamp and OSGi bundle version (check it to confirm which plugin build is " +
			"serving), the Eclipse buildId, the workspace location, project counts, whether " +
			"auto-build is enabled, whether a build is currently running, this server's build " +
			"queue state, and the workspace-wide error/warning totals. Marker counts are only " +
			"final when nothing is building — 'build in progress' means retry.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		return Map.of("type", "object", "properties", Map.of());
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
			throws CoreException {
		IWorkspaceRoot root = workspace.getRoot();

		int open = 0;
		int closed = 0;
		for (IProject project : root.getProjects()) {
			if (project.isOpen()) {
				open++;
			}
			else {
				closed++;
			}
		}

		int errors = 0;
		int warnings = 0;
		for (IMarker marker : root.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
			switch (marker.getAttribute(IMarker.SEVERITY, -1)) {
				case IMarker.SEVERITY_ERROR -> errors++;
				case IMarker.SEVERITY_WARNING -> warnings++;
				default -> {
					// info / unset severity: not summarised here (get_problems severity=info shows them)
				}
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Server build: ").append(BuildInfo.describe())
				.append(", bundle ").append(bundleVersion()).append('\n');
		sb.append("Eclipse buildId: ")
				.append(System.getProperty("eclipse.buildId", "unknown")).append('\n');
		sb.append("Workspace: ").append(location(root)).append('\n');
		sb.append("Projects: ").append(open + closed).append(" (").append(open).append(" open, ")
				.append(closed).append(" closed)").append('\n');
		sb.append("Auto-build: ")
				.append(workspace.getDescription().isAutoBuilding() ? "on" : "off").append('\n');
		sb.append("Builds: ").append(buildState()).append('\n');
		sb.append("MCP build queue: ").append(queueState()).append('\n');
		sb.append("Problems: ").append(errors).append(" errors, ").append(warnings)
				.append(" warnings  (get_problems for detail)");

		File log = ReadLogTool.logFile();
		if (log != null) {
			sb.append('\n').append("Log: ").append(log.getAbsolutePath())
					.append("  (read with read_log)");
		}
		return Results.ok(sb.toString());
	}

	private static String location(IWorkspaceRoot root) {
		IPath path = root.getLocation();
		return path != null ? path.toOSString() : String.valueOf(root.getLocationURI());
	}

	/**
	 * Whether the platform is building right now. Auto-build and explicit builds run as jobs
	 * in these two families, so a non-empty find() is the honest "your markers are stale"
	 * signal — the workspace itself has no "is building" flag.
	 */
	private static String buildState() {
		int auto = Job.getJobManager().find(ResourcesPlugin.FAMILY_AUTO_BUILD).length;
		int manual = Job.getJobManager().find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length;
		if (auto + manual == 0) {
			return "idle";
		}
		return "build in progress (" + auto + " auto, " + manual + " manual job" +
			(auto + manual == 1 ? "" : "s") + ") — problem counts are not final";
	}

	private String queueState() {
		int pending = buildQueue.pending();
		if (pending == 0) {
			return "idle";
		}
		String running = buildQueue.running();
		return pending + " task" + (pending == 1 ? "" : "s") + " pending" +
			(running != null ? ", running '" + running + "'" : "");
	}

	/** The OSGi bundle version, including the build qualifier — decisive for "is my build loaded". */
	private static String bundleVersion() {
		try {
			Bundle bundle = FrameworkUtil.getBundle(GetWorkspaceInfoTool.class);
			return bundle != null ? bundle.getVersion().toString() : "not running as a bundle";
		}
		catch (Throwable t) {
			return "unknown";
		}
	}
}

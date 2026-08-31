package ebbex.eclipsemcpserver.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.Args;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Schemas;
import ebbex.eclipsemcpserver.util.Workspaces;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Read the workspace's problem markers &mdash; the compiler errors and warnings the IDE
 * itself shows in the Problems view. This is the tool the edit&rarr;refresh&rarr;build loop
 * ends on, so its output is deliberately grep-shaped: one
 * {@code project/path:line: [ERROR] message} per line, errors first.
 *
 * <p>The header reports the totals for the whole scope <em>before</em> the severity filter,
 * so a caller asking for errors still learns that there are 340 warnings behind them without
 * paying for a second call.
 *
 * <p>Markers are only as fresh as the last build: if {@code get_workspace_info} says a build
 * is in progress, the counts here are mid-flight.
 */
public class GetProblemsTool implements Tool {

	private static final int DEFAULT_LIMIT = 100;

	/** One problem marker, flattened to what a caller needs to act on it. */
	private record Problem(int severity, String sortPath, String displayPath, int line,
			String message) {
	}

	@Override
	public String name() {
		return "get_problems";
	}

	@Override
	public String description() {
		return "List Eclipse problem markers (compiler errors, warnings, info) as " +
			"'project/path:line: [ERROR] message', errors first. This is how you check whether " +
			"an edit compiles: edit the file, manage_projects op=refresh, then call this. " +
			"Defaults to errors only across the whole workspace; scope it with 'project' " +
			"(name from list_projects), narrow it with 'path' (workspace-relative prefix) or " +
			"'filter' (substring of the message). The header always reports the error/warning/" +
			"info totals for the scope, whatever severity you asked for. Markers reflect the " +
			"last completed build — check get_workspace_info if a build may still be running.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		return Map.of(
			"type", "object",
			"properties", Map.of(
				"severity", Schemas.enumProp("Minimum severity to list (default 'error'); " +
					"'all' lists every severity", List.of("error", "warning", "info", "all")),
				"project", Schemas.stringProp("Limit to this project (name as shown by " +
					"list_projects); default is the whole workspace"),
				"path", Schemas.stringProp("Keep only problems under this workspace-relative " +
					"path prefix, e.g. 'MyProject/src/main'"),
				"filter", Schemas.stringProp("Case-insensitive substring the problem message " +
					"must contain"),
				"offset", Schemas.intProp("Skip this many problems (default 0)"),
				"limit", Schemas.intProp("Maximum problems to return (default " +
					DEFAULT_LIMIT + ")")));
	}

	@Override
	public boolean isReadOnly() {
		return true;
	}

	@Override
	public McpSchema.CallToolResult execute(Map<String, Object> args, IWorkspace workspace)
			throws CoreException {
		String severityArg = Args.stringArg(args, "severity", "error").toLowerCase();
		int minSeverity = minSeverity(severityArg);
		String projectName = Args.stringArg(args, "project", "");
		String pathPrefix = normalize(Args.stringArg(args, "path", ""));
		String filter = Args.stringArg(args, "filter", "").toLowerCase();
		int offset = Math.max(0, Args.intArg(args, "offset", 0));
		int limit = Math.max(1, Args.intArg(args, "limit", DEFAULT_LIMIT));

		IResource scope = projectName.isEmpty()
				? workspace.getRoot()
				: Workspaces.requireProject(workspace, projectName);

		// Totals are counted over everything the scope + path/filter select, ignoring the
		// severity argument: that is the "landscape" line the caller would otherwise have to
		// make a second call for.
		int[] totals = new int[3]; // {errors, warnings, infos}
		List<Problem> matches = new ArrayList<>();
		for (IMarker marker : scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
			// One attribute read per marker: a workspace with thousands of markers pays four
			// separate map lookups per marker otherwise.
			Map<String, Object> attributes = marker.getAttributes();
			if (attributes == null) {
				continue;
			}
			IResource resource = marker.getResource();
			String fullPath = normalize(resource.getFullPath().toString());
			if (!pathPrefix.isEmpty() && !fullPath.startsWith(pathPrefix)) {
				continue;
			}
			String message = string(attributes.get(IMarker.MESSAGE));
			if (!filter.isEmpty() && !message.toLowerCase().contains(filter)) {
				continue;
			}
			int severity = severity(attributes.get(IMarker.SEVERITY));
			totals[2 - severity]++;
			if (severity < minSeverity) {
				continue;
			}
			matches.add(new Problem(severity, fullPath, display(resource), line(attributes),
				message.replace('\n', ' ').replace('\r', ' ')));
		}

		// Errors first, then by path, then by line: stable and diff-friendly across calls.
		matches.sort(Comparator.comparingInt(Problem::severity).reversed()
				.thenComparing(Problem::sortPath)
				.thenComparingInt(Problem::line));

		String scopeText = scopeText(projectName, pathPrefix, filter);
		StringBuilder sb = new StringBuilder();
		sb.append(totals[0]).append(" errors, ").append(totals[1]).append(" warnings, ")
				.append(totals[2]).append(" infos in ").append(scopeText).append('\n');

		if (matches.isEmpty()) {
			return Results.ok(sb.append("No ").append(severityLabel(severityArg)).append(" in ")
					.append(scopeText).append('.').toString());
		}
		List<Problem> window = matches.stream().skip(offset).limit(limit).toList();
		for (Problem problem : window) {
			sb.append(problem.displayPath());
			if (problem.line() > 0) {
				sb.append(':').append(problem.line());
			}
			sb.append(": [").append(label(problem.severity())).append("] ")
					.append(problem.message()).append('\n');
		}
		sb.append(Results.paginationFooter(window.size(), offset, matches.size()));
		return Results.ok(sb.toString());
	}

	private static int minSeverity(String severity) {
		return switch (severity) {
			case "error" -> IMarker.SEVERITY_ERROR;
			case "warning" -> IMarker.SEVERITY_WARNING;
			case "info", "all" -> IMarker.SEVERITY_INFO;
			default -> throw new IllegalArgumentException("Unknown severity '" + severity +
				"' — use error, warning, info, or all");
		};
	}

	private static String severityLabel(String severity) {
		return switch (severity) {
			case "error" -> "errors";
			case "warning" -> "warnings or errors";
			case "info" -> "problems";
			default -> "problems";
		};
	}

	private static String label(int severity) {
		return switch (severity) {
			case IMarker.SEVERITY_ERROR -> "ERROR";
			case IMarker.SEVERITY_WARNING -> "WARN";
			default -> "INFO";
		};
	}

	/** Markers without a usable SEVERITY attribute are treated as info rather than dropped. */
	private static int severity(Object value) {
		if (value instanceof Number n) {
			int severity = n.intValue();
			if (severity >= IMarker.SEVERITY_INFO && severity <= IMarker.SEVERITY_ERROR) {
				return severity;
			}
		}
		return IMarker.SEVERITY_INFO;
	}

	private static int line(Map<String, Object> attributes) {
		Object value = attributes.get(IMarker.LINE_NUMBER);
		return value instanceof Number n ? n.intValue() : -1;
	}

	private static String string(Object value) {
		return value != null ? value.toString() : "(no message)";
	}

	/**
	 * {@code project/project-relative/path}. A marker on the project itself (build path
	 * problems) prints just the project name; one on the workspace root prints
	 * {@code (workspace)}.
	 */
	private static String display(IResource resource) {
		if (resource.getType() == IResource.ROOT) {
			return "(workspace)";
		}
		String project = resource.getProject() != null ? resource.getProject().getName() : "";
		String relative = resource.getProjectRelativePath().toString();
		if (project.isEmpty()) {
			return normalize(resource.getFullPath().toString());
		}
		return relative.isEmpty() ? project : project + "/" + relative;
	}

	private static String scopeText(String projectName, String pathPrefix, String filter) {
		StringBuilder sb = new StringBuilder(
			projectName.isEmpty() ? "workspace" : "project " + projectName);
		if (!pathPrefix.isEmpty()) {
			sb.append(" under ").append(pathPrefix);
		}
		if (!filter.isEmpty()) {
			sb.append(" matching '").append(filter).append('\'');
		}
		return sb.toString();
	}

	/** Workspace-relative paths are compared without a leading separator, whoever supplies them. */
	private static String normalize(String path) {
		return path.startsWith("/") ? path.substring(1) : path;
	}
}

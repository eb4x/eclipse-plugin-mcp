package ebbex.eclipsemcpserver.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.Args;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Schemas;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * List the workspace's projects, one per line, with their open/closed state, natures,
 * problem counts, and filesystem location.
 *
 * <p>The location is the field that maps an on-disk path Claude edits to the project name
 * every other tool takes as an argument, so it is always printed. Per-project marker counts
 * cost one {@code findMarkers} call each (~128 projects in the target workspace); pass
 * {@code counts=false} to skip them when only the names are needed.
 */
public class ListProjectsTool implements Tool {

	private static final int DEFAULT_LIMIT = 100;

	/** Short names for the natures worth seeing at a glance; anything else prints its last segment. */
	private static final Map<String, String> NATURE_NAMES = Map.of(
		"org.eclipse.jdt.core.javanature", "java",
		"org.eclipse.buildship.core.gradleprojectnature", "gradle",
		"org.eclipse.m2e.core.maven2Nature", "maven",
		"org.eclipse.pde.PluginNature", "pde",
		"org.eclipse.pde.FeatureNature", "feature");

	@Override
	public String name() {
		return "list_projects";
	}

	@Override
	public String description() {
		return "List the projects in the Eclipse workspace: name, open/closed state, natures " +
			"(java, gradle, maven, …), error/warning counts, and filesystem location. Use it to " +
			"map an on-disk path to the project name that get_problems and manage_projects take, " +
			"or to find which projects have errors. Closed projects report neither natures nor " +
			"markers. Pass counts=false to skip the per-project marker queries on a large " +
			"workspace.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		return Map.of(
			"type", "object",
			"properties", Map.of(
				"filter", Schemas.stringProp("Case-insensitive substring to match against " +
					"project names"),
				"closed", Schemas.boolProp("Include closed projects (default true)"),
				"counts", Schemas.boolProp("Include per-project error/warning counts " +
					"(default true; false is faster on a large workspace)"),
				"offset", Schemas.intProp("Skip this many matches (default 0)"),
				"limit", Schemas.intProp("Maximum projects to return (default " +
					DEFAULT_LIMIT + ")")));
	}

	@Override
	public boolean isReadOnly() {
		return true;
	}

	@Override
	public McpSchema.CallToolResult execute(Map<String, Object> args, IWorkspace workspace) {
		String filter = Args.stringArg(args, "filter", "").toLowerCase();
		boolean includeClosed = Args.boolArg(args, "closed", true);
		boolean counts = Args.boolArg(args, "counts", true);
		int offset = Math.max(0, Args.intArg(args, "offset", 0));
		int limit = Math.max(1, Args.intArg(args, "limit", DEFAULT_LIMIT));

		List<IProject> matches = new ArrayList<>();
		for (IProject project : workspace.getRoot().getProjects()) {
			if (!includeClosed && !project.isOpen()) {
				continue;
			}
			if (!filter.isEmpty() && !project.getName().toLowerCase().contains(filter)) {
				continue;
			}
			matches.add(project);
		}
		matches.sort(Comparator.comparing(IProject::getName));

		if (matches.isEmpty()) {
			return Results.ok("No projects" + (filter.isEmpty() ? "" : " matching '" + filter + "'") +
				(includeClosed ? "" : " (open projects only)") + " in the workspace.");
		}

		List<IProject> window = matches.stream().skip(offset).limit(limit).toList();
		StringBuilder sb = new StringBuilder();
		for (IProject project : window) {
			sb.append(describe(project, counts)).append('\n');
		}
		sb.append(Results.paginationFooter(window.size(), offset, matches.size()));
		return Results.ok(sb.toString());
	}

	/** {@code name [open] natures=java,gradle errors=0 warnings=3 /path/on/disk} */
	private static String describe(IProject project, boolean counts) {
		StringBuilder sb = new StringBuilder(project.getName());
		if (!project.isOpen()) {
			// A closed project has neither a readable description nor markers — say so once and
			// skip both fields rather than printing zeros that would read as "clean".
			return sb.append(" [closed] ").append(location(project)).toString();
		}
		sb.append(" [open] natures=").append(natures(project));
		if (counts) {
			int[] problems = problemCounts(project);
			if (problems == null) {
				sb.append(" errors=? warnings=?");
			}
			else {
				sb.append(" errors=").append(problems[0]).append(" warnings=").append(problems[1]);
			}
		}
		return sb.append(' ').append(location(project)).toString();
	}

	private static String natures(IProject project) {
		try {
			String[] ids = project.getDescription().getNatureIds();
			if (ids.length == 0) {
				return "-";
			}
			StringBuilder sb = new StringBuilder();
			for (String id : ids) {
				if (sb.length() > 0) {
					sb.append(',');
				}
				sb.append(shortNature(id));
			}
			return sb.toString();
		}
		catch (CoreException e) {
			return "?";
		}
	}

	private static String shortNature(String id) {
		String name = NATURE_NAMES.get(id);
		if (name != null) {
			return name;
		}
		int dot = id.lastIndexOf('.');
		return dot >= 0 && dot < id.length() - 1 ? id.substring(dot + 1) : id;
	}

	/** {@code {errors, warnings}}, or null if the markers could not be read. */
	private static int[] problemCounts(IProject project) {
		try {
			int[] counts = new int[2];
			for (IMarker marker : project.findMarkers(IMarker.PROBLEM, true,
				IResource.DEPTH_INFINITE)) {
				switch (marker.getAttribute(IMarker.SEVERITY, -1)) {
					case IMarker.SEVERITY_ERROR -> counts[0]++;
					case IMarker.SEVERITY_WARNING -> counts[1]++;
					default -> {
						// info / unset severity is not summarised on a listing line
					}
				}
			}
			return counts;
		}
		catch (CoreException e) {
			return null;
		}
	}

	private static String location(IProject project) {
		IPath path = project.getLocation();
		return path != null ? path.toOSString() : String.valueOf(project.getLocationURI());
	}
}

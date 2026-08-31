package ebbex.eclipsemcpserver.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.Args;
import ebbex.eclipsemcpserver.util.BuildQueue;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Schemas;
import ebbex.eclipsemcpserver.util.WorkspaceOps;
import ebbex.eclipsemcpserver.util.Workspaces;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Make on-disk edits visible to Eclipse, compile them, and report what that did to the problem
 * markers &mdash; plus the project open/close lifecycle those need.
 *
 * <p><b>{@code op=refresh} is the load-bearing one.</b> This server's callers edit files directly
 * on the shared filesystem; Eclipse does not see those writes until the workspace is refreshed.
 * So {@code refresh} is the whole IDE-mediated editing path for them: refresh &rarr; build &rarr;
 * problems, in one call, returning a marker delta rather than "ok". It is deliberately blunt
 * about what it does <em>not</em> know: if the wait budget runs out while builders are still
 * running, it says the counts may still move instead of reporting a number it cannot stand behind.
 *
 * <p><b>Two different waits, both bounded.</b> A build is enqueued on the shared {@link BuildQueue}
 * (one worker, deduplicated per project, so a second {@code op=build} on the same project never
 * stacks a second build), then awaited for {@code wait_seconds}. But the queue going idle only
 * means <em>our</em> build call returned: refreshing or building also schedules the platform's own
 * auto-build job, and markers keep moving while that runs. So the platform's build job families
 * are polled to emptiness within the same budget before the "after" marker snapshot is taken.
 * {@code IJobManager.join(FAMILY_AUTO_BUILD, …)} would be the obvious call and is not used: it is
 * unbounded, and a cold build of this workspace would hold the MCP request open for minutes.
 *
 * <p><b>Scheduling rules</b> are the reason {@link WorkspaceOps} exists; see its javadoc for the
 * decompiled evidence. In short: {@code refreshLocal} and {@code open}/{@code close} run inside
 * {@code WorkspaceOps.run} under the factory's {@code refreshRule}/{@code modifyRule} for the
 * target, while {@code build} is called bare because it takes the (root) build rule itself and
 * nesting it under any other rule is an error.
 */
public class ManageProjectsTool implements Tool {

	private static final List<String> OPS = List.of("refresh", "build", "clean", "open", "close");

	private static final int DEFAULT_WAIT_SECONDS = 20;

	/** A caller can wait longer than the default, but not forever — the HTTP request is held. */
	private static final int MAX_WAIT_SECONDS = 600;

	/** How often to re-check whether the platform's own build jobs have drained. */
	private static final long JOB_POLL_MS = 100;

	private final BuildQueue buildQueue;

	public ManageProjectsTool(BuildQueue buildQueue) {
		this.buildQueue = buildQueue;
	}

	@Override
	public String name() {
		return "manage_projects";
	}

	@Override
	public String description() {
		return "Refresh, build, clean, open or close workspace projects. op=refresh is the one to " +
			"use after editing files on disk from outside Eclipse: it makes the changes visible " +
			"to the IDE and then (build=true, the default) runs an incremental build, reporting " +
			"the error/warning marker delta — the whole edit -> compile -> problems loop in one " +
			"call. op=build runs an incremental build; op=clean discards the build output and " +
			"rebuilds from scratch (slow — a whole-workspace clean will not finish inside " +
			"wait_seconds). 'project' names one project; omit it for refresh/build/clean to mean " +
			"the whole workspace, and it is required for open/close. Builds are queued one at a " +
			"time and deduplicated per project, then awaited up to wait_seconds; if the wait runs " +
			"out the build continues in the background and the report says so — poll get_problems " +
			"for the outcome. Marker counts are only trustworthy once the report says the build " +
			"finished.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		return Map.of(
			"type", "object",
			"properties", Map.of(
				"op", Schemas.enumProp("What to do", OPS),
				"project", Schemas.stringProp("Project name (list_projects shows them). For " +
					"refresh/build/clean, omitting it means the whole workspace; required for " +
					"open and close."),
				"wait_seconds", Schemas.intProp("How long to wait for the build to finish before " +
					"returning 'still building' (default " + DEFAULT_WAIT_SECONDS + ", max " +
					MAX_WAIT_SECONDS + "). The build is never cancelled by this bound."),
				"build", Schemas.boolProp("op=refresh only: run an incremental build after " +
					"refreshing (default true). false refreshes and returns immediately.")),
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
		if (op == null || !OPS.contains(op)) {
			throw new IllegalArgumentException("op must be one of " + OPS);
		}
		String projectName = Args.stringArg(args, "project", null);
		if (projectName != null && projectName.isBlank()) {
			projectName = null;
		}
		int waitSeconds = Math.max(0,
			Math.min(MAX_WAIT_SECONDS, Args.intArg(args, "wait_seconds", DEFAULT_WAIT_SECONDS)));

		return switch (op) {
			case "refresh" -> refresh(workspace, projectName,
				Args.boolArg(args, "build", true), waitSeconds);
			case "build" -> Results.ok(buildReport(workspace,
				optionalOpenProject(workspace, projectName),
				IncrementalProjectBuilder.INCREMENTAL_BUILD, waitSeconds));
			case "clean" -> Results.ok(buildReport(workspace,
				optionalOpenProject(workspace, projectName),
				IncrementalProjectBuilder.CLEAN_BUILD, waitSeconds));
			case "open" -> open(workspace, requireName(projectName, op));
			case "close" -> close(workspace, requireName(projectName, op));
			default -> Results.error("unhandled op " + op);
		};
	}

	// ---- op=refresh ----

	/**
	 * Reconcile the workspace with the filesystem, then (by default) compile.
	 *
	 * <p>The refresh is one {@code WorkspaceOps.run} rather than a per-project loop precisely so
	 * that the platform fires <em>one</em> resource delta: JDT's builder reacts to deltas, and N
	 * separate operations would mean N build triggers for one logical edit.
	 */
	private McpSchema.CallToolResult refresh(IWorkspace workspace, String projectName,
			boolean build, int waitSeconds) throws Exception {
		IProject project = optionalOpenProject(workspace, projectName);
		// refreshRule(root) is the root itself, refreshRule(project) is (by default) the project;
		// either way this is exactly the rule refreshLocal would begin on its own.
		IResource target = project != null ? project : workspace.getRoot();
		ISchedulingRule rule = workspace.getRuleFactory().refreshRule(target);

		long started = System.currentTimeMillis();
		WorkspaceOps.run(workspace, rule,
			monitor -> target.refreshLocal(IResource.DEPTH_INFINITE, monitor));
		long elapsed = System.currentTimeMillis() - started;

		StringBuilder out = new StringBuilder();
		if (project != null) {
			out.append("Refreshed project '").append(project.getName())
					.append("' from disk (recursive, ").append(elapsed).append(" ms).");
		}
		else {
			out.append("Refreshed the whole workspace from disk (recursive, ")
					.append(openProjects(workspace)).append(" open project(s), ")
					.append(elapsed).append(" ms). Closed projects are not refreshed.");
		}

		if (!build) {
			// The one case where auto-build state explains a surprise, so it is stated.
			out.append('\n').append(workspace.getDescription().isAutoBuilding()
					? "No build was requested (build=false), but auto-build is on, so Eclipse " +
						"compiles the changed files in the background — poll get_problems."
					: "No build was requested (build=false) and auto-build is off: the changed " +
						"files will not compile, and get_problems will keep showing stale " +
						"markers, until you call op=build.");
			return Results.ok(out.toString());
		}
		out.append('\n').append(buildReport(workspace, project,
			IncrementalProjectBuilder.INCREMENTAL_BUILD, waitSeconds));
		return Results.ok(out.toString());
	}

	// ---- op=build / op=clean, and the build half of op=refresh ----

	/**
	 * Enqueue a build of {@code project} (or of the whole workspace when null), wait for it
	 * within {@code waitSeconds}, and describe what it did to the problem markers.
	 *
	 * <p>{@code CLEAN_BUILD} is passed alone rather than followed by an incremental build:
	 * the platform's clean discards the build state and the builders rebuild their output from
	 * scratch for that kind, so a second call would only build what the clean already built.
	 *
	 * @return a plain-text report; never throws for a build failure, which is reported instead
	 */
	private String buildReport(IWorkspace workspace, IProject project, int kind, int waitSeconds)
			throws Exception {
		IResource scope = project != null ? project : workspace.getRoot();
		String scopeName = project != null ? "project '" + project.getName() + "'"
				: "the whole workspace";
		String key = project != null ? project.getName() : "*";
		String what = kind == IncrementalProjectBuilder.CLEAN_BUILD ? "Clean" : "Build";

		Counts before = countProblems(scope);

		AtomicReference<Throwable> failure = new AtomicReference<>();
		boolean queued = buildQueue.submit(key, () -> {
			try {
				// NOT inside WorkspaceOps.run: both of these begin the platform's own build rule
				// (the workspace root), and nesting that under another rule is a rule-conflict
				// error rather than a lock upgrade.
				if (project != null) {
					project.build(kind, new NullProgressMonitor());
				}
				else {
					workspace.build(kind, new NullProgressMonitor());
				}
			}
			catch (Throwable t) {
				failure.set(t);
			}
		});
		if (!queued) {
			return "A build of " + key + " is already queued or running — not queued again; poll " +
				"get_problems (or get_workspace_info for build state). Marker counts are " +
				"unchanged by this call.";
		}

		long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
		try {
			buildQueue.awaitIdle(Math.max(0, deadline - System.currentTimeMillis()));
		}
		catch (TimeoutException e) {
			return what + " of " + scopeName + " is still building after " + waitSeconds +
				"s — the build continues in the background; poll get_problems (or " +
				"get_workspace_info for build state).";
		}
		// Our call returned, but the auto-build it triggered may still be writing markers.
		boolean settled = awaitBuildJobs(deadline);
		Counts after = countProblems(scope);

		StringBuilder out = new StringBuilder(what).append(" of ").append(scopeName);
		Throwable t = failure.get();
		if (t != null) {
			out.append(" failed: ").append(describe(t)).append(". Markers now: ");
		}
		else {
			out.append(" finished: ");
		}
		out.append("errors ").append(before.errors()).append(" -> ").append(after.errors())
				.append(delta(before.errors(), after.errors()))
				.append(", warnings ").append(before.warnings()).append(" -> ")
				.append(after.warnings())
				.append(delta(before.warnings(), after.warnings())).append('.');
		if (!settled) {
			out.append("\nEclipse's own build jobs were still running when the ")
					.append(waitSeconds)
					.append("s budget ran out, so these counts may still move — poll get_problems.");
		}
		return out.toString();
	}

	/**
	 * Wait until no build job is left in the platform's queues, or the deadline passes.
	 *
	 * <p>Polling rather than {@code IJobManager.join(FAMILY_AUTO_BUILD, monitor)} because join is
	 * unbounded: it would hold the MCP request for however long a cold build of a 128-project
	 * workspace takes. Both families are checked — {@code FAMILY_AUTO_BUILD} for the build the
	 * refresh triggered, {@code FAMILY_MANUAL_BUILD} for one a human started in the IDE.
	 *
	 * @return true if the queues drained, false if the budget ran out first
	 */
	private static boolean awaitBuildJobs(long deadline) throws InterruptedException {
		IJobManager jobs = Job.getJobManager();
		while (true) {
			if (jobs.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length == 0 &&
				jobs.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length == 0) {
				return true;
			}
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				return false;
			}
			Thread.sleep(Math.min(JOB_POLL_MS, remaining));
		}
	}

	// ---- op=open / op=close ----

	private McpSchema.CallToolResult open(IWorkspace workspace, String projectName)
			throws CoreException {
		IProject project = Workspaces.requireProject(workspace, projectName);
		if (project.isOpen()) {
			return Results.ok("Project '" + project.getName() + "' is already open — nothing to do.");
		}
		WorkspaceOps.run(workspace, workspace.getRuleFactory().modifyRule(project),
			monitor -> project.open(monitor));
		return Results.ok("Opened project '" + project.getName() + "'. " +
			(workspace.getDescription().isAutoBuilding()
					? "Auto-build is on, so Eclipse is compiling it now — poll get_problems."
					: "Auto-build is off; call op=build to compile it."));
	}

	private McpSchema.CallToolResult close(IWorkspace workspace, String projectName)
			throws CoreException {
		IProject project = Workspaces.requireProject(workspace, projectName);
		if (!project.isOpen()) {
			return Results.ok("Project '" + project.getName() +
				"' is already closed — nothing to do.");
		}
		WorkspaceOps.run(workspace, workspace.getRuleFactory().modifyRule(project),
			monitor -> project.close(monitor));
		return Results.ok("Closed project '" + project.getName() +
			"'. Its problem markers are gone from get_problems until it is opened again, and " +
			"projects depending on it will report unresolved references.");
	}

	// ---- helpers ----

	private record Counts(int errors, int warnings) {
	}

	/**
	 * Error and warning marker counts under {@code scope}.
	 *
	 * <p>Read defensively per marker: a build running alongside this deletes and recreates
	 * markers, so an individual handle can go stale between {@code findMarkers} and the attribute
	 * read. A stale marker is skipped rather than failing the whole report.
	 */
	private static Counts countProblems(IResource scope) throws CoreException {
		int errors = 0;
		int warnings = 0;
		for (IMarker marker : scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
			int severity;
			try {
				severity = marker.getAttribute(IMarker.SEVERITY, -1);
			}
			catch (Exception e) {
				continue;
			}
			if (severity == IMarker.SEVERITY_ERROR) {
				errors++;
			}
			else if (severity == IMarker.SEVERITY_WARNING) {
				warnings++;
			}
		}
		return new Counts(errors, warnings);
	}

	/** " (-9)" / " (+2)" / "" when nothing moved. */
	private static String delta(int before, int after) {
		int change = after - before;
		if (change == 0) {
			return "";
		}
		return change > 0 ? " (+" + change + ")" : " (" + change + ")";
	}

	/** The named project, or null for "the whole workspace"; refuses a closed project. */
	private static IProject optionalOpenProject(IWorkspace workspace, String projectName) {
		if (projectName == null) {
			return null;
		}
		IProject project = Workspaces.requireProject(workspace, projectName);
		if (!project.isOpen()) {
			// refreshLocal and build are both no-ops on a closed project, and findMarkers throws
			// on one — saying so beats reporting "errors 0 -> 0".
			throw new IllegalArgumentException("Project '" + project.getName() +
				"' is closed: it cannot be refreshed or built. Run op=open first.");
		}
		return project;
	}

	private static String requireName(String projectName, String op) {
		if (projectName == null) {
			throw new IllegalArgumentException("project is required for op=" + op +
				" (list_projects shows the names)");
		}
		return projectName;
	}

	private static int openProjects(IWorkspace workspace) {
		int open = 0;
		for (IProject project : workspace.getRoot().getProjects()) {
			if (project.isOpen()) {
				open++;
			}
		}
		return open;
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank() ? t.toString()
				: message.replaceAll("\\s+", " ").trim();
	}
}

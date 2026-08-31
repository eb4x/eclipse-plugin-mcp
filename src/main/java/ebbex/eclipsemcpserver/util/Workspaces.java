package ebbex.eclipsemcpserver.util;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Workspace readiness gate and project resolution (the {@code Locations} role).
 *
 * <p>This bundle starts eagerly at start level 4 and may activate before
 * {@code org.eclipse.core.resources} has bound the instance area
 * ({@code -Dosgi.dataAreaRequiresExplicitInit=true}), so the workspace is resolved
 * lazily per request, never cached at activation.
 */
public final class Workspaces {

	private Workspaces() {
	}

	/** The workspace, or null while the resources plugin has not initialized it yet. */
	public static IWorkspace workspaceOrNull() {
		try {
			return ResourcesPlugin.getWorkspace();
		}
		catch (IllegalStateException | NoClassDefFoundError e) {
			return null;
		}
	}

	public static boolean isReady() {
		return workspaceOrNull() != null;
	}

	/**
	 * Resolve an existing project by name, throwing an {@link IllegalArgumentException}
	 * whose message tells the caller how to find valid names.
	 */
	public static IProject requireProject(IWorkspace workspace, String name) {
		IProject project = workspace.getRoot().getProject(name);
		if (!project.exists()) {
			throw new IllegalArgumentException("No project named '" + name +
				"' in the workspace (list_projects shows the names)");
		}
		return project;
	}
}

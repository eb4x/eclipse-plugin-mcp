package ebbex.eclipsemcpserver.tools.launch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.IResourceProxyVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

/**
 * Resolves launch configurations by merging two sources: the launch manager's index and
 * the shared {@code *.launch} files sitting in open projects.
 *
 * <p>The platform's {@code LaunchManager} imports shared launch files only when a resource
 * delta touches them, and that import does not survive an IDE restart — so a config checked
 * into a project (the Ghidra repo's {@code Features Base/.launch/Ghidra.launch}) is
 * routinely invisible by name right after startup (see docs/mcp-feedback.md). Rather than
 * make every caller perform the touch-the-file-and-refresh ritual, these lookups fall back
 * to scanning the in-memory resource tree for {@code .launch} files and handing back
 * {@link ILaunchManager#getLaunchConfiguration(IFile)} handles, which launch fine without
 * ever being indexed.
 *
 * <p>The scan is a proxy-visitor walk of the already-loaded resource tree — no disk I/O —
 * and runs only on listing calls and name misses, so the cost is a few milliseconds across
 * this workspace's ~125 projects.
 */
final class LaunchConfigs {

	private static final String LAUNCH_EXTENSION = ".launch";

	private LaunchConfigs() {
	}

	/**
	 * Every configuration addressable by name: the manager's own, plus a handle for each
	 * shared {@code .launch} file in an open project that the manager has not indexed.
	 * Deduped by name; the manager's entry wins (same file, same content).
	 */
	static List<ILaunchConfiguration> all(ILaunchManager manager, IWorkspace workspace)
			throws CoreException {
		Map<String, ILaunchConfiguration> byName = new LinkedHashMap<>();
		for (ILaunchConfiguration config : manager.getLaunchConfigurations()) {
			byName.putIfAbsent(config.getName(), config);
		}
		if (workspace != null) {
			for (IFile file : sharedLaunchFiles(workspace)) {
				ILaunchConfiguration config = manager.getLaunchConfiguration(file);
				byName.putIfAbsent(config.getName(), config);
			}
		}
		return new ArrayList<>(byName.values());
	}

	/** Exact-name lookup over {@link #all}; null when no source knows the name. */
	static ILaunchConfiguration byName(ILaunchManager manager, IWorkspace workspace, String name)
			throws CoreException {
		for (ILaunchConfiguration config : all(manager, workspace)) {
			if (config.getName().equals(name)) {
				return config;
			}
		}
		return null;
	}

	/**
	 * All {@code *.launch} files in open projects. A shared config's name is its file name
	 * minus the extension, which is exactly how the platform names imported ones.
	 */
	private static List<IFile> sharedLaunchFiles(IWorkspace workspace) throws CoreException {
		List<IFile> files = new ArrayList<>();
		IResourceProxyVisitor visitor = (IResourceProxy proxy) -> {
			if (proxy.getType() == IResource.FILE) {
				if (proxy.getName().endsWith(LAUNCH_EXTENSION)) {
					files.add((IFile) proxy.requestResource());
				}
				return false;
			}
			return true;
		};
		for (IProject project : workspace.getRoot().getProjects()) {
			if (project.isOpen()) {
				project.accept(visitor, IResource.NONE);
			}
		}
		return files;
	}
}

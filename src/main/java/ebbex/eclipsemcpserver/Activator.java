package ebbex.eclipsemcpserver;

import java.util.List;

import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import ebbex.eclipsemcpserver.util.BuildInfo;
import ebbex.eclipsemcpserver.util.BuildQueue;
import ebbex.eclipsemcpserver.util.ConsoleCapture;
import ebbex.eclipsemcpserver.util.Logs;
import ebbex.eclipsemcpserver.util.Workspaces;

/**
 * Bundle activator (eager start via the {@code bundles.info} autostart flag — no
 * {@code org.eclipse.ui.startup}, no workbench dependency). {@link #start} returns in
 * milliseconds: the actual server bring-up runs in a system {@link Job} so a slow bind
 * never delays IDE startup.
 *
 * <p>Bind failure is a logged warning, not a crash: the bundle stays alive serverless
 * and {@code GET /version} (absent) diagnoses it. There is no restart affordance in the
 * MVP — restart the IDE (or fix the port with {@code -Dmcp.server.port}).
 */
public class Activator implements BundleActivator {

	private static final int DEFAULT_PORT = 8124;
	private static final String PORT_PROPERTY = "mcp.server.port";
	private static final String HOST = "127.0.0.1";

	private McpHttpServer server;
	private ConsoleCapture consoleCapture;
	private BuildQueue buildQueue;
	private Job startupJob;

	@Override
	public void start(BundleContext context) {
		int port = Integer.getInteger(PORT_PROPERTY, DEFAULT_PORT);

		consoleCapture = new ConsoleCapture();
		buildQueue = new BuildQueue();
		List<McpHttpServer.Endpoint> endpoints = Endpoints.build(
			ToolRegistry.workspaceTools(buildQueue),
			ToolRegistry.launchTools(consoleCapture));
		server = new McpHttpServer(HOST, port, endpoints);

		startupJob = Job.createSystem("Start Eclipse MCP server", monitor -> {
			// Jetty needs no workspace — bring the port up first so GET /version
			// answers as early as possible.
			try {
				server.start();
			}
			catch (Throwable t) {
				Logs.warn("MCP server failed to start on " + HOST + ":" + port + " (" + t +
					"). Another instance may hold the port; override with -D" + PORT_PROPERTY +
					"=<port> and restart Eclipse. Build: " + BuildInfo.describe());
			}

			// Touching DebugPlugin before the instance area is bound
			// (-Dosgi.dataAreaRequiresExplicitInit=true) makes LaunchManager's static
			// initializer throw, and an ExceptionInInitializerError poisons the class
			// for the whole session — every later launch-tool call fails. The workspace
			// becoming available is the signal that the instance area is bound, so wait
			// for it (bounded; the chooser may sit forever if a human never picks).
			long deadline = System.currentTimeMillis() + 10 * 60_000;
			while (!Workspaces.isReady() && System.currentTimeMillis() < deadline &&
				!monitor.isCanceled()) {
				try {
					Thread.sleep(500);
				}
				catch (InterruptedException e) {
					return;
				}
			}
			if (monitor.isCanceled()) {
				return;
			}
			if (!Workspaces.isReady()) {
				Logs.warn("MCP server: workspace never became available; console capture " +
					"was not installed (launch tools will report the same gate)");
				return;
			}
			// Now safe — and still early enough to be listening before any user launch.
			try {
				consoleCapture.install();
			}
			catch (Throwable t) {
				Logs.error("MCP server: console capture unavailable", t);
			}
		});
		startupJob.setSystem(true);
		startupJob.schedule();
	}

	@Override
	public void stop(BundleContext context) {
		// Equinox stops bundles serially — every step here is bounded and wrapped so a
		// wedged component can never hang IDE exit. User launches are left alone.
		if (startupJob != null) {
			try {
				startupJob.cancel();
				startupJob.join(2000, null);
			}
			catch (Exception ignored) {
			}
			startupJob = null;
		}
		if (consoleCapture != null) {
			try {
				consoleCapture.dispose();
			}
			catch (Throwable t) {
				Logs.warn("MCP server: console capture dispose failed: " + t);
			}
			consoleCapture = null;
		}
		if (buildQueue != null) {
			try {
				buildQueue.shutdown();
			}
			catch (Throwable t) {
				Logs.warn("MCP server: build queue shutdown failed: " + t);
			}
			buildQueue = null;
		}
		if (server != null) {
			server.stop();
			server = null;
		}
	}
}

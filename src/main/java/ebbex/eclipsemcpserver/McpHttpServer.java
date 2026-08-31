package ebbex.eclipsemcpserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import ebbex.eclipsemcpserver.util.BuildInfo;
import ebbex.eclipsemcpserver.util.Logs;
import ebbex.eclipsemcpserver.util.Workspaces;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Embedded Jetty server hosting one MCP streamable-HTTP endpoint per tool group,
 * all on a single port. Each {@link Endpoint} is mounted at {@code /mcp/<path>}
 * (e.g. {@code /mcp/workspace}, {@code /mcp/launch}) as an independent MCP
 * server, so clients enable only the group(s) they need.
 *
 * <p>The entire Jetty/servlet/Jackson/MCP stack is private to this bundle
 * (nested jars, nothing exported or imported), so it cannot cross-wire with the
 * platform's own Jetty 12.1.9/ee8 or Jackson 2 bundles.
 */
public class McpHttpServer {

	public static final String BASE_PATH = "/mcp";
	public static final String VERSION_PATH = "/version";

	/** One named MCP endpoint: a path segment plus its tool specifications. */
	public record Endpoint(String path, String serverInfoName,
			List<McpServerFeatures.SyncToolSpecification> specs) {

		public String mcpEndpoint() {
			return BASE_PATH + "/" + path;
		}
	}

	private final String host;
	private final int port;
	private final List<Endpoint> endpoints;

	private Server jetty;
	private final List<McpSyncServer> mcpServers = new ArrayList<>();

	public McpHttpServer(String host, int port, List<Endpoint> endpoints) {
		this.host = host;
		this.port = port;
		this.endpoints = endpoints;
	}

	public void start() throws Exception {
		// Jetty, Jackson 3 and reactor all resolve ServiceLoader providers against the
		// thread context classloader. On the framework's start thread (and on any thread
		// not spawned by us) that is not this bundle's loader, and the providers live in
		// our nested jars — so pin the TCCL for the whole bring-up.
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(McpHttpServer.class.getClassLoader());
		try {
			startWithOwnClassLoader();
		}
		finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	private void startWithOwnClassLoader() throws Exception {
		quietVerboseLoggers();
		// Never McpJsonMapper.getDefault(): its ServiceLoader/DS discovery is not
		// processed for nested jars. Construct the mapper explicitly.
		var jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

		jetty = new Server();
		ServerConnector connector = new ServerConnector(jetty);
		connector.setHost(host);
		connector.setPort(port);
		jetty.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler("/");

		for (Endpoint endpoint : endpoints) {
			var transport = HttpServletStreamableServerTransportProvider.builder()
					.jsonMapper(jsonMapper)
					.mcpEndpoint(endpoint.mcpEndpoint())
					.build();

			McpSyncServer mcpServer = McpServer.sync(transport)
					.serverInfo(endpoint.serverInfoName(), BuildInfo.version())
					.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
					.tools(endpoint.specs())
					.immediateExecution(true)
					.build();
			mcpServers.add(mcpServer);

			ServletHolder holder = new ServletHolder("mcp-" + endpoint.path(), transport);
			holder.setAsyncSupported(true);
			context.addServlet(holder, endpoint.mcpEndpoint() + "/*");
			context.addServlet(holder, endpoint.mcpEndpoint());
		}

		// Plain-HTTP identity/readiness probe: `curl http://host:port/version` answers both
		// "is the server up?" and "is it my build?" without an MCP session handshake.
		context.addServlet(new ServletHolder("version", new VersionServlet()), VERSION_PATH);

		jetty.setHandler(context);
		jetty.start();

		StringBuilder sb = new StringBuilder("MCP server listening on http://" + host + ":" + port +
			" (build: " + BuildInfo.describe() + ") with " + endpoints.size() + " endpoint(s):");
		for (Endpoint endpoint : endpoints) {
			sb.append("\n  ").append(endpoint.mcpEndpoint()).append("  (")
					.append(endpoint.specs().size()).append(" tools)");
		}
		sb.append("\n  ").append(VERSION_PATH).append("  (plain-HTTP build/readiness probe)");
		Logs.info(sb.toString());
	}

	private static class VersionServlet extends HttpServlet {
		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response)
				throws IOException {
			Bundle bundle = FrameworkUtil.getBundle(McpHttpServer.class);
			var workspace = Workspaces.workspaceOrNull();
			response.setContentType("text/plain;charset=utf-8");
			var out = response.getWriter();
			out.println("EclipseMCPServer " + BuildInfo.describe());
			out.println("bundle: " + (bundle != null ? bundle.getVersion() : "unknown"));
			out.println("eclipse: " + System.getProperty("eclipse.buildId", "unknown"));
			out.println("workspace: " + (workspace != null
					? workspace.getRoot().getLocation()
					: "(not initialized yet)"));
		}
	}

	public boolean isRunning() {
		return jetty != null && jetty.isRunning();
	}

	/**
	 * Our slf4j binding is a private slf4j-simple configured by the bundle-root
	 * {@code simplelogger.properties}. That file is read from the classpath on first
	 * logger creation, which happens under the TCCL pinned in {@link #start()}; the
	 * system properties here are belt-and-braces for any earlier touch.
	 */
	private static void quietVerboseLoggers() {
		try {
			System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		}
		catch (Throwable t) {
			// logging is best-effort; never let it stop the server
		}
	}

	public void stop() {
		for (McpSyncServer mcpServer : mcpServers) {
			try {
				mcpServer.close();
			}
			catch (Exception e) {
				Logs.warn("Error closing MCP server: " + e.getMessage());
			}
		}
		mcpServers.clear();
		if (jetty != null) {
			try {
				jetty.stop();
			}
			catch (Exception e) {
				Logs.warn("Error stopping Jetty: " + e.getMessage());
			}
			jetty = null;
		}
	}
}

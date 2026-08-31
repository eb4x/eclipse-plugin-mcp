package ebbex.eclipsemcpserver;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IWorkspace;

import ebbex.eclipsemcpserver.util.Logs;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Workspaces;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Builds the MCP endpoints (tool groups) and their per-tool call handlers.
 *
 * <ul>
 * <li>{@code workspace} &mdash; projects, builds, problem markers, the platform log.</li>
 * <li>{@code launch} &mdash; launch configurations, processes, console output.</li>
 * </ul>
 *
 * Exception discipline (shared with the Ghidra sibling): {@link IllegalArgumentException}
 * is bad caller input and returns its bare message; anything else is logged and returns a
 * generic error. A throwing tool is never a transport-level failure.
 */
public final class Endpoints {

	private Endpoints() {
	}

	public static List<McpHttpServer.Endpoint> build(List<Tool> workspaceTools,
			List<Tool> launchTools) {
		return List.of(
			new McpHttpServer.Endpoint("workspace", "eclipse-workspace",
				workspaceTools.stream().map(Endpoints::spec).toList()),
			new McpHttpServer.Endpoint("launch", "eclipse-launch",
				launchTools.stream().map(Endpoints::spec).toList()));
	}

	private static McpServerFeatures.SyncToolSpecification spec(Tool tool) {
		McpSchema.Tool mcpTool = McpSchema.Tool.builder(tool.name(), tool.inputSchema())
				.description(tool.description())
				.annotations(McpSchema.ToolAnnotations.builder()
						.readOnlyHint(tool.isReadOnly())
						.build())
				.build();

		return McpServerFeatures.SyncToolSpecification.builder()
				.tool(mcpTool)
				.callHandler((exchange, request) -> {
					IWorkspace workspace = Workspaces.workspaceOrNull();
					if (tool.requiresWorkspace() && workspace == null) {
						return Results.error("The Eclipse workspace is not initialized yet " +
							"(the IDE may still be starting, or is running headless). " +
							"Retry shortly; read_log works without a workspace.");
					}
					Map<String, Object> args = arguments(request);
					try {
						return tool.execute(args, workspace);
					}
					catch (IllegalArgumentException e) {
						// Bad arguments (unresolvable project/config name, …): the message
						// already tells the caller what to fix — no class-name noise.
						return Results.error(e.getMessage() != null ? e.getMessage() : e.toString());
					}
					catch (Exception e) {
						Logs.error("tool " + tool.name() + " failed", e);
						return Results.error(tool.name() + " failed: " + e);
					}
				})
				.build();
	}

	private static Map<String, Object> arguments(McpSchema.CallToolRequest request) {
		return request.arguments() != null ? request.arguments() : Map.of();
	}
}

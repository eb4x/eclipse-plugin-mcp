package ebbex.eclipsemcpserver;

import java.util.Map;

import org.eclipse.core.resources.IWorkspace;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * An MCP tool exposed by this server. One interface for both endpoint groups
 * (the Ghidra sibling needs two because it injects different contexts; here
 * every tool reaches the workspace and the debug plugin statically).
 */
public interface Tool {

	String name();

	String description();

	/** JSON Schema 2020-12 for the tool's arguments, as plain maps ({@code Schemas} helpers). */
	Map<String, Object> inputSchema();

	/** True if the tool does not modify the workspace or launch anything. */
	boolean isReadOnly();

	/**
	 * True if the tool needs the workspace to be initialized. When false, the endpoint
	 * invokes the tool even before the resources plugin is up (and passes {@code null})
	 * — e.g. a log reader that must work when startup itself broke.
	 */
	default boolean requiresWorkspace() {
		return true;
	}

	McpSchema.CallToolResult execute(Map<String, Object> args, IWorkspace workspace)
			throws Exception;
}

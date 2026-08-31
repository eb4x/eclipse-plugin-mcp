package ebbex.eclipsemcpserver;

import java.util.List;

import ebbex.eclipsemcpserver.tools.GetProblemsTool;
import ebbex.eclipsemcpserver.tools.GetWorkspaceInfoTool;
import ebbex.eclipsemcpserver.tools.ListProjectsTool;
import ebbex.eclipsemcpserver.tools.ManageProjectsTool;
import ebbex.eclipsemcpserver.tools.ReadLogTool;
import ebbex.eclipsemcpserver.tools.launch.ListLaunchesTool;
import ebbex.eclipsemcpserver.tools.launch.ManageLaunchTool;
import ebbex.eclipsemcpserver.tools.launch.ReadConsoleTool;
import ebbex.eclipsemcpserver.util.BuildQueue;
import ebbex.eclipsemcpserver.util.ConsoleCapture;

/**
 * The fixed tool lists, one per endpoint. To add a tool: implement {@link Tool} in
 * {@code tools/} (or {@code tools/launch/}), register it here, and add a call to the
 * probe script.
 */
public final class ToolRegistry {

	private ToolRegistry() {
	}

	public static List<Tool> workspaceTools(BuildQueue buildQueue) {
		return List.of(
			new GetWorkspaceInfoTool(buildQueue),
			new ListProjectsTool(),
			new ManageProjectsTool(buildQueue),
			new GetProblemsTool(),
			new ReadLogTool());
	}

	public static List<Tool> launchTools(ConsoleCapture consoleCapture) {
		return List.of(
			new ListLaunchesTool(consoleCapture),
			new ManageLaunchTool(),
			new ReadConsoleTool(consoleCapture));
	}
}

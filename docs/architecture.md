# Architecture

How the bundle comes up, why the classpath is shaped the way it is, and the threading
rules every tool must respect. Read this before touching `Activator`, `McpHttpServer`,
anything under `util/`, or the launch tools.

## Bring-up

- `Activator` (eager via `bundles.info`, autostart=true) schedules a system Job that
  installs `ConsoleCapture` then starts `McpHttpServer` — the activator itself returns
  in milliseconds. There is no plugin.xml and no workbench dependency.
- The Job first waits for the workspace to be ready. That wait exists because of the
  `DebugPlugin` trap below; do not remove it.

## Classpath: nested jars, nothing exported or imported

- The whole third-party stack (MCP SDK 2.0.0, Jetty 12.1.4 ee11, jakarta.servlet 6.1,
  Jackson 3, reactor, slf4j + slf4j-simple) is **nested jars on `Bundle-ClassPath`,
  nothing exported or imported** — Eclipse's own Jetty (12.1.9/ee8) and Jackson 2
  bundles must never wire in. Do not add `Import-Package` for any of them; do not add
  `Export-Package` at all.
- **TCCL rule**: Jetty, Jackson and reactor resolve ServiceLoader providers against the
  thread context classloader; `McpHttpServer.start()` pins the TCCL to the bundle
  classloader for the whole bring-up. Never call `McpJsonMapper.getDefault()`
  (nested-jar services are not discovered) — the mapper is constructed explicitly.

## Threading

- Model reads (resources, markers, launch manager) run directly on Jetty HTTP threads.
- Mutations go through `util/WorkspaceOps` (one `IWorkspace.run` write path; never the
  workspace root rule).
- Builds go through `util/BuildQueue` (single worker, dedup per project, bounded wait
  then "still building — poll").
- `util/UiThread` is the only sanctioned door to the SWT thread (bounded, never
  `syncExec`) and is **unused by the MVP** — keep it that way unless a tool genuinely
  needs the UI.

## Launching

- Core API only (`ILaunchConfiguration.launch`), never `DebugUITools`. Pre-check error
  markers to dodge the modal "errors exist — proceed?" prompt; the launch call itself
  runs on a bounded side thread because a modal can still block it.
- **Never touch `DebugPlugin`/`LaunchManager` before the workspace is ready**: its
  static initializer needs the instance area, and the resulting
  `ExceptionInInitializerError` poisons launching for the whole session (see
  `docs/mcp-feedback.md`).
- Shared `.launch` files (checked into a project) are only indexed by the platform when
  a resource delta touches them, and the index does not survive an IDE restart. Since
  0.2.0 the launch tools work around it: `tools/launch/LaunchConfigs` merges the
  manager's index with a resource-tree scan for `*.launch` files, so shared configs
  stay addressable by name.
- `manage_launch` on a shared target (the `Ghidra` config serves several Claude sessions
  at once) is a cross-session action: `terminate_existing` kills a server other
  sessions may be mid-conversation with. Check with the other sessions
  (`ListAgents`/`SendMessage`) before restarting Ghidra on someone else's behalf;
  `list_launches kind=active` + `GET :8765/version` only prove what is running, not who
  is using it.

## Adding a tool

Implement `Tool` in `tools/` (or `tools/launch/`), register it in `ToolRegistry`, and
add a call to `tools/probe.sh` so `./gradlew probe` exercises it.

# MCP friction log

Every rough edge an agent hits while using (or building) this server gets an entry.
Fix entries in the same repo the fix lands in; re-verify against the live server.

Entry template:

```
## <date> <one-line symptom>
- **Context**: what was being attempted
- **Symptom**: what happened, verbatim where useful
- **Cause**: root cause once known
- **Fix / workaround**: what resolved it, and where
- **Verified**: how it was re-checked against the live server
```

## 2026-08-31 debug.core poisoned for the whole session by early activation

- **Context**: first live run of the bundle; `list_launches` failed with
  "The Eclipse debug core is not available in this IDE instance".
- **Symptom**: `.metadata/.log`: `LaunchManager.<clinit>` threw
  `IllegalStateException: The instance data location has not been specified yet`
  (from `ConsoleCapture.install()` at T+2s on our startup job), then every later
  touch of `LaunchManager` — including Eclipse's own launchview and
  `jdt.launching`'s compilation participant — failed with
  `NoClassDefFoundError: Could not initialize class ...LaunchManager`.
- **Cause**: `-Dosgi.dataAreaRequiresExplicitInit=true` defers instance-area
  binding; an `ExceptionInInitializerError` in a static initializer poisons the
  class for the whole session. Touching `DebugPlugin` before the workspace exists
  breaks launching for *everyone*, not just us.
- **Fix**: `Activator` starts Jetty first, then waits (bounded) for
  `Workspaces.isReady()` before `ConsoleCapture.install()`. Launch tools are
  already gated by `requiresWorkspace()`.
- **Verified**: restart → probe PASS → `list_launches` lists configurations.

## 2026-08-31 shared .launch files are invisible until a resource delta touches them

- **Context**: peers address the launch config by name ("Ghidra"); it lives in
  `Features Base/.launch/Ghidra.launch` (checked into the Ghidra repo) but
  `list_launches kind=configurations` did not show it — only workspace-local
  (JUnit) configs.
- **Symptom**: `manage_launch op=launch configuration=Ghidra` → "no such
  configuration".
- **Cause**: the platform `LaunchManager` indexes shared `.launch` files from
  resource deltas; a file that existed before the session started and never
  changes produces no delta, so it is never imported.
- **Workaround**: `touch` the `.launch` file on disk, then
  `manage_projects op=refresh project=<its project>` — the delta imports it and
  it stays addressable by name for the session.
- **Verified**: after touch+refresh, `list_launches` shows
  `Ghidra (Java Application)` and `manage_launch` launches it. Confirmed on the
  0.1.1 restart that the index does **not** survive an IDE restart — the
  touch+refresh dance was needed once per session.
- **Fix (0.2.0)**: `tools/launch/LaunchConfigs` merges the manager's index with
  a proxy-visitor scan of open projects for `*.launch` files, handing back
  `ILaunchManager.getLaunchConfiguration(IFile)` handles — shared configs are
  addressable by name (and visible in `list_launches kind=configurations`)
  straight after a restart, no ritual.
- **Verified**: freshly restarted IDE, no touch/refresh: `manage_launch
  op=launch configuration=Ghidra` resolves and launches. (Re-verify note kept
  current with the 0.2.0 rollout.)

## 2026-08-31 bare `list_launches {}` rejected — `kind` was required

- **Context**: the ghidra-plugin-rtlink session's independent raw-HTTP
  verification; the common "what's running?" question needed an argument.
- **Symptom**: `list_launches {}` fails schema validation (`kind` required).
- **Fix**: `kind` defaults to `active` (the common case); an explicit bad value
  still errors with the enum list. 0.1.1.
- **Verified**: pending the next coordinated Eclipse restart (the running
  Ghidra on 8765 is in use by peer sessions; not restarting for a nit).

## 2026-08-31 probe compared against a restamped buildinfo, always "stale"

- **Context**: first `./gradlew probe` after a successful install+restart.
- **Symptom**: `FAIL Eclipse is serving build … — restart Eclipse to load it`
  although the server was serving the freshly installed jar.
- **Cause**: `probe` depended on `generateBuildInfo`, which restamps `built=`
  on every run; the comparison source drifted from the artifact.
- **Fix**: `tools/probe.sh` now extracts `eclipsemcp-build.properties` from the
  newest built jar and compares against that; the task has no build dependencies.
- **Verified**: probe PASS against the running server; re-running any other
  gradle task no longer flips it to stale.

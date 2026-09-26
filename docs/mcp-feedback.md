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
- **Verified**: 2026-09-26 against the live 0.2.0 server (raw HTTP handshake,
  no arguments): `list_launches {}` returned `#2 Ghidra [run] running
  started=11:40:59 buffered=16 lines` — no schema error, and the default is
  indeed `active`.

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

## 2026-09-26 empty `list_launches kind=active` read as "the tool cannot see it"

- **Context**: a local agent was being taught to start and stop Ghidra through
  `eclipse-launch`. It called `list_launches` with no arguments on a freshly
  restarted IDE.
- **Symptom**: the result was `(none)`. The agent concluded: "returned nothing,
  which might be because it only lists Eclipse launch configurations, not all
  running processes on the system" — exactly backwards (a bare call defaults to
  `kind=active`, i.e. running launches), and it never tried
  `kind=configurations`, which had 16 entries including `Ghidra`.
- **Cause**: `(none)` names what is absent but not what to ask instead. An empty
  `kind=active` is the *normal* state of a just-started IDE, and it looks
  identical to a capability gap.
- **Fix (0.2.1)**: `ListLaunchesTool.emptyListing` makes the empty result
  kind-aware: `kind=active` says nothing has been launched in this session, that
  launches do not survive an IDE restart, that a process started outside Eclipse
  is never visible, and points at `kind=configurations` + `manage_launch`;
  `kind=configurations` distinguishes "no configurations" from "your filter
  matched none".
- **Verified**: 2026-09-26 on the live 0.2.1 server, freshly restarted so the
  session had no launches — the exact state that produced the confusion.
  `list_launches {}` now returns: `(none) — nothing has been launched in this
  Eclipse session. Launches do not survive an IDE restart, and a process started
  outside Eclipse is never visible here. Use list_launches kind=configurations
  to see what can be launched, then manage_launch op=launch
  configuration=<name>.` And `kind=configurations` with a non-matching filter:
  `(none matching filter 'zzzz') — drop the filter to list every
  configuration.`

## 2026-09-26 p2 kept our bundles.info line but reset the autostart flag

- **Context**: the org.eclipse.Java flatpak upgraded 4.40 → 4.41; afterwards
  :8124 refused connections and every peer's `eclipse-*` MCP server failed.
- **Symptom**: the bundle jar was still in the pool and the `bundles.info` line
  was still present — but ended `,4,false` instead of `,4,true`. The bundle
  installed and resolved, the Activator never ran: no startup banner in the
  Error Log, no listener, nothing in `.metadata/.log` mentioning us at all.
  `./gradlew installStatus` printed the line without objecting.
- **Cause**: a p2 operation rewriting `bundles.info` from the profile. The
  documented failure was the line being *dropped*; keeping it with the start
  flag cleared is the same hazard in a shape that passes a visual check.
- **Fix (0.2.1)**: `installStatus` asserts the trailing flag is `true` and that
  the line's version has a matching jar in `plugins/`, printing `line: BROKEN
  — …` with the remedy. The p2 note in `build.gradle` and CLAUDE.md now describe
  both shapes.
- **Verified**: run against a copy of the real pool with the flag flipped to
  `false` → `line: BROKEN — autostart flag is 'false', must be true; the
  Activator never runs and no server starts. Re-run ./gradlew install`; and with
  the jar renamed → `line: BROKEN — points at 0.2.0.202609261000, but plugins/
  has no …`. The real pool reports clean.

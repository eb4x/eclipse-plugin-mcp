# Eclipse MCP Server

An [MCP](https://modelcontextprotocol.io/) server embedded in the Eclipse IDE as a
plain OSGi bundle, so coding agents can drive Eclipse: refresh and build projects,
read JDT compile errors, and run/relaunch launch configurations and read their
console output. Sibling of
[ghidra-plugin-mcp](../ghidra-plugin-mcp) and built with the same stack: the
official MCP Java SDK (streamable HTTP transport), embedded Jetty 12 (ee11),
Jackson 3, Java 21 — all private to the bundle (nested jars, nothing exported),
so nothing can cross-wire with Eclipse's own Jetty/Jackson bundles.

## Design

- **Few, orthogonal tools** with `op`/`kind` enum discriminators and
  `offset`/`limit`/`filter` pagination, plain-text results. No tool-per-verb sprawl.
- **Two endpoints on one port** (default `127.0.0.1:8124`, `-Dmcp.server.port=` to
  override), so clients enable only the group they need:
  - `/mcp/workspace` — `get_workspace_info`, `list_projects`, `manage_projects`,
    `get_problems`, `read_log`
  - `/mcp/launch` — `list_launches`, `manage_launch`, `read_console`
- **No editing tools by design**: the agent edits files on disk (the flatpak shares
  the host filesystem); `manage_projects op=refresh` makes the edits visible and
  builds, `get_problems` reports the JDT errors. That one `op` replaces an entire
  remote-editing tool surface.
- **Everything runs off the UI thread** (the resource/JDT/debug model is
  concurrency-safe; only SWT is Display-confined and no MVP tool touches it), so a
  modal dialog can never hang a read. Launching, the one path a modal can still
  bite, is pre-checked and bounded.
- **No auth, loopback only.** Any local process running as this user can drive
  Eclipse — including launching arbitrary run configurations. That is equivalent to
  a local shell on a single-user workstation; do not use this on a shared host.

## Build & install

Requires a host JDK (25 works; sources target 21) and a `gradle.properties` (gitignored)
pointing at the Eclipse installation — see the checked-in comments and `CLAUDE.md`.

```
./gradlew check          # build + verify the bundle packaging
./gradlew install        # copy the jar into the p2 pool + add the bundles.info line
# restart Eclipse
./gradlew probe          # confirm the running server is the build you just made
```

`GET http://127.0.0.1:8124/version` answers "is it up, and is it my build?" without
an MCP handshake.

Note: the bundle is installed by writing a `bundles.info` line, not through p2, so
any p2 operation (install/update software, base upgrade) silently drops it —
`./gradlew installStatus` diagnoses, `./gradlew install` re-applies.

## Client configuration

```
claude mcp add --transport http eclipse-workspace http://127.0.0.1:8124/mcp/workspace
claude mcp add --transport http eclipse-launch    http://127.0.0.1:8124/mcp/launch
```

## License

Apache-2.0. This is a personal tool built largely by/for coding agents; fork it
rather than sending patches.

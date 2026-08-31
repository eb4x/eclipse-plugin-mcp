#!/usr/bin/env bash
#
# Probe the MCP server running inside Eclipse.
#
# Answers the two questions an offline build cannot: is the Eclipse on 8124 running
# the build that is on disk here (stale-restart detection), and does each endpoint
# actually serve its tool set over a real streamable-HTTP handshake?
#
# Run after `./gradlew install` AND an Eclipse restart:
#     ./gradlew probe        (or: tools/probe.sh)

set -uo pipefail

BASE_URL="${MCP_BASE_URL:-http://127.0.0.1:8124}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

failures=0

fail() {
	printf 'FAIL  %s\n' "$*" >&2
	failures=$((failures + 1))
}

pass() {
	printf 'ok    %s\n' "$*"
}

die() {
	printf 'FAIL  %s\n' "$*" >&2
	exit 1
}

for cmd in curl jq; do
	command -v "${cmd}" >/dev/null 2>&1 || die "${cmd} is required but not on PATH"
done

# --- local build stamp ------------------------------------------------------

# The stamp to compare against is the one INSIDE the newest built jar — not the
# loose build/generated file, which every Gradle run restamps (comparing against
# that reports "stale" the moment any other task has run since the install).
newest_jar="$(ls -t "${REPO_DIR}"/build/libs/ebbex.eclipsemcpserver_*.jar \
	"${REPO_DIR}"/dist/ebbex.eclipsemcpserver_*.jar 2>/dev/null | head -n1)"
[ -n "${newest_jar}" ] || die "no built jar under build/libs or dist — run ./gradlew jar"

BUILD_INFO="${WORK_DIR}/eclipsemcp-build.properties"
unzip -p "${newest_jar}" eclipsemcp-build.properties > "${BUILD_INFO}" 2>/dev/null ||
	die "no eclipsemcp-build.properties inside ${newest_jar}"

prop() {
	# Trailing \r tolerated; values never contain '=' beyond the first.
	sed -n "s/^$1=//p" "${BUILD_INFO}" | tr -d '\r' | head -n1
}

local_version="$(prop version)"
local_git="$(prop git)"
local_built="$(prop built)"

printf 'local jar:   %s\n' "$(basename "${newest_jar}")"
printf 'local build: %s (git %s, built %s)\n' "${local_version}" "${local_git}" "${local_built}"

# --- GET /version -----------------------------------------------------------

version_body="${WORK_DIR}/version.txt"
if ! curl -sf --max-time 3 "${BASE_URL}/version" -o "${version_body}"; then
	printf 'FAIL  no MCP server on 8124 — is Eclipse running, and did ./gradlew install run before the restart? (./gradlew installStatus)\n' >&2
	exit 1
fi

# Line 1: "EclipseMCPServer <version> (git <hash>, built <time>)"
# Line 2: "bundle: <bundleVersion>"   Line 3: "eclipse: <buildId>"   Line 4: "workspace: <path>"
server_line="$(sed -n '1p' "${version_body}")"
server_bundle="$(sed -n '2p' "${version_body}" | sed 's/^bundle:[[:space:]]*//')"
server_eclipse="$(sed -n '3p' "${version_body}" | sed 's/^eclipse:[[:space:]]*//')"
server_workspace="$(sed -n '4p' "${version_body}" | sed 's/^workspace:[[:space:]]*//')"

server_git="$(printf '%s' "${server_line}" | sed -n 's/.*(git \([^,]*\), built \(.*\))$/\1/p')"
server_built="$(printf '%s' "${server_line}" | sed -n 's/.*(git \([^,]*\), built \(.*\))$/\2/p')"

printf 'server:      %s\n' "${server_line}"
printf 'bundle:      %s\n' "${server_bundle}"
printf 'eclipse:     %s\n' "${server_eclipse}"
printf 'workspace:   %s\n' "${server_workspace}"
printf '\n'

if [ -z "${server_git}" ] || [ -z "${server_built}" ]; then
	die "could not parse git/built out of the /version line: ${server_line}"
fi

# The whole point of the build stamp: a restart that did not pick up the new jar
# looks exactly like a successful one until this comparison.
if [ "${server_git}" != "${local_git}" ] || [ "${server_built}" != "${local_built}" ]; then
	printf 'FAIL  Eclipse is serving build %s (built %s), you built %s — restart Eclipse to load it.\n' \
		"${server_git}" "${server_built}" "${local_git}" >&2
	exit 1
fi
pass "build stamp matches (git ${local_git}, built ${local_built})"

# --- streamable-HTTP handshake per endpoint ---------------------------------

# The transport may answer either a bare JSON body or an SSE frame; strip the SSE
# framing so jq sees a JSON document either way.
unframe() {
	if grep -q '^data: ' "$1"; then
		sed -n 's/^data: //p' "$1"
	else
		cat "$1"
	fi
}

# mcp_post <url> <session-id-or-empty> <json-body> <header-out> <body-out>
mcp_post() {
	local url="$1" session="$2" body="$3" hdr_out="$4" body_out="$5"
	local -a args=(
		-s --max-time 15 -X POST "${url}"
		-D "${hdr_out}" -o "${body_out}"
		-H 'Content-Type: application/json'
		-H 'Accept: application/json, text/event-stream'
		--data-binary "${body}"
	)
	if [ -n "${session}" ]; then
		args+=(-H "Mcp-Session-Id: ${session}")
	fi
	curl "${args[@]}"
}

check_endpoint() {
	local path="$1"
	shift
	local expected_tools="$1"
	shift
	local url="${BASE_URL}${path}"
	local hdr="${WORK_DIR}/hdr" resp="${WORK_DIR}/resp"

	local init_body='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
	if ! mcp_post "${url}" '' "${init_body}" "${hdr}" "${resp}"; then
		fail "${path}: initialize request failed"
		return
	fi
	if ! unframe "${resp}" | jq -e '.result.serverInfo' >/dev/null 2>&1; then
		fail "${path}: initialize returned no result.serverInfo: $(unframe "${resp}" | head -c 300)"
		return
	fi
	local server_name
	server_name="$(unframe "${resp}" | jq -r '.result.serverInfo.name')"

	local session
	session="$(tr -d '\r' < "${hdr}" | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d:[[:space:]]*//p' | head -n1)"
	if [ -z "${session}" ]; then
		fail "${path}: no Mcp-Session-Id response header on initialize"
		return
	fi
	pass "${path}: initialized (serverInfo ${server_name}, session ${session})"

	mcp_post "${url}" "${session}" \
		'{"jsonrpc":"2.0","method":"notifications/initialized"}' "${hdr}" "${resp}" >/dev/null

	if ! mcp_post "${url}" "${session}" \
		'{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' "${hdr}" "${resp}"; then
		fail "${path}: tools/list request failed"
		return
	fi
	local actual_tools
	actual_tools="$(unframe "${resp}" | jq -r '[.result.tools[].name] | sort | join(",")' 2>/dev/null)"
	if [ -z "${actual_tools}" ] || [ "${actual_tools}" = "null" ]; then
		fail "${path}: tools/list returned no tools: $(unframe "${resp}" | head -c 300)"
		return
	fi
	local want
	want="$(printf '%s' "${expected_tools}" | tr ',' '\n' | sort | paste -sd, -)"
	if [ "${actual_tools}" != "${want}" ]; then
		fail "${path}: tool set mismatch
        expected: ${want}
        actual:   ${actual_tools}"
	else
		pass "${path}: tools/list = ${actual_tools}"
	fi
}

# tools/list only proves the registry; actually invoking a workspace-backed tool
# proves the readiness gate opened and the workspace is reachable from an HTTP thread.
call_get_workspace_info() {
	local url="${BASE_URL}/mcp/workspace"
	local hdr="${WORK_DIR}/hdr2" resp="${WORK_DIR}/resp2"

	local init_body='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
	mcp_post "${url}" '' "${init_body}" "${hdr}" "${resp}" || { fail 'get_workspace_info: initialize failed'; return; }
	local session
	session="$(tr -d '\r' < "${hdr}" | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d:[[:space:]]*//p' | head -n1)"
	if [ -z "${session}" ]; then
		fail 'get_workspace_info: no Mcp-Session-Id'
		return
	fi
	mcp_post "${url}" "${session}" \
		'{"jsonrpc":"2.0","method":"notifications/initialized"}' "${hdr}" "${resp}" >/dev/null

	if ! mcp_post "${url}" "${session}" \
		'{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_workspace_info","arguments":{}}}' \
		"${hdr}" "${resp}"; then
		fail 'get_workspace_info: request failed'
		return
	fi
	local out
	out="$(unframe "${resp}")"
	if printf '%s' "${out}" | jq -e '.error' >/dev/null 2>&1; then
		fail "get_workspace_info: JSON-RPC error: $(printf '%s' "${out}" | jq -c '.error')"
		return
	fi
	if [ "$(printf '%s' "${out}" | jq -r '.result.isError // false')" = "true" ]; then
		fail "get_workspace_info: isError: $(printf '%s' "${out}" | jq -r '.result.content[0].text // ""' | head -c 300)"
		return
	fi
	pass "get_workspace_info: $(printf '%s' "${out}" | jq -r '.result.content[0].text // ""' | head -n1)"
}

check_endpoint /mcp/workspace 'get_workspace_info,list_projects,manage_projects,get_problems,read_log'
check_endpoint /mcp/launch 'list_launches,manage_launch,read_console'
call_get_workspace_info

printf '\n'
if [ "${failures}" -eq 0 ]; then
	printf 'PASS  eclipse-plugin-mcp %s (git %s) — bundle %s\n' "${local_version}" "${local_git}" "${server_bundle}"
	exit 0
fi
printf 'FAIL  %d check(s) failed\n' "${failures}" >&2
exit 1

package ebbex.eclipsemcpserver.tools.launch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IWorkspace;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.Args;
import ebbex.eclipsemcpserver.util.ConsoleCapture;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Schemas;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Read the buffered console output of a launch — the Console view, for a client with no window.
 *
 * <p>Output arrives in the tens of thousands of lines (a Ghidra auto-analysis), so the default
 * is a tail and the arguments are built for grepping rather than dumping: {@code filter} is
 * applied to the <em>whole</em> buffer first, and {@code head}/{@code tail} then slice the
 * matches. Asking for {@code head=200} is how you see a startup failure that scrolled away
 * hours ago — {@link ConsoleCapture} pins the first lines of every launch precisely so that
 * question stays answerable.
 *
 * <p>Needs no workspace: the buffers live in this bundle.
 */
public class ReadConsoleTool implements Tool {

	private static final List<String> STREAMS = List.of("both", "out", "err");

	private static final int DEFAULT_TAIL = 200;

	/** Hard cap on lines returned in one call, whatever head+tail ask for. */
	private static final int MAX_OUTPUT_LINES = 2_000;

	private final ConsoleCapture consoleCapture;

	public ReadConsoleTool(ConsoleCapture consoleCapture) {
		this.consoleCapture = consoleCapture;
	}

	@Override
	public String name() {
		return "read_console";
	}

	@Override
	public String description() {
		return "Read a launch's console output from this server's buffer. 'launch' is a buffer " +
			"id (\"#3\" or \"3\", from list_launches kind=active) or a launch configuration " +
			"name (newest launch of it); it defaults to the most recent launch. Output can be " +
			"huge, so this returns the last 'tail' lines (default " + DEFAULT_TAIL + "); pass " +
			"head=N to also see the FIRST N lines — startup and plugin-load failures print " +
			"early and are kept even after the middle of the buffer is elided. 'filter' is a " +
			"case-insensitive substring applied to the whole buffer BEFORE head/tail, so it " +
			"greps rather than tails. stream=err shows only stderr; in stream=both, stderr " +
			"lines are prefixed '[err] '. Only launches started while this server was running " +
			"are buffered.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("launch", Schemas.stringProp("Buffer id (\"#3\") or launch configuration " +
			"name (e.g. \"Ghidra\"); defaults to the most recent launch"));
		properties.put("stream", Schemas.enumProp("Which stream to show: both (default), out, " +
			"err", STREAMS));
		properties.put("head", Schemas.intProp("Show the first N matching lines as well " +
			"(default 0)"));
		properties.put("tail", Schemas.intProp("Show the last N matching lines (default " +
			DEFAULT_TAIL + "; 0 for none)"));
		properties.put("filter", Schemas.stringProp("Case-insensitive substring; applied to the " +
			"whole buffer before head/tail"));

		return Map.of(
			"type", "object",
			"properties", properties);
	}

	@Override
	public boolean isReadOnly() {
		return true;
	}

	@Override
	public boolean requiresWorkspace() {
		// The buffers are ours; a workspace that has not finished initializing is irrelevant.
		return false;
	}

	@Override
	public McpSchema.CallToolResult execute(Map<String, Object> args, IWorkspace workspace)
			throws Exception {
		String stream = Args.stringArg(args, "stream", "both");
		if (!STREAMS.contains(stream)) {
			throw new IllegalArgumentException(
				"Unknown stream '" + stream + "'; expected one of " + STREAMS);
		}
		int head = Math.max(0, Args.intArg(args, "head", 0));
		int tail = Math.max(0, Args.intArg(args, "tail", DEFAULT_TAIL));
		String filter = Args.stringArg(args, "filter", null);

		List<ConsoleCapture.Capture> captures = consoleCapture.captures();
		if (captures.isEmpty()) {
			return Results.error("No launches have been captured since the MCP server started. " +
				"Start one with manage_launch op=launch (only launches begun while this server " +
				"is running are buffered).");
		}
		ConsoleCapture.Capture capture = resolve(Args.stringArg(args, "launch", null), captures);

		List<ConsoleCapture.Line> all = capture.snapshot();
		List<ConsoleCapture.Line> matching = new ArrayList<>();
		for (ConsoleCapture.Line line : all) {
			if (!wantedStream(line, stream)) {
				continue;
			}
			if (!matches(line.text(), filter)) {
				continue;
			}
			matching.add(line);
		}

		StringBuilder sb = new StringBuilder();
		sb.append('#').append(capture.id()).append(' ').append(capture.configName())
				.append(" [").append(capture.mode()).append("] ").append(capture.state())
				.append(' ').append(capture.bufferedLines()).append(" lines buffered");
		if (capture.elidedLines() > 0) {
			sb.append(" (").append(capture.elidedLines()).append(" elided)");
		}
		if ((filter != null && !filter.isEmpty()) || !"both".equals(stream)) {
			sb.append(", ").append(matching.size()).append(" matching");
		}
		sb.append('\n');

		if (matching.isEmpty()) {
			sb.append(all.isEmpty() ? "(no output buffered yet)" : "(no lines match)");
			return Results.ok(sb.toString());
		}

		int size = matching.size();
		int headCount = Math.min(head, size);
		int tailCount = Math.min(tail, size);
		boolean capped = false;
		if (headCount + tailCount > MAX_OUTPUT_LINES) {
			headCount = Math.min(headCount, MAX_OUTPUT_LINES);
			tailCount = Math.max(0, MAX_OUTPUT_LINES - headCount);
			capped = true;
		}
		if (headCount == 0 && tailCount == 0) {
			sb.append("(head=0 and tail=0 — nothing to show)");
			return Results.ok(sb.toString());
		}

		if (headCount + tailCount >= size) {
			// The two windows cover the whole selection; show it once, with no false elision.
			append(sb, matching, 0, size, stream);
		}
		else {
			append(sb, matching, 0, headCount, stream);
			int omitted = size - headCount - tailCount;
			if (omitted > 0) {
				sb.append("... ").append(omitted).append(" lines omitted ...\n");
			}
			append(sb, matching, size - tailCount, size, stream);
		}

		if (capped) {
			sb.append("(capped at ").append(MAX_OUTPUT_LINES)
					.append(" lines; narrow with filter, or ask for a smaller head/tail)\n");
		}
		return Results.ok(sb.toString());
	}

	private static void append(StringBuilder sb, List<ConsoleCapture.Line> lines, int from, int to,
			String stream) {
		for (int i = from; i < to; i++) {
			ConsoleCapture.Line line = lines.get(i);
			// Tag only when the two streams are interleaved — with stream=err every line is
			// stderr and the prefix would be noise.
			if ("both".equals(stream) && line.isError()) {
				sb.append("[err] ");
			}
			sb.append(line.text()).append('\n');
		}
	}

	private static boolean wantedStream(ConsoleCapture.Line line, String stream) {
		return switch (stream) {
			case "out" -> !line.isError();
			case "err" -> line.isError();
			default -> true;
		};
	}

	private static boolean matches(String text, String filter) {
		return filter == null || filter.isEmpty() || (text != null &&
			text.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)));
	}

	/** Resolve by "#id"/"id", else by exact configuration name, else the newest capture. */
	private ConsoleCapture.Capture resolve(String target, List<ConsoleCapture.Capture> newestFirst) {
		if (target == null || target.isBlank()) {
			return newestFirst.get(0);
		}
		String trimmed = target.trim();
		String digits = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
		if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
			ConsoleCapture.Capture capture = consoleCapture.byId(Integer.parseInt(digits));
			if (capture == null) {
				throw new IllegalArgumentException("No buffered launch #" + digits + " (" +
					available(newestFirst) + ")");
			}
			return capture;
		}
		ConsoleCapture.Capture capture = consoleCapture.newestFor(trimmed);
		if (capture != null) {
			return capture;
		}
		for (ConsoleCapture.Capture candidate : newestFirst) {
			if (candidate.configName().toLowerCase(Locale.ROOT)
					.contains(trimmed.toLowerCase(Locale.ROOT))) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("No buffered launch of '" + trimmed + "' (" +
			available(newestFirst) + ")");
	}

	private static String available(List<ConsoleCapture.Capture> captures) {
		List<String> names = new ArrayList<>();
		for (ConsoleCapture.Capture capture : captures) {
			names.add("#" + capture.id() + " " + capture.configName());
		}
		return "buffered: " + String.join(", ", names);
	}
}

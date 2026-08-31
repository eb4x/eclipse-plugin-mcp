package ebbex.eclipsemcpserver.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;

import ebbex.eclipsemcpserver.Tool;
import ebbex.eclipsemcpserver.util.Args;
import ebbex.eclipsemcpserver.util.Results;
import ebbex.eclipsemcpserver.util.Schemas;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Read this running IDE's platform log ({@code .metadata/.log}). Because the server lives
 * inside the Eclipse process, it resolves the path the instance is actually writing to via
 * {@link Platform#getLogFileLocation()} &mdash; there is no ambiguity about which install's
 * log you are reading.
 *
 * <p>Entries are grouped whole: an Eclipse entry runs from an {@code !ENTRY} (or
 * {@code !SESSION}) line up to the next one, carrying its {@code !MESSAGE},
 * {@code !SUBENTRY}, {@code !STACK} and raw stack-trace lines with it, so a filter that hits
 * a stack frame returns the whole exception rather than one orphan line.
 *
 * <p>This tool deliberately does not require a workspace: it is most valuable exactly when
 * startup itself broke and nothing else answers.
 */
public class ReadLogTool implements Tool {

	private static final int DEFAULT_TAIL = 200;

	/** Hard cap on emitted lines — a single stack-heavy session would otherwise flood the context. */
	private static final int MAX_LINES = 2000;

	/** A line that begins a new entry. {@code !SESSION} starts a session header block. */
	private static final Pattern ENTRY_START = Pattern.compile("^!(ENTRY|SESSION)\\b");

	/** The date Eclipse puts at the end of an {@code !ENTRY} line / after {@code !SESSION}. */
	private static final Pattern TIMESTAMP =
		Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");

	@Override
	public String name() {
		return "read_log";
	}

	@Override
	public String description() {
		return "Read this running Eclipse instance's platform log (.metadata/.log), resolved " +
			"in-process so it is unambiguously this IDE's log. Returns the last matching entries, " +
			"newest last, after the resolved 'Log:' path. Whole entries are kept together " +
			"(!ENTRY with its !MESSAGE/!SUBENTRY/!STACK and stack trace), so filtering on an " +
			"exception name returns the full trace. Works even when the workspace is not " +
			"initialized — this is the tool to call when startup broke or another tool reports " +
			"an internal failure.";
	}

	@Override
	public Map<String, Object> inputSchema() {
		return Map.of(
			"type", "object",
			"properties", Map.of(
				"tail", Schemas.intProp("Return at most the last N matching entries (default " +
					DEFAULT_TAIL + "); output is additionally capped at about " + MAX_LINES +
					" lines"),
				"filter", Schemas.stringProp(
					"Keep only entries containing this case-insensitive substring"),
				"regex", Schemas.stringProp("Keep only entries matching this Java regular " +
					"expression anywhere in the entry (case-insensitive); combined with " +
					"'filter' when both are given"),
				"since", Schemas.stringProp("Keep only entries at/after this timestamp, compared " +
					"against the entry's 'yyyy-MM-dd HH:mm:ss' (e.g. '2026-08-31' or " +
					"'2026-08-31 17:30'); entries without a parseable date are kept")));
	}

	@Override
	public boolean isReadOnly() {
		return true;
	}

	@Override
	public boolean requiresWorkspace() {
		// The log is most valuable when the workspace never came up, so this tool must not be
		// gated on it. The workspace argument is null in that case and unused here.
		return false;
	}

	@Override
	public McpSchema.CallToolResult execute(Map<String, Object> args, IWorkspace workspace) {
		File logFile = logFile();
		if (logFile == null) {
			return Results.error("Could not resolve the Eclipse log file. The instance area is " +
				"probably not bound yet (the IDE is still starting, or was launched without a " +
				"workspace) — retry shortly.");
		}
		if (!logFile.isFile()) {
			return Results.ok("Log: " + logFile.getAbsolutePath() +
				"\n(the log file does not exist yet — nothing has been logged this session)");
		}

		int tail = Math.max(1, Args.intArg(args, "tail", DEFAULT_TAIL));
		String filter = Args.stringArg(args, "filter", "");
		String regex = Args.stringArg(args, "regex", "");
		String since = Args.stringArg(args, "since", "");

		Pattern pattern = null;
		if (!regex.isEmpty()) {
			try {
				pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
			}
			catch (PatternSyntaxException e) {
				return Results.error("Invalid 'regex': " + e.getMessage());
			}
		}
		String needle = filter.toLowerCase();

		Deque<String> kept = new ArrayDeque<>();
		long[] stats = new long[2]; // {linesScanned, matchingEntries}
		try {
			tailEntries(logFile, tail, since, needle, pattern, kept, stats);
		}
		catch (IOException e) {
			return Results.error("Failed to read " + logFile.getAbsolutePath() + ": " +
				e.getMessage());
		}

		StringBuilder sb = new StringBuilder("Log: ").append(logFile.getAbsolutePath()).append('\n');
		if (kept.isEmpty()) {
			return Results.ok(sb.append("(no matching entries in ").append(stats[0])
					.append(" lines scanned)").toString());
		}
		int matched = kept.size();
		int elided = capLines(kept);
		for (String entry : kept) {
			sb.append(entry).append("\n\n");
		}
		sb.append(footer(kept.size(), stats[1], stats[0]));
		if (elided > 0) {
			sb.append("\n(").append(elided).append(" older matching ")
					.append(elided == 1 ? "entry" : "entries").append(" of the ").append(matched)
					.append(" requested omitted to keep output under ~").append(MAX_LINES)
					.append(" lines; narrow with filter/regex/since or lower tail)");
		}
		return Results.ok(sb.toString());
	}

	/**
	 * Stream the log, grouping each {@code !ENTRY}/{@code !SESSION} line with its continuation
	 * lines into one entry, and retain only the last {@code tail} entries that pass
	 * {@code since} and the filters. Memory stays O(tail): {@code kept} is capped and each
	 * entry is built one at a time.
	 */
	private static void tailEntries(File logFile, int tail, String since, String needle,
			Pattern pattern, Deque<String> kept, long[] stats) throws IOException {
		try (BufferedReader reader =
			Files.newBufferedReader(logFile.toPath(), StandardCharsets.UTF_8)) {
			StringBuilder entry = null;
			String line;
			while ((line = reader.readLine()) != null) {
				stats[0]++;
				if (ENTRY_START.matcher(line).find() || entry == null) {
					// A new entry starts here (or these are stray lines before the first one):
					// flush what we had.
					accept(entry, since, needle, pattern, kept, tail, stats);
					entry = new StringBuilder(line);
				}
				else {
					// !MESSAGE / !SUBENTRY / !STACK / raw stack frames belong to the current entry.
					entry.append('\n').append(line);
				}
			}
			accept(entry, since, needle, pattern, kept, tail, stats);
		}
	}

	/** Apply since + filters to a completed entry; if it passes, append it to the capped deque. */
	private static void accept(StringBuilder entry, String since, String needle, Pattern pattern,
			Deque<String> kept, int tail, long[] stats) {
		if (entry == null) {
			return;
		}
		String text = trimTrailingBlanks(entry);
		if (text.isEmpty()) {
			return;
		}
		if (!since.isEmpty() && beforeSince(text, since)) {
			return;
		}
		if (pattern != null && !pattern.matcher(text).find()) {
			return;
		}
		if (!needle.isEmpty() && !text.toLowerCase().contains(needle)) {
			return;
		}
		stats[1]++;
		kept.addLast(text);
		while (kept.size() > tail) {
			kept.removeFirst();
		}
	}

	/**
	 * Drop entries from the front until the retained text is within {@link #MAX_LINES}, and
	 * return how many were dropped. The newest entry is always kept, however long it is — a
	 * 3000-frame stack trace is still the answer to "why did it fail".
	 */
	private static int capLines(Deque<String> kept) {
		int lines = 0;
		for (String entry : kept) {
			lines += countLines(entry);
		}
		int elided = 0;
		while (lines > MAX_LINES && kept.size() > 1) {
			lines -= countLines(kept.removeFirst());
			elided++;
		}
		return elided;
	}

	private static int countLines(String text) {
		int lines = 1;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				lines++;
			}
		}
		return lines;
	}

	private static String trimTrailingBlanks(StringBuilder entry) {
		int end = entry.length();
		while (end > 0 && Character.isWhitespace(entry.charAt(end - 1))) {
			end--;
		}
		return entry.substring(0, end);
	}

	/**
	 * True if the entry's timestamp sorts before {@code since}. Eclipse writes a zero-padded
	 * {@code yyyy-MM-dd HH:mm:ss.SSS} on {@code !ENTRY}/{@code !SESSION} lines, so a
	 * lexicographic compare over the common prefix is correct and needs no parsing (a caller
	 * may pass just a date, or a date and time). Lenient by design: an entry whose first line
	 * carries no parseable date is never dropped by {@code since}.
	 */
	private static boolean beforeSince(String entry, String since) {
		int firstLineEnd = entry.indexOf('\n');
		String header = firstLineEnd < 0 ? entry : entry.substring(0, firstLineEnd);
		Matcher matcher = TIMESTAMP.matcher(header);
		if (!matcher.find()) {
			return false;
		}
		String stamp = matcher.group();
		int len = Math.min(since.length(), stamp.length());
		return stamp.substring(0, len).compareTo(since.substring(0, len)) < 0;
	}

	private static String footer(int shown, long matching, long scanned) {
		return "(showing last " + shown + " of " + matching + " matching " +
			(matching == 1 ? "entry" : "entries") + "; " + scanned + " lines scanned)";
	}

	/**
	 * The log file this instance writes, or null if the platform cannot tell us yet (the
	 * instance area is bound late, and {@code Platform} can fail outright before that).
	 */
	public static File logFile() {
		try {
			IPath path = Platform.getLogFileLocation();
			return path != null ? path.toFile() : null;
		}
		catch (Throwable t) {
			// IllegalStateException / NoClassDefFoundError before the instance area is bound.
			return null;
		}
	}
}

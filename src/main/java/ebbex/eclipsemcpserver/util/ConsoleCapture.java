package ebbex.eclipsemcpserver.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

/**
 * Captures the console output of every launch started while this bundle is running, so
 * {@code read_console} can serve it to an MCP client that has no window to look at.
 *
 * <p><b>Why we buffer at all.</b> Each {@link IProcess} already exposes an
 * {@link IStreamMonitor} with a {@code getContents()} accumulation, but that accumulation is
 * not ours: as soon as the {@code org.eclipse.debug.ui} Console view attaches to a process it
 * switches the monitors to unbuffered ({@code IFlushableStreamMonitor.setBuffered(false)}) and
 * flushes them, after which {@code getContents()} returns only whatever arrived since the last
 * flush. Reading the Console view's own document instead would confine us to the UI thread and
 * to {@code org.eclipse.debug.ui} (which the MVP deliberately does not require). So we keep our
 * own line buffer, fed by an {@link IStreamListener} per stream.
 *
 * <p><b>Retention.</b> Sized for a full Ghidra auto-analysis: up to {@value #MAX_LINES} lines and
 * {@value #MAX_CHARS} characters per launch, with <em>head+tail</em> retention — the first
 * {@value #HEAD_LINES} lines are pinned forever (startup and extension-load failures print early
 * and are exactly what callers come looking for) and the remainder is a rolling tail. Lines
 * dropped between head and tail are counted and reported, never silently lost. The last
 * {@value #MAX_LAUNCHES} launches are retained.
 *
 * <p><b>Threading.</b> Launch and stream callbacks arrive on arbitrary threads; reads arrive on
 * Jetty threads. Every mutation and every read of a {@link Capture} is synchronized on that
 * capture; the capture list itself is guarded by {@link #lock}. Nothing here touches SWT.
 */
public final class ConsoleCapture {

	/** Hard per-launch line cap (head + tail). */
	public static final int MAX_LINES = 50_000;

	/** Lines pinned at the head of the buffer, never evicted. */
	public static final int HEAD_LINES = 5_000;

	/** Hard per-launch character cap (head + tail). */
	public static final long MAX_CHARS = 16L * 1024 * 1024;

	/** Share of {@link #MAX_CHARS} the pinned head may occupy, so one huge early line cannot fill it. */
	private static final long HEAD_CHARS = MAX_CHARS / 4;

	/** Launches retained; the eldest is detached and dropped beyond this. */
	public static final int MAX_LAUNCHES = 10;

	/** A stream chunk with no newline at all is force-flushed past this length. */
	private static final int MAX_PENDING_CHARS = 64 * 1024;

	private static final DateTimeFormatter TIME_OF_DAY =
		DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	/** One captured output line, tagged with the stream it arrived on. */
	public record Line(char stream, String text) {

		public boolean isError() {
			return stream == 'E';
		}
	}

	/** A stream monitor we attached to, remembered so {@link #dispose()} can detach. */
	private record Attachment(IStreamMonitor monitor, IStreamListener listener) {
	}

	/** The buffered output of one launch, plus the metadata tools report alongside it. */
	public static final class Capture {

		private final int id;
		private final String configName;
		private final String mode;
		private final long startedMillis;
		private final ILaunch launch;

		/** Pinned first lines. Guarded by {@code this}. */
		private final List<Line> head = new ArrayList<>();

		/** Rolling tail. Guarded by {@code this}. */
		private final ArrayDeque<Line> tail = new ArrayDeque<>();

		private long headChars;
		private long tailChars;
		private long elided;

		private final StringBuilder pendingOut = new StringBuilder();
		private final StringBuilder pendingErr = new StringBuilder();

		private final List<Attachment> attachments = new ArrayList<>();
		private final Set<IProcess> wiredProcesses =
			Collections.newSetFromMap(new IdentityHashMap<>());

		private Capture(int id, String configName, String mode, long startedMillis, ILaunch launch) {
			this.id = id;
			this.configName = configName;
			this.mode = mode;
			this.startedMillis = startedMillis;
			this.launch = launch;
		}

		public int id() {
			return id;
		}

		public String configName() {
			return configName;
		}

		public String mode() {
			return mode;
		}

		public long startedMillis() {
			return startedMillis;
		}

		/** The captured launch — still useful after removal from the launch manager. */
		public ILaunch launch() {
			return launch;
		}

		/** Lines currently held (head + tail), excluding those elided. */
		public synchronized int bufferedLines() {
			return head.size() + tail.size();
		}

		/** Lines dropped between head and tail on overflow. */
		public synchronized long elidedLines() {
			return elided;
		}

		/** An immutable snapshot in arrival order, stdout and stderr interleaved. */
		public synchronized List<Line> snapshot() {
			List<Line> all = new ArrayList<>(head.size() + tail.size());
			all.addAll(head);
			all.addAll(tail);
			return all;
		}

		/** "running", "terminated(exit=0)", … — see {@link ConsoleCapture#describeState(ILaunch)}. */
		public String state() {
			return describeState(launch);
		}

		/** Feed a raw chunk; splits into lines and keeps any unterminated remainder pending. */
		private synchronized void appendChunk(char stream, String chunk) {
			if (chunk == null || chunk.isEmpty()) {
				return;
			}
			StringBuilder pending = stream == 'E' ? pendingErr : pendingOut;
			pending.append(chunk);
			int start = 0;
			int nl;
			while ((nl = pending.indexOf("\n", start)) >= 0) {
				addLine(stream, stripCr(pending.substring(start, nl)));
				start = nl + 1;
			}
			pending.delete(0, start);
			if (pending.length() > MAX_PENDING_CHARS) {
				// A process that never emits a newline (a progress bar, a binary dump) must not
				// grow this builder without bound.
				addLine(stream, pending.toString());
				pending.setLength(0);
			}
		}

		/** Emit whatever sits in the per-stream remainders as final lines (process died mid-line). */
		private synchronized void flushPending() {
			if (pendingOut.length() > 0) {
				addLine('O', stripCr(pendingOut.toString()));
				pendingOut.setLength(0);
			}
			if (pendingErr.length() > 0) {
				addLine('E', stripCr(pendingErr.toString()));
				pendingErr.setLength(0);
			}
		}

		private synchronized void addLine(char stream, String text) {
			Line line = new Line(stream, text);
			long cost = text.length() + 1L;
			if (head.size() < HEAD_LINES && headChars + cost <= HEAD_CHARS) {
				head.add(line);
				headChars += cost;
				return;
			}
			tail.addLast(line);
			tailChars += cost;
			while (!tail.isEmpty() &&
				(head.size() + tail.size() > MAX_LINES || headChars + tailChars > MAX_CHARS)) {
				Line dropped = tail.removeFirst();
				tailChars -= dropped.text().length() + 1L;
				elided++;
			}
		}

		private static String stripCr(String s) {
			return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
		}
	}

	private final Object lock = new Object();

	/** Retained captures, oldest first. Guarded by {@link #lock}. */
	private final List<Capture> captures = new ArrayList<>();

	private int nextId = 1;

	private ILaunchesListener2 listener;

	private boolean disposed;

	/**
	 * Register on the launch manager and adopt any launch already running. Called from the
	 * startup job before the server begins serving, so anything the user starts afterwards is
	 * captured in full; launches already under way are seeded best-effort from
	 * {@code getContents()} and may already have lost output to the Console view.
	 */
	public void install() {
		ILaunchManager manager = launchManager();
		if (manager == null) {
			Logs.warn("MCP server: org.eclipse.debug.core is unavailable; " +
				"read_console will have nothing to report");
			return;
		}
		ILaunchesListener2 l = new ILaunchesListener2() {

			@Override
			public void launchesAdded(ILaunch[] launches) {
				wireAll(launches);
			}

			@Override
			public void launchesChanged(ILaunch[] launches) {
				// Processes are attached to a launch after it is added, so this — not
				// launchesAdded — is usually where a launch first has streams to listen to.
				wireAll(launches);
			}

			@Override
			public void launchesTerminated(ILaunch[] launches) {
				for (ILaunch launch : launches) {
					Capture capture = findCapture(launch);
					if (capture != null) {
						capture.flushPending();
					}
				}
			}

			@Override
			public void launchesRemoved(ILaunch[] launches) {
				for (ILaunch launch : launches) {
					forget(launch);
				}
			}
		};
		manager.addLaunchListener(l);
		synchronized (lock) {
			listener = l;
		}
		wireAll(manager.getLaunches());
	}

	/** Detach every listener and drop every buffer. Safe to call twice. */
	public void dispose() {
		ILaunchesListener2 l;
		List<Capture> doomed;
		synchronized (lock) {
			disposed = true;
			l = listener;
			listener = null;
			doomed = new ArrayList<>(captures);
			captures.clear();
		}
		if (l != null) {
			ILaunchManager manager = launchManager();
			if (manager != null) {
				manager.removeLaunchListener(l);
			}
		}
		for (Capture capture : doomed) {
			detach(capture);
		}
	}

	/** Retained captures, newest first. */
	public List<Capture> captures() {
		synchronized (lock) {
			List<Capture> copy = new ArrayList<>(captures);
			Collections.reverse(copy);
			return copy;
		}
	}

	/** The most recently started capture, or null if nothing has been captured. */
	public Capture newest() {
		synchronized (lock) {
			return captures.isEmpty() ? null : captures.get(captures.size() - 1);
		}
	}

	public Capture byId(int id) {
		synchronized (lock) {
			for (Capture capture : captures) {
				if (capture.id() == id) {
					return capture;
				}
			}
			return null;
		}
	}

	/** The newest capture whose launch configuration has this exact name, or null. */
	public Capture newestFor(String configName) {
		synchronized (lock) {
			for (int i = captures.size() - 1; i >= 0; i--) {
				if (captures.get(i).configName().equals(configName)) {
					return captures.get(i);
				}
			}
			return null;
		}
	}

	/** The capture for this exact launch object, or null if it was never captured. */
	public Capture forLaunch(ILaunch launch) {
		return findCapture(launch);
	}

	// ---------------------------------------------------------------- wiring

	private void wireAll(ILaunch[] launches) {
		if (launches == null) {
			return;
		}
		for (ILaunch launch : launches) {
			try {
				wire(launch);
			}
			catch (Throwable t) {
				// A misbehaving launch must never break the listener for the others.
				Logs.error("MCP server: console capture failed for a launch", t);
			}
		}
	}

	private void wire(ILaunch launch) {
		if (launch == null) {
			return;
		}
		Capture capture = findOrCreate(launch);
		if (capture == null) {
			return;
		}
		for (IProcess process : launch.getProcesses()) {
			if (process == null) {
				continue;
			}
			synchronized (capture) {
				if (!capture.wiredProcesses.add(process)) {
					continue;
				}
			}
			IStreamsProxy proxy = process.getStreamsProxy();
			if (proxy == null) {
				// Debug targets and some process types expose no streams at all.
				continue;
			}
			attach(capture, 'O', proxy.getOutputStreamMonitor());
			attach(capture, 'E', proxy.getErrorStreamMonitor());
		}
	}

	private void attach(Capture capture, char stream, IStreamMonitor monitor) {
		if (monitor == null) {
			return;
		}
		// Seed first, then listen: whatever the monitor accumulated before we got here would
		// otherwise be lost. The reverse order would drop it. This does mean a chunk arriving
		// between the two calls can be recorded twice; that race is accepted (rare, and a
		// duplicated line is far cheaper than a missing one).
		String existing;
		try {
			existing = monitor.getContents();
		}
		catch (Throwable t) {
			existing = null;
		}
		if (existing != null && !existing.isEmpty()) {
			capture.appendChunk(stream, existing);
		}
		IStreamListener listener = (text, source) -> capture.appendChunk(stream, text);
		monitor.addListener(listener);
		synchronized (capture) {
			capture.attachments.add(new Attachment(monitor, listener));
		}
	}

	private Capture findCapture(ILaunch launch) {
		if (launch == null) {
			return null;
		}
		synchronized (lock) {
			for (Capture capture : captures) {
				if (capture.launch == launch) {
					return capture;
				}
			}
			return null;
		}
	}

	private Capture findOrCreate(ILaunch launch) {
		Capture evicted = null;
		Capture capture;
		synchronized (lock) {
			for (Capture existing : captures) {
				if (existing.launch == launch) {
					return existing;
				}
			}
			if (disposed) {
				// A stray callback after stop(): record nothing.
				return null;
			}
			capture = new Capture(nextId++, configNameOf(launch), modeOf(launch),
				startTimeOf(launch), launch);
			captures.add(capture);
			if (captures.size() > MAX_LAUNCHES) {
				evicted = captures.remove(0);
			}
		}
		if (evicted != null) {
			detach(evicted);
		}
		return capture;
	}

	private void forget(ILaunch launch) {
		Capture capture = null;
		synchronized (lock) {
			for (int i = 0; i < captures.size(); i++) {
				if (captures.get(i).launch == launch) {
					capture = captures.remove(i);
					break;
				}
			}
		}
		if (capture != null) {
			detach(capture);
		}
	}

	private void detach(Capture capture) {
		List<Attachment> attachments;
		synchronized (capture) {
			attachments = new ArrayList<>(capture.attachments);
			capture.attachments.clear();
			capture.wiredProcesses.clear();
			capture.head.clear();
			capture.tail.clear();
			capture.headChars = 0;
			capture.tailChars = 0;
			capture.pendingOut.setLength(0);
			capture.pendingErr.setLength(0);
		}
		for (Attachment attachment : attachments) {
			try {
				attachment.monitor().removeListener(attachment.listener());
			}
			catch (Throwable ignored) {
				// The monitor's process is gone; nothing to detach from.
			}
		}
	}

	// ------------------------------------------------------------- utilities

	/** The launch manager, or null when {@code org.eclipse.debug.core} is not available. */
	public static ILaunchManager launchManager() {
		try {
			DebugPlugin plugin = DebugPlugin.getDefault();
			return plugin == null ? null : plugin.getLaunchManager();
		}
		catch (Throwable t) {
			return null;
		}
	}

	/** The launch configuration's name, or a placeholder for a launch that has none. */
	public static String configNameOf(ILaunch launch) {
		ILaunchConfiguration config = launch == null ? null : launch.getLaunchConfiguration();
		return config == null ? "(no configuration)" : config.getName();
	}

	public static String modeOf(ILaunch launch) {
		String mode = launch == null ? null : launch.getLaunchMode();
		return mode == null ? "?" : mode;
	}

	/** The platform's own launch timestamp when it recorded one, else now. */
	public static long startTimeOf(ILaunch launch) {
		try {
			String stamp = launch == null ? null
					: launch.getAttribute(DebugPlugin.ATTR_LAUNCH_TIMESTAMP);
			if (stamp != null) {
				return Long.parseLong(stamp);
			}
		}
		catch (Throwable ignored) {
			// Attribute absent or not a number — fall through.
		}
		return System.currentTimeMillis();
	}

	/** "running", "terminated(exit=N)" or plain "terminated" when no exit code is available. */
	public static String describeState(ILaunch launch) {
		if (launch == null) {
			return "unknown";
		}
		if (!launch.isTerminated()) {
			return "running";
		}
		for (IProcess process : launch.getProcesses()) {
			if (process == null || !process.isTerminated()) {
				continue;
			}
			try {
				// getExitValue() throws while the process still runs, hence the guard above.
				return "terminated(exit=" + process.getExitValue() + ")";
			}
			catch (Exception ignored) {
				// Exit code not retained by this process type.
			}
		}
		return "terminated";
	}

	public static String timeOfDay(long millis) {
		return TIME_OF_DAY.format(Instant.ofEpochMilli(millis));
	}
}

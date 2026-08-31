package ebbex.eclipsemcpserver.util;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * A single background worker for long-running workspace operations (builds), the
 * Ghidra sibling's {@code Analysis} analogue. One daemon thread, FIFO, with dedup:
 * submitting a key already queued or running is a reported no-op, so a second
 * {@code op=build} on the same project never stacks a second build.
 *
 * <p>Callers bound their own wait with {@link #awaitIdle}: incremental JDT builds
 * finish in well under a second and feel synchronous; a cold/full build falls
 * through to "still building — poll get_workspace_info / get_problems".
 */
public final class BuildQueue {

	private record Task(String key, Runnable body) {
	}

	private final ArrayDeque<Task> queue = new ArrayDeque<>();
	private final Set<String> queuedKeys = new HashSet<>();
	private String runningKey;
	private boolean shutdown;
	private final Thread worker;

	public BuildQueue() {
		worker = new Thread(this::run, "mcp-build-queue");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Enqueue {@code body} under {@code key} (e.g. a project name, or {@code *} for the
	 * whole workspace). Returns false — without running anything — when the same key is
	 * already queued or running.
	 */
	public synchronized boolean submit(String key, Runnable body) {
		if (shutdown) {
			throw new IllegalStateException("build queue is shut down");
		}
		if (queuedKeys.contains(key) || key.equals(runningKey)) {
			return false;
		}
		queue.add(new Task(key, body));
		queuedKeys.add(key);
		notifyAll();
		return true;
	}

	/** Number of tasks queued or running. */
	public synchronized int pending() {
		return queue.size() + (runningKey != null ? 1 : 0);
	}

	/** The key currently running, or null. */
	public synchronized String running() {
		return runningKey;
	}

	/**
	 * Wait until the queue is empty and nothing is running, at most {@code timeoutMs}.
	 *
	 * @throws TimeoutException if work is still pending when the bound expires — the
	 *             work is not cancelled and completes in the background
	 */
	public synchronized void awaitIdle(long timeoutMs) throws InterruptedException,
			TimeoutException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (pending() > 0) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				throw new TimeoutException();
			}
			wait(remaining);
		}
	}

	public synchronized void shutdown() {
		shutdown = true;
		queue.clear();
		queuedKeys.clear();
		notifyAll();
		worker.interrupt();
	}

	private void run() {
		while (true) {
			Task task;
			synchronized (this) {
				while (queue.isEmpty() && !shutdown) {
					try {
						wait();
					}
					catch (InterruptedException e) {
						return;
					}
				}
				if (shutdown) {
					return;
				}
				task = queue.poll();
				queuedKeys.remove(task.key());
				runningKey = task.key();
			}
			try {
				task.body().run();
			}
			catch (Throwable t) {
				Logs.error("build queue task '" + task.key() + "' failed", t);
			}
			finally {
				synchronized (this) {
					runningKey = null;
					notifyAll();
				}
			}
		}
	}
}

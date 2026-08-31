package ebbex.eclipsemcpserver.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The server's single policy for reaching the SWT UI thread: post the work, wait with a
 * bound, and report rather than hang if the bound expires. Port of the Ghidra sibling's
 * {@code Edt.runNow}. <b>No MVP tool uses this</b> — the whole MVP tool set runs on the
 * resource/JDT/debug model, which is safe off the UI thread. It exists so future editor
 * or selection tools have a correct door to walk through.
 *
 * <p>Eclipse raises <em>modal</em> dialogs from paths an agent can provoke (save-before-launch,
 * errors-exist-proceed?, out-of-sync prompts), and a modal dialog pumps a nested
 * {@code readAndDispatch} loop, so a {@code Display.syncExec} queued behind it never runs.
 * Every unbounded wait becomes permanent: the MCP call never answers. Hence: never
 * {@code syncExec}, always {@code asyncExec} + bounded {@code CompletableFuture.get}.
 *
 * <p>Two Eclipse-specific rules beyond the Swing original:
 * <ul>
 * <li>Never call {@code Display.getDefault()} from a background thread — it <em>creates</em>
 * a Display if none exists. Resolve via
 * {@code PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null}.</li>
 * <li>{@code org.eclipse.ui}/{@code org.eclipse.swt} are not required by this bundle's MVP
 * manifest; a caller must catch {@link LinkageError} and convert it to a tool error.</li>
 * </ul>
 *
 * <p>A timeout does not cancel the posted task, and cannot: it runs to completion once the
 * UI thread is free again. So a timeout means <em>unknown outcome</em>, and every message
 * built on one has to say so.
 */
public final class UiThread {

	private UiThread() {
	}

	/**
	 * Run {@code body} on the UI thread of {@code display} and return its result, waiting at
	 * most {@code timeoutMs}. Runs inline when the caller is already on that thread.
	 *
	 * <p>{@code display} is typed {@link Object} so this class loads without SWT on the
	 * classpath; pass an {@code org.eclipse.swt.widgets.Display}. Callers obtain it with
	 * {@code PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null}
	 * and must handle null ("the Eclipse UI is not running").
	 *
	 * @throws java.util.concurrent.TimeoutException if the bound expires — the task is still
	 *             queued or running, so the outcome is genuinely unknown
	 * @throws Exception whatever {@code body} threw, unwrapped
	 */
	public static <T> T runNow(Object display, Callable<T> body, long timeoutMs) throws Exception {
		org.eclipse.swt.widgets.Display d = (org.eclipse.swt.widgets.Display) display;
		if (org.eclipse.swt.widgets.Display.getCurrent() == d) {
			return body.call();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		d.asyncExec(() -> {
			try {
				future.complete(body.call());
			}
			catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
		}
	}

	/**
	 * The stock explanation for a timeout: what is blocked, that the outcome is unknown, and
	 * what the human has to do. Callers prepend what they were attempting.
	 */
	public static String timeoutAdvice(long timeoutMs) {
		return "timed out after " + (timeoutMs / 1000) + "s waiting for Eclipse's UI thread. Most " +
			"likely a modal dialog is waiting for a human in the Eclipse window (a save prompt, an " +
			"errors-exist-proceed question, an error dialog). Dismiss it there, then re-check the " +
			"target to see what actually happened — the work was not abandoned and may still " +
			"complete. Every other tool that touches the UI thread will block the same way until " +
			"the dialog is gone.";
	}
}

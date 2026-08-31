package ebbex.eclipsemcpserver.util;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;

/**
 * The single write path into the workspace &mdash; the Ghidra sibling's {@code Transactions}
 * analogue. Everything that mutates resources goes through {@link #run}, so there is one place
 * where the batching and the scheduling-rule discipline are decided and documented.
 *
 * <p><b>Batching.</b> {@code IWorkspace.run} defers resource-change notification until the body
 * returns, so listeners (JDT's builder among them) see one delta for the whole operation instead
 * of one per changed file. {@code AVOID_UPDATE} additionally tells the platform not to run the
 * auto-build/notification pass in the middle of the operation. That is what makes
 * "refresh 128 projects" one build trigger rather than thousands.
 *
 * <p><b>Rule discipline.</b> The rule passed here must <em>contain</em> every rule the body's
 * own API calls will begin, or the platform throws
 * {@code IllegalArgumentException: Attempted to beginRule: X, does not match outer scope rule: Y}.
 * It must also be no wider than that, because a rule is a workspace-wide lock. Verified against
 * this install's {@code org.eclipse.core.resources} (4.40 / bundle 3.24.0) by decompiling
 * {@code Resource}/{@code Project} and {@code ResourceRuleFactory}:
 *
 * <ul>
 * <li>{@code IResource.refreshLocal} begins {@code getRuleFactory().refreshRule(resource)}.
 *     The default factory's {@code refreshRule} is {@code parent(resource)}, and {@code parent}
 *     returns the resource itself for a PROJECT or the ROOT &mdash; so refreshing one project
 *     locks only that project, and refreshing the root locks the root.</li>
 * <li>{@code IProject.open} and {@code IProject.close} begin
 *     {@code getRuleFactory().modifyRule(project)}, which for a project is the project itself.</li>
 * <li>{@code IProject.build} / {@code IWorkspace.build} take the build rule themselves.
 *     {@code ResourceRuleFactory.buildRule()} is <em>final</em> and returns the workspace ROOT.
 *     Do <b>not</b> call them inside {@link #run} &mdash; nesting a build under a narrower rule
 *     is the {@code does not match outer scope rule} failure, and nesting it under the root rule
 *     buys nothing over letting the build take that rule itself.</li>
 * </ul>
 *
 * <p>Always ask the workspace's own {@code getRuleFactory()} rather than hardcoding a rule: the
 * platform's factory ({@code org.eclipse.core.internal.resources.Rules}) delegates per project to
 * whatever rule factory a team provider installed, and that provider is entitled to widen the
 * rule. Hardcoding the project would then be too narrow.
 *
 * <p><b>Never take the workspace ROOT rule blindly.</b> The root rule is exactly
 * {@code buildRule()}: holding it blocks every other resource operation in the IDE, and joining a
 * build job (or waiting on anything that needs to build) while holding it deadlocks. The root
 * rule is acceptable only when the operation genuinely spans the whole workspace &mdash; a
 * whole-workspace {@code refreshLocal}, where {@code refreshRule(root)} <em>is</em> the root, and
 * where the caller does no waiting inside the body.
 */
public final class WorkspaceOps {

	private WorkspaceOps() {
	}

	/**
	 * Run {@code body} as one batched workspace operation under {@code rule}.
	 *
	 * <p>Runs on the calling thread (the HTTP thread for short mutations, the
	 * {@link BuildQueue} worker for long ones) &mdash; the workspace is not UI-confined, so there
	 * is no thread hop here, unlike the Ghidra sibling's EDT round trip.
	 *
	 * @param rule the scheduling rule to hold for the whole operation; see the class javadoc.
	 *            May be null to hold no rule at all, which is only right for a body that takes
	 *            its own rules
	 * @throws CoreException whatever the body throws, plus the platform's own failures
	 */
	public static void run(IWorkspace workspace, ISchedulingRule rule, ICoreRunnable body)
			throws CoreException {
		workspace.run(body, rule, IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
	}
}

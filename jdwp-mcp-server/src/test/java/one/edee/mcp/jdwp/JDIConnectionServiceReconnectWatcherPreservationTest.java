package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Pins the contract that watchers survive {@code jdwp_reconnect}. The natural risk: the JDI
 * event listener's {@code stop()} method invokes a VM-death hook wired to
 * {@link JDIConnectionService#notifyVmDied()}, and that hook calls
 * {@link WatcherManager#clearAll()}. Without explicit detach/re-attach of the hook around the
 * reconnect's listener-stop step, the watchers documented as "Survives" in the reconnect
 * contract would be silently wiped.
 *
 * <p>The test exercises the attach-failure path because a real successful re-attach requires a
 * live JDWP target. The behaviour on the failure path is identical for the watcher-clearing
 * concern: if the hook fired during the listener stop, it would have wiped the watchers before
 * the attach was even attempted, and a subsequent throw would leave them gone.
 */
@DisplayName("JDIConnectionService.reconnectPreservingSpecs — watcher preservation")
class JDIConnectionServiceReconnectWatcherPreservationTest {

	@Test
	@DisplayName("watchers survive a reconnect even when the fresh attach fails")
	void shouldNotClearWatchersWhenReconnectAttachFails() throws Exception {
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		// Real listener — the wiring of vmDeathHook → notifyVmDied happens in the JDIConnectionService
		// constructor; using a real listener exercises the full path. The listener's start() is not
		// invoked because we never reach a successful attach.
		final JdiEventListener listener = new JdiEventListener(tracker, history, evaluator, guard, null, marks);
		final JDIConnectionService service = new JDIConnectionService(
			listener, tracker, history, watchers, guard, marks, new JdiHealthMonitor());

		// Seed a watcher that the reconnect must preserve.
		watchers.createWatcher("trace.id", 1, "entity.id");
		assertThat(watchers.getAllWatchers()).hasSize(1);

		// Plant a fake successful-attach target via reflection so reconnect's precondition passes.
		// The attach itself will fail (the port is unreachable), exercising the failure branch.
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "127.0.0.1", 1);

		assertThatThrownBy(service::reconnectPreservingSpecs)
			.isInstanceOf(Exception.class);

		// Watcher must still be present — the hook was detached around the listener stop so
		// notifyVmDied's clearAll could not run.
		assertThat(watchers.getAllWatchers())
			.as("watcher count after failed reconnect")
			.hasSize(1);
	}
}

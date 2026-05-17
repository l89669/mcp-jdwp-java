package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JDWPTools#jdwp_attach_watcher}: rejection of empty expressions, rejection of
 * blank labels, and the normal attach path.
 */
@DisplayName("jdwp_attach_watcher")
class JDWPToolsAttachWatcherTest {

	private WatcherManager watcherManager;
	private BreakpointTracker tracker;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		tracker = mock(BreakpointTracker.class);
		// Accept every breakpoint id by default — individual tests override for the rejection path.
		when(tracker.isKnownBreakpointId(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
		watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
	}

	@Test
	@DisplayName("rejects an empty expression")
	void shouldRejectEmptyExpression() {
		final String result = tools.jdwp_attach_watcher(1, "label", "   ");

		assertThat(result).startsWith("Error: No expression provided");
	}

	@Test
	@DisplayName("happy path — registers the watcher and returns the new one-line confirmation (P2-10)")
	void shouldRegisterWatcherAndReturnId() {
		final String result = tools.jdwp_attach_watcher(1, "trace-entity-id", "entity.id");

		// The renderer now collapses to a single line: '✓ Watcher [<id-prefix>] "label" attached
		// to BP #N (expr: <expression>)'. Old 7-line block was largely duplicate metadata.
		assertThat(result).startsWith("✓ Watcher [");
		assertThat(result).contains("\"trace-entity-id\"");
		assertThat(result).contains("BP #1");
		assertThat(result).contains("expr: entity.id");
		assertThat(watcherManager.getWatchersForBreakpoint(1)).hasSize(1);
	}

	/**
	 * A blank ({@code ""} or whitespace-only) label is rejected with the same kind of error used
	 * for empty expressions. Without this guard, the watcher would be created with an empty label
	 * and become unidentifiable in {@code jdwp_overview(types="watcher")}.
	 */
	@Test
	@DisplayName("rejects a blank label")
	void shouldAcceptBlankLabel() {
		final String result = tools.jdwp_attach_watcher(1, "   ", "entity.id");

		assertThat(result).startsWith("Error: No label provided");
		assertThat(watcherManager.getWatchersForBreakpoint(1)).isEmpty();
	}

	/**
	 * Regression for P1-1: attaching a watcher to an unknown BP id used to silently succeed,
	 * leaving an inert watcher in the registry with a misleading "attached successfully" message.
	 * The new contract rejects the attach with an [ERROR] envelope pointing the agent at
	 * {@code jdwp_overview} so they can see which BP ids actually exist.
	 */
	@Test
	@DisplayName("rejects attach to a non-existent breakpoint id (P1-1)")
	void shouldRejectAttachToUnknownBreakpointId() {
		when(tracker.isKnownBreakpointId(999)).thenReturn(false);

		final String result = tools.jdwp_attach_watcher(999, "ghost", "x");

		assertThat(result).startsWith("[ERROR]");
		assertThat(result).contains("999");
		assertThat(result).contains("jdwp_overview");
		// And no watcher must have been registered on the unknown id.
		assertThat(watcherManager.getWatchersForBreakpoint(999)).isEmpty();
	}
}

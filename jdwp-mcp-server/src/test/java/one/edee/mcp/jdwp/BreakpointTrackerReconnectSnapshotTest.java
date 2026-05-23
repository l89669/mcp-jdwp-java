package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the reconnect snapshot / restore contract on
 * {@link BreakpointTracker}. The contract: synthetic IDs survive verbatim across
 * {@code snapshotForReconnect()} → {@code restoreFromSnapshotAsPending()}; metadata and chain
 * edges are restored; everything comes back as <i>pending</i> regardless of whether the
 * original entry was active or pending, because the live JDI request handles are dead.
 */
@DisplayName("BreakpointTracker reconnect snapshot/restore")
class BreakpointTrackerReconnectSnapshotTest {

	private BreakpointTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
	}

	@Test
	@DisplayName("synthetic IDs survive the round-trip")
	void shouldPreserveSyntheticIds() {
		final int lineId = registerActiveLineBp("com.example.A", 10);
		final int pendingLineId = tracker.registerPendingBreakpoint("com.example.B", 20, EventRequest.SUSPEND_ALL, "all");
		final int exceptionId = registerActiveExceptionBp("com.example.X");

		final BreakpointTracker.ReconnectSnapshot snapshot = tracker.snapshotForReconnect();
		tracker.restoreFromSnapshotAsPending(snapshot);

		// Every restored entry is pending (live JDI requests cannot survive vm.dispose), but the
		// IDs match the originals so watchers and chain edges keyed on the IDs stay valid.
		assertThat(tracker.getPendingBreakpoint(lineId)).isNotNull();
		assertThat(tracker.getPendingBreakpoint(pendingLineId)).isNotNull();
		assertThat(tracker.getAllPendingExceptionBreakpoints()).containsKey(exceptionId);
		assertThat(tracker.getAllBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("the next mint after restore does not collide with restored IDs")
	void shouldAdvanceIdCounterPastRestoredIds() {
		// Mint a handful of entries, snapshot the highest, restore, then mint another and verify
		// it does not collide.
		final int firstId = tracker.registerPendingBreakpoint("com.example.A", 1, EventRequest.SUSPEND_ALL, "all");
		final int secondId = tracker.registerPendingBreakpoint("com.example.B", 2, EventRequest.SUSPEND_ALL, "all");
		final int thirdId = tracker.registerPendingBreakpoint("com.example.C", 3, EventRequest.SUSPEND_ALL, "all");

		final BreakpointTracker.ReconnectSnapshot snapshot = tracker.snapshotForReconnect();
		tracker.restoreFromSnapshotAsPending(snapshot);

		final int nextId = tracker.registerPendingBreakpoint("com.example.D", 4, EventRequest.SUSPEND_ALL, "all");
		assertThat(nextId).isGreaterThan(Math.max(firstId, Math.max(secondId, thirdId)));
	}

	@Test
	@DisplayName("metadata (conditions, logpoint expressions) survives")
	void shouldPreserveMetadata() {
		final int lineId = registerActiveLineBp("com.example.A", 10);
		tracker.setCondition(lineId, "x > 0");
		tracker.setLogpointExpression(lineId, "x");

		final BreakpointTracker.ReconnectSnapshot snapshot = tracker.snapshotForReconnect();
		tracker.restoreFromSnapshotAsPending(snapshot);

		assertThat(tracker.getCondition(lineId)).isEqualTo("x > 0");
		assertThat(tracker.getLogpointExpression(lineId)).isEqualTo("x");
		assertThat(tracker.isLogpoint(lineId)).isTrue();
	}

	@Test
	@DisplayName("chain edges (dependency + reverse index) survive")
	void shouldPreserveChainEdges() {
		final int triggerId = tracker.registerPendingBreakpoint("com.example.A", 10, EventRequest.SUSPEND_ALL, "all");
		final int dependentId = tracker.registerPendingBreakpoint("com.example.B", 20, EventRequest.SUSPEND_ALL, "all");
		tracker.registerDependency(dependentId, triggerId, /* oneShot */ true);

		final BreakpointTracker.ReconnectSnapshot snapshot = tracker.snapshotForReconnect();
		tracker.restoreFromSnapshotAsPending(snapshot);

		final BreakpointTracker.TriggerLink link = tracker.getDependencyOfDependent(dependentId);
		assertThat(link).isNotNull();
		assertThat(link.triggerId()).isEqualTo(triggerId);
		assertThat(link.oneShot()).isTrue();
		assertThat(tracker.getDependentsOfTrigger(triggerId)).containsExactly(dependentId);
	}

	@Test
	@DisplayName("trigger-fire memory survives so a chained dependent still comes up armed")
	void shouldPreserveTriggerFiredMemory() {
		final int triggerId = tracker.registerPendingBreakpoint("com.example.A", 10, EventRequest.SUSPEND_ALL, "all");
		tracker.markTriggerFired(triggerId);

		final BreakpointTracker.ReconnectSnapshot snapshot = tracker.snapshotForReconnect();
		tracker.restoreFromSnapshotAsPending(snapshot);

		assertThat(tracker.hasTriggerFired(triggerId)).isTrue();
	}

	private int registerActiveLineBp(String className, int lineNumber) {
		final BreakpointRequest req = mock(BreakpointRequest.class);
		final Location loc = mock(Location.class);
		final ReferenceType refType = mock(ReferenceType.class);
		when(req.location()).thenReturn(loc);
		when(req.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);
		when(loc.declaringType()).thenReturn(refType);
		when(loc.lineNumber()).thenReturn(lineNumber);
		when(refType.name()).thenReturn(className);
		return tracker.registerBreakpoint(req);
	}

	private int registerActiveExceptionBp(String exceptionClass) {
		final ExceptionRequest req = mock(ExceptionRequest.class);
		return tracker.registerExceptionBreakpoint(req,
			BreakpointTracker.ExceptionBreakpointSpec.suspending(exceptionClass, true, true));
	}
}

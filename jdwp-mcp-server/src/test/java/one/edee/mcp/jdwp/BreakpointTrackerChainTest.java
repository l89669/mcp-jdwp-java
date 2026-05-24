package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import one.edee.mcp.jdwp.BreakpointTracker.ChainRegistrationException;
import one.edee.mcp.jdwp.BreakpointTracker.ExceptionBreakpointSpec;
import one.edee.mcp.jdwp.BreakpointTracker.TriggerLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the chain-of-breakpoints CRUD surface on {@link BreakpointTracker}: register / clear
 * a dependency, query both sides of the relationship, cascade-detach when a trigger goes away,
 * and lookup the underlying {@link com.sun.jdi.request.EventRequest} by synthetic ID. Also covers
 * the dependent-side cleanup on BP removal and the chain-state wipe on reset, so the tracker's
 * invariants stay aligned with the rest of its bookkeeping.
 */
class BreakpointTrackerChainTest {

	private BreakpointTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
	}

	/**
	 * Registers {@code count} dummy line BPs so the tracker hands out IDs 1..count. Tests of the
	 * pure chain data structure use bare integer IDs ({@code 1}, {@code 2}, {@code 5}…); since
	 * {@link BreakpointTracker#registerDependency} validates that the trigger is a known BP (the
	 * atomic-validation guarantee), each such test seeds the tracker with the IDs it intends to
	 * use.
	 */
	private void seedDummyBreakpoints(int count) {
		for (int i = 0; i < count; i++) {
			tracker.registerBreakpoint(mock(BreakpointRequest.class));
		}
	}

	@Nested
	@DisplayName("registerDependency / getDependencyOfDependent")
	class RegisterAndRetrieve {

		@Test
		@DisplayName("retrieves a registered dependency by dependent ID")
		void shouldRegisterAndRetrieve() {
			seedDummyBreakpoints(2);
			tracker.registerDependency(2, 1, false);
			TriggerLink link = tracker.getDependencyOfDependent(2);

			assertThat(link).isNotNull();
			assertThat(link.triggerId()).isEqualTo(1);
			assertThat(link.oneShot()).isFalse();
		}

		@Test
		@DisplayName("returns null for an unknown dependent")
		void shouldReturnNullForUnknownDependent() {
			assertThat(tracker.getDependencyOfDependent(999)).isNull();
		}

		@Test
		@DisplayName("re-registering with a different trigger updates the reverse index")
		void shouldReplaceTriggerOnReRegister() {
			seedDummyBreakpoints(5);
			tracker.registerDependency(2, 1, false);
			tracker.registerDependency(2, 5, true);

			TriggerLink link = tracker.getDependencyOfDependent(2);
			assertThat(link.triggerId()).isEqualTo(5);
			assertThat(link.oneShot()).isTrue();
			assertThat(tracker.getDependentsOfTrigger(1)).isEmpty();
			assertThat(tracker.getDependentsOfTrigger(5)).containsExactly(2);
		}

		@Test
		@DisplayName("re-registering same trigger can flip the oneShot flag")
		void shouldUpdateOneShotOnReRegister() {
			seedDummyBreakpoints(2);
			tracker.registerDependency(2, 1, false);
			tracker.registerDependency(2, 1, true);

			assertThat(tracker.getDependencyOfDependent(2).oneShot()).isTrue();
			assertThat(tracker.getDependentsOfTrigger(1)).containsExactly(2);
		}

		/**
		 * The tracker rejects every cycle including the trivial 1-hop self-edge (a {@code dependent
		 * → dependent} chain would close the cycle in one step). The {@link JDWPTools} tool-boundary
		 * still has its own {@code dependentId == triggerId} fast path so the user sees the
		 * friendlier "cannot be its own trigger" message, but direct callers of
		 * {@link BreakpointTracker#registerDependency} get the generic cycle exception.
		 */
		@Test
		@DisplayName("rejects a self-chain as a 1-hop cycle")
		void shouldRejectSelfChainAtTrackerLevel() {
			seedDummyBreakpoints(5);

			assertThatThrownBy(() -> tracker.registerDependency(5, 5, false))
				.isInstanceOf(ChainRegistrationException.class)
				.satisfies(ex -> assertThat(((ChainRegistrationException) ex).reason())
					.isEqualTo(ChainRegistrationException.Reason.CYCLE));

			assertThat(tracker.getDependencyOfDependent(5)).isNull();
			assertThat(tracker.getDependentsOfTrigger(5)).isEmpty();
		}

		/**
		 * Re-registering an identical {@code (dependent, trigger, oneShot)} triple must be a
		 * pure no-op: the reverse index stays at size 1 (no duplicate dependent entry), and the
		 * dependent set still contains exactly the single dependent. Guards against a regression
		 * where the reverse-index cleanup branch fires only when the trigger changes.
		 */
		@Test
		@DisplayName("re-registering identical link is a no-op")
		void shouldBeNoOpWhenReRegisteringIdenticalLink() {
			seedDummyBreakpoints(2);
			tracker.registerDependency(2, 1, false);
			tracker.registerDependency(2, 1, false);

			assertThat(tracker.getDependentsOfTrigger(1)).hasSize(1);
			assertThat(tracker.getDependentsOfTrigger(1)).containsExactly(2);
		}

		/**
		 * {@link BreakpointTracker#registerDependency} walks the existing chain graph and refuses
		 * to add an edge that would close a cycle. A {@code 2 → 1 → 2} cycle therefore fails at the
		 * second registration with a {@link ChainRegistrationException} whose path describes the
		 * would-be cycle.
		 */
		@Test
		@DisplayName("rejects a 2→1→2 cycle (multi-hop cycle detection)")
		void shouldRejectTwoHopCycle() {
			seedDummyBreakpoints(2);
			tracker.registerDependency(2, 1, false);

			assertThatThrownBy(() -> tracker.registerDependency(1, 2, false))
				.isInstanceOf(ChainRegistrationException.class)
				.satisfies(ex -> {
					ChainRegistrationException cre = (ChainRegistrationException) ex;
					assertThat(cre.reason()).isEqualTo(ChainRegistrationException.Reason.CYCLE);
					assertThat(cre.cyclePath()).containsExactly(1, 2, 1);
				});

			// State must be unchanged: the original 2 → 1 edge survives, the would-be 1 → 2 edge
			// was never added.
			assertThat(tracker.getDependencyOfDependent(2)).isNotNull();
			assertThat(tracker.getDependencyOfDependent(2).triggerId()).isEqualTo(1);
			assertThat(tracker.getDependencyOfDependent(1)).isNull();
			assertThat(tracker.getDependentsOfTrigger(1)).containsExactly(2);
			assertThat(tracker.getDependentsOfTrigger(2)).isEmpty();
		}

		/**
		 * Atomic validation inside {@link BreakpointTracker#registerDependency}. The boundary check
		 * at {@link JDWPTools#jdwp_set_breakpoint_dependency} cannot prevent a concurrent
		 * {@link BreakpointTracker#removeBreakpoint} between the check and the registration. The
		 * tracker re-validates inside its {@code synchronized} block and throws
		 * {@link ChainRegistrationException} when the trigger has gone away. This is the
		 * deterministic version: explicitly remove the trigger BP before calling
		 * {@code registerDependency} and assert the rejection — the concurrent race version stays
		 * {@code @Disabled} in {@code JDWPToolsChainToolsTest}.
		 */
		@Test
		@DisplayName("rejects registration when trigger BP was removed before the call")
		void shouldRejectRegistrationWhenTriggerWasRemoved() {
			BreakpointRequest triggerBp = mock(BreakpointRequest.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(triggerBp.virtualMachine()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int triggerId = tracker.registerBreakpoint(triggerBp);
			int dependentId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			// Simulate the racing thread: trigger gets removed between the user's boundary check
			// and the registerDependency call.
			tracker.removeBreakpoint(triggerId);

			assertThatThrownBy(() -> tracker.registerDependency(dependentId, triggerId, false))
				.isInstanceOf(ChainRegistrationException.class)
				.satisfies(ex -> {
					ChainRegistrationException cre = (ChainRegistrationException) ex;
					assertThat(cre.reason()).isEqualTo(ChainRegistrationException.Reason.MISSING_TRIGGER);
					assertThat(cre.triggerId()).isEqualTo(triggerId);
				});

			// Sanity: the dependent did NOT get a chain edge from the failed registration.
			assertThat(tracker.getDependencyOfDependent(dependentId)).isNull();
		}

		/**
		 * Three-hop cycle coverage: {@code 4 → 3 → 2 → 1 → 4} closes at the last edge. Ensures the
		 * walk in {@link BreakpointTracker#registerDependency} follows transitive trigger links,
		 * not just the immediate one.
		 */
		@Test
		@DisplayName("rejects a 4→3→2→1→4 multi-hop cycle")
		void shouldRejectThreeHopCycle() {
			seedDummyBreakpoints(4);
			tracker.registerDependency(3, 2, false);
			tracker.registerDependency(2, 1, false);
			tracker.registerDependency(4, 3, false);

			assertThatThrownBy(() -> tracker.registerDependency(1, 4, false))
				.isInstanceOf(ChainRegistrationException.class)
				.satisfies(ex -> assertThat(((ChainRegistrationException) ex).reason())
					.isEqualTo(ChainRegistrationException.Reason.CYCLE));

			// Edge never landed: 1 has no upstream trigger, and 4 has no dependents.
			assertThat(tracker.getDependencyOfDependent(1)).isNull();
			assertThat(tracker.getDependentsOfTrigger(4)).isEmpty();
			// The original three-link chain survives intact.
			assertThat(tracker.getDependentsOfTrigger(3)).containsExactly(4);
			assertThat(tracker.getDependentsOfTrigger(2)).containsExactly(3);
			assertThat(tracker.getDependentsOfTrigger(1)).containsExactly(2);
		}
	}

	@Nested
	@DisplayName("getDependentsOfTrigger reverse index")
	class ReverseIndex {

		@Test
		@DisplayName("returns every dependent registered against a trigger")
		void shouldReturnAllDependents() {
			seedDummyBreakpoints(99);
			tracker.registerDependency(2, 1, false);
			tracker.registerDependency(3, 1, true);
			tracker.registerDependency(4, 99, false);

			Set<Integer> deps = tracker.getDependentsOfTrigger(1);
			assertThat(deps).containsExactlyInAnyOrder(2, 3);
		}

		@Test
		@DisplayName("returns empty for a trigger with no dependents")
		void shouldReturnEmptyForUnknownTrigger() {
			assertThat(tracker.getDependentsOfTrigger(999)).isEmpty();
		}

		@Test
		@DisplayName("returns an immutable snapshot")
		void shouldReturnImmutableSet() {
			seedDummyBreakpoints(2);
			tracker.registerDependency(2, 1, false);
			Set<Integer> deps = tracker.getDependentsOfTrigger(1);
			assertThatCode(() -> deps.add(99)).isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	@DisplayName("clearDependency / clearDependentsOfTrigger")
	class Detach {

		@Test
		@DisplayName("clears a single dependent and returns the prior link")
		void shouldClearSingleDependency() {
			seedDummyBreakpoints(2);
			tracker.registerDependency(2, 1, true);
			TriggerLink previous = tracker.clearDependency(2);

			assertThat(previous).isNotNull();
			assertThat(previous.triggerId()).isEqualTo(1);
			assertThat(previous.oneShot()).isTrue();
			assertThat(tracker.getDependencyOfDependent(2)).isNull();
			assertThat(tracker.getDependentsOfTrigger(1)).isEmpty();
		}

		@Test
		@DisplayName("returns null when clearing a non-existent dependency")
		void shouldReturnNullWhenClearingUnknown() {
			assertThat(tracker.clearDependency(999)).isNull();
		}

		@Test
		@DisplayName("cascade-detaches every dependent of a trigger and returns their IDs")
		void shouldCascadeDetachAllDependents() {
			seedDummyBreakpoints(99);
			tracker.registerDependency(2, 1, false);
			tracker.registerDependency(3, 1, true);
			tracker.registerDependency(4, 99, false);

			Set<Integer> detached = tracker.clearDependentsOfTrigger(1);

			assertThat(detached).containsExactlyInAnyOrder(2, 3);
			assertThat(tracker.getDependencyOfDependent(2)).isNull();
			assertThat(tracker.getDependencyOfDependent(3)).isNull();
			assertThat(tracker.getDependentsOfTrigger(1)).isEmpty();
			// Unrelated chain survives
			assertThat(tracker.getDependencyOfDependent(4)).isNotNull();
			assertThat(tracker.getDependentsOfTrigger(99)).containsExactly(4);
		}

		@Test
		@DisplayName("cascade-detach returns empty when the trigger has no dependents")
		void shouldReturnEmptyWhenNoDependents() {
			assertThat(tracker.clearDependentsOfTrigger(999)).isEmpty();
		}

		/**
		 * Cascade-detach must be idempotent: after every dependent has already been detached
		 * (e.g., via {@link BreakpointTracker#clearDependency}), a follow-up
		 * {@link BreakpointTracker#clearDependentsOfTrigger} call must still return an empty set
		 * without throwing. Guards the cleanup paths in {@link JDWPTools#cascadeChainBreak}.
		 */
		@Test
		@DisplayName("returns empty (no NPE) after every dependent was already detached")
		void shouldReturnEmptyAfterAllDependentsAlreadyDetached() {
			seedDummyBreakpoints(2);
			tracker.registerDependency(2, 1, false);
			tracker.clearDependency(2);

			assertThat(tracker.clearDependentsOfTrigger(1)).isEmpty();
			// And a follow-up call still does not throw.
			assertThat(tracker.clearDependentsOfTrigger(1)).isEmpty();
		}
	}

	@Nested
	@DisplayName("getEventRequestById / findExceptionIdByRequest")
	class RequestLookup {

		@Test
		@DisplayName("returns the line BP request for a line ID")
		void shouldResolveLineRequest() {
			BreakpointRequest bp = mock(BreakpointRequest.class);
			int id = tracker.registerBreakpoint(bp);

			assertThat(tracker.getEventRequestById(id)).isSameAs(bp);
		}

		@Test
		@DisplayName("returns the exception request for an exception BP ID")
		void shouldResolveExceptionRequest() {
			ExceptionRequest exReq = mock(ExceptionRequest.class);
			int id = tracker.registerExceptionBreakpoint(exReq,
				ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));

			assertThat(tracker.getEventRequestById(id)).isSameAs(exReq);
		}

		@Test
		@DisplayName("returns null for an unknown ID (including pending IDs that have no underlying request yet)")
		void shouldReturnNullForUnknownId() {
			tracker.registerPendingBreakpoint("com.Pending", 42, 2, "ALL");
			assertThat(tracker.getEventRequestById(9999)).isNull();
			// Pending BPs have no underlying JDI request yet — also resolve to null.
			assertThat(tracker.getEventRequestById(1)).isNull();
		}

		@Test
		@DisplayName("finds the exception BP ID from the underlying request")
		void shouldFindIdByRequest() {
			ExceptionRequest exReq = mock(ExceptionRequest.class);
			int id = tracker.registerExceptionBreakpoint(exReq,
				ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));

			assertThat(tracker.findExceptionIdByRequest(exReq)).isEqualTo(id);
			assertThat(tracker.findExceptionIdByRequest(mock(ExceptionRequest.class))).isNull();
		}
	}

	@Nested
	@DisplayName("dependent-side cleanup on BP removal")
	class Cleanup {

		@Test
		@DisplayName("removeBreakpoint clears the chain entry for the removed BP")
		void shouldClearDependentSideOnLineRemove() {
			BreakpointRequest bp = mock(BreakpointRequest.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(bp.virtualMachine()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int dependentId = tracker.registerBreakpoint(bp);
			tracker.registerDependency(dependentId, triggerId, false);

			tracker.removeBreakpoint(dependentId);

			assertThat(tracker.getDependencyOfDependent(dependentId)).isNull();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).isEmpty();
		}

		@Test
		@DisplayName("removeExceptionBreakpoint clears the chain entry too")
		void shouldClearDependentSideOnExceptionRemove() {
			ExceptionRequest exReq = mock(ExceptionRequest.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(exReq.virtualMachine()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int dependentId = tracker.registerExceptionBreakpoint(exReq,
				ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));
			tracker.registerDependency(dependentId, triggerId, false);

			tracker.removeExceptionBreakpoint(dependentId);

			assertThat(tracker.getDependencyOfDependent(dependentId)).isNull();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).isEmpty();
		}

		@Test
		@DisplayName("reset wipes all chain state")
		void shouldWipeChainStateOnReset() {
			seedDummyBreakpoints(3);
			tracker.registerDependency(2, 1, false);
			tracker.registerDependency(3, 1, true);

			tracker.reset();

			assertThat(tracker.getDependencyOfDependent(2)).isNull();
			assertThat(tracker.getDependencyOfDependent(3)).isNull();
			assertThat(tracker.getDependentsOfTrigger(1)).isEmpty();
		}

		/**
		 * {@link BreakpointTracker#removePendingBreakpoint} mirrors the chain + metadata cleanup
		 * performed by {@link BreakpointTracker#removeBreakpoint}, so a direct removal of a pending
		 * BP leaves no ghost dependent entry behind.
		 */
		@Test
		@DisplayName("removePendingBreakpoint clears the chain entry for the removed BP")
		void shouldClearChainEntryWhenPendingBreakpointIsRemovedDirectly() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			tracker.registerDependency(pendingId, triggerId, false);

			boolean removed = tracker.removePendingBreakpoint(pendingId);

			assertThat(removed).isTrue();
			assertThat(tracker.getDependencyOfDependent(pendingId)).isNull();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).doesNotContain(pendingId);
		}
	}

	/**
	 * The tracker persists "this trigger has fired at least once" across pending → active
	 * promotion. The JDI listener calls {@link BreakpointTracker#markTriggerFired} on every BP hit
	 * unconditionally; the {@link BreakpointTracker#disarmIfChained} promotion helper consults
	 * {@link BreakpointTracker#hasTriggerFired} and only disables the new request when the trigger
	 * has NOT fired yet, so a chained dependent whose class loads after the trigger has already
	 * executed comes up armed instead of disarmed.
	 */
	@Nested
	@DisplayName("Trigger-fire memory across pending → active promotion")
	class PendingTriggerMemory {

		@Test
		@DisplayName("markTriggerFired is remembered across the pending → active boundary")
		void shouldRememberTriggerFiresWhilePending() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingDependentId = tracker.registerPendingBreakpoint("com.example.Late", 10, 2, "ALL");
			tracker.registerDependency(pendingDependentId, triggerId, false);

			assertThat(tracker.hasTriggerFired(triggerId)).isFalse();
			tracker.markTriggerFired(triggerId);
			assertThat(tracker.hasTriggerFired(triggerId)).isTrue();
		}

		/**
		 * disarmIfChained must NOT disable a freshly-promoted dependent when its trigger has
		 * already fired — the dependent has to come up armed so it catches the next hit at its
		 * location (the trigger gating has already been satisfied earlier in the session).
		 */
		@Test
		@DisplayName("disarmIfChained leaves dependent enabled when trigger has already fired")
		void shouldLeaveDependentEnabledWhenTriggerAlreadyFired() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingDependentId = tracker.registerPendingBreakpoint("com.example.Late", 10, 2, "ALL");
			tracker.registerDependency(pendingDependentId, triggerId, false);
			tracker.markTriggerFired(triggerId);

			BreakpointRequest promotedRequest = mock(BreakpointRequest.class);
			boolean disarmed = tracker.disarmIfChained(pendingDependentId, promotedRequest);

			assertThat(disarmed).isFalse();
			org.mockito.Mockito.verify(promotedRequest, org.mockito.Mockito.never()).setEnabled(false);
		}

		/**
		 * Counterpart: when the trigger has NOT fired yet, {@code disarmIfChained} still disables
		 * the promoted request — the historical behaviour for a fresh chain is preserved.
		 */
		@Test
		@DisplayName("disarmIfChained still disables dependent when trigger has not fired yet")
		void shouldDisableDependentWhenTriggerHasNotFiredYet() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingDependentId = tracker.registerPendingBreakpoint("com.example.Late", 10, 2, "ALL");
			tracker.registerDependency(pendingDependentId, triggerId, false);

			BreakpointRequest promotedRequest = mock(BreakpointRequest.class);
			boolean disarmed = tracker.disarmIfChained(pendingDependentId, promotedRequest);

			assertThat(disarmed).isTrue();
			org.mockito.Mockito.verify(promotedRequest).setEnabled(false);
		}

		/**
		 * The trigger-fired memory is scoped to the session: a full {@link BreakpointTracker#reset}
		 * wipes it so a re-attached session starts fresh.
		 */
		@Test
		@DisplayName("reset wipes the trigger-fired memory")
		void shouldClearTriggerFiredMemoryOnReset() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			tracker.markTriggerFired(triggerId);
			assertThat(tracker.hasTriggerFired(triggerId)).isTrue();

			tracker.reset();
			assertThat(tracker.hasTriggerFired(triggerId)).isFalse();
		}

		/**
		 * Removing the trigger BP itself wipes its fire history so a future BP that reuses the
		 * synthetic ID does not inherit the previous fires.
		 */
		@Test
		@DisplayName("removeBreakpoint(trigger) wipes the trigger-fired flag")
		void shouldClearTriggerFiredFlagWhenTriggerRemoved() {
			BreakpointRequest triggerBp = mock(BreakpointRequest.class);
			com.sun.jdi.VirtualMachine vm = mock(com.sun.jdi.VirtualMachine.class);
			com.sun.jdi.request.EventRequestManager erm = mock(com.sun.jdi.request.EventRequestManager.class);
			when(triggerBp.virtualMachine()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int triggerId = tracker.registerBreakpoint(triggerBp);
			tracker.markTriggerFired(triggerId);
			tracker.removeBreakpoint(triggerId);

			assertThat(tracker.hasTriggerFired(triggerId)).isFalse();
		}
	}
}

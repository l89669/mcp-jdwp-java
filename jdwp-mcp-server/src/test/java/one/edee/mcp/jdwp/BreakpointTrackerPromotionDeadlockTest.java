package one.edee.mcp.jdwp;

import com.sun.jdi.Field;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.AccessWatchpointRequest;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the monitor-release contract in {@link BreakpointTracker#tryPromotePending}:
 * the tracker monitor must NOT be held across the per-entry
 * {@link JDIConnectionService#findLoadedClass} lookup, so a concurrent listener-side
 * {@link BreakpointTracker#promotePendingFieldsForClass} (which acquires the same monitor) can
 * proceed in parallel. Pre-fix, the worker held the monitor across the lookup and the listener
 * blocked on it; post-fix, the listener runs to completion immediately while the worker is mid-
 * lookup. The simulated lookup uses a latch to keep the worker suspended past the listener's
 * observation window, mimicking a slow {@code vm.allClasses()} scan or — historically — a parked
 * {@code Class.forName} invoke. Both scenarios are covered by the same shape: the property under
 * test is "monitor released across the lookup", not the specific reason the lookup is slow.
 *
 * <p>Three variants cover the three call sites in {@code tryPromotePending} — line BP, exception
 * BP, and field BP — so a partial regression that only re-introduces the bug on one path is
 * caught by the other two. Each variant also asserts the post-release behaviour: once the
 * simulated lookup returns, the worker must finish promotion (active entry visible under the
 * same synthetic id) instead of silently no-oping.
 */
class BreakpointTrackerPromotionDeadlockTest {

	/** Time the listener thread gets to either complete or settle into BLOCKED on the monitor. */
	private static final long LISTENER_OBSERVATION_WINDOW_MS = 1_000L;

	/** Bound on how long the simulated JDI invoke waits before giving up — must exceed the observation window. */
	private static final long WORKER_RELEASE_TIMEOUT_S = 5L;

	@Test
	@DisplayName("line-BP promotion: listener can promote in parallel and worker completes after release")
	void lineBreakpointPromotion_listenerProceedsAndWorkerPromotesAfterRelease() throws Exception {
		BreakpointTracker tracker = new BreakpointTracker();
		JDIConnectionService service = mock(JDIConnectionService.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		BreakpointRequest bp = mock(BreakpointRequest.class);

		when(service.getRawVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(refType.name()).thenReturn("com.x.Foo");
		lenient().when(refType.locationsOfLine(42)).thenReturn(List.of(loc));
		lenient().when(erm.createBreakpointRequest(loc)).thenReturn(bp);

		int pendingId = tracker.registerPendingBreakpoint("com.x.Foo", 42, EventRequest.SUSPEND_ALL, "ALL");

		runDeadlockProbe(tracker, service, vm, erm, refType);

		assertThat(tracker.getBreakpoint(pendingId))
			.as("line BP must be promoted to active after the worker's simulated invoke returns")
			.isSameAs(bp);
	}

	@Test
	@DisplayName("exception-BP promotion: listener can promote in parallel and worker completes after release")
	void exceptionBreakpointPromotion_listenerProceedsAndWorkerPromotesAfterRelease() throws Exception {
		BreakpointTracker tracker = new BreakpointTracker();
		JDIConnectionService service = mock(JDIConnectionService.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ReferenceType refType = mock(ReferenceType.class);
		ExceptionRequest exReq = mock(ExceptionRequest.class);

		when(service.getRawVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(refType.name()).thenReturn("com.x.Foo");
		lenient().when(erm.createExceptionRequest(refType, true, true)).thenReturn(exReq);

		int pendingId = tracker.registerPendingExceptionBreakpoint(
			BreakpointTracker.ExceptionBreakpointSpec.suspending("com.x.Foo", true, true));

		runDeadlockProbe(tracker, service, vm, erm, refType);

		assertThat(tracker.getAllExceptionBreakpoints().get(pendingId))
			.as("exception BP must be promoted to active after the worker's simulated invoke returns")
			.isNotNull();
		assertThat(tracker.getAllExceptionBreakpoints().get(pendingId).getRequest()).isSameAs(exReq);
	}

	@Test
	@DisplayName("field-BP promotion: listener can promote in parallel and worker completes after release")
	void fieldBreakpointPromotion_listenerProceedsAndWorkerPromotesAfterRelease() throws Exception {
		BreakpointTracker tracker = new BreakpointTracker();
		JDIConnectionService service = mock(JDIConnectionService.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field field = mock(Field.class);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);

		when(service.getRawVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(refType.name()).thenReturn("com.x.Foo");
		lenient().when(refType.allFields()).thenReturn(List.of(field));
		lenient().when(field.name()).thenReturn("bar");
		lenient().when(field.isStatic()).thenReturn(false);
		lenient().when(erm.createAccessWatchpointRequest(field)).thenReturn(accessReq);

		int pendingId = tracker.registerPendingFieldBreakpoint(BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null));

		runDeadlockProbe(tracker, service, vm, erm, refType);

		assertThat(tracker.getAllFieldBreakpoints().get(pendingId))
			.as("field BP must be promoted to active after the worker's simulated invoke returns")
			.isNotNull();
		assertThat(tracker.getAllFieldBreakpoints().get(pendingId).getAccessRequest()).isSameAs(accessReq);
	}

	/**
	 * Recheck-race coverage for the field BP arm: while the worker is mid-lookup in
	 * {@link JDIConnectionService#findLoadedClass}, the JDI listener calls
	 * {@link BreakpointTracker#promotePendingFieldsForClass} on the same {@code ReferenceType} and
	 * wins the race. When the worker eventually re-acquires the tracker monitor, its recheck must
	 * observe that the pending entry has been removed and return 0 promotions instead of double-
	 * promoting the same synthetic id onto a second watchpoint request.
	 */
	@Test
	@DisplayName("field-BP tryPromotePending returns 0 when listener wins the promotion race")
	void tryPromotePending_returnsZeroWhenListenerPromotesFieldEntryWhileWorkerIsParkedInJdiInvoke() throws Exception {
		BreakpointTracker tracker = new BreakpointTracker();
		JDIConnectionService service = mock(JDIConnectionService.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field field = mock(Field.class);
		AccessWatchpointRequest workerAccessReq = mock(AccessWatchpointRequest.class, "workerAccessReq");
		AccessWatchpointRequest listenerAccessReq = mock(AccessWatchpointRequest.class, "listenerAccessReq");

		when(service.getRawVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(refType.name()).thenReturn("com.x.Foo");
		when(refType.allFields()).thenReturn(List.of(field));
		when(field.name()).thenReturn("bar");
		when(field.isStatic()).thenReturn(false);
		// First call (from the listener while the worker is parked) returns the listener's request;
		// any subsequent call (e.g. if the recheck failed to short-circuit) would observe the worker
		// request — making a missed recheck visible as a second watchpoint armed on the target VM.
		when(erm.createAccessWatchpointRequest(field))
			.thenReturn(listenerAccessReq)
			.thenReturn(workerAccessReq);

		int pendingId = tracker.registerPendingFieldBreakpoint(BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null));

		CountDownLatch workerInsideInvoke = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		when(service.findLoadedClass(eq("com.x.Foo")))
			.thenAnswer(inv -> {
				workerInsideInvoke.countDown();
				if (!releaseWorker.await(WORKER_RELEASE_TIMEOUT_S, TimeUnit.SECONDS)) {
					throw new IllegalStateException("worker latch timed out");
				}
				return refType;
			});

		AtomicBoolean workerSawPromotion = new AtomicBoolean(false);
		Thread worker = new Thread(() -> {
			int promoted = tracker.tryPromotePending(service);
			workerSawPromotion.set(promoted > 0);
		}, "field-recheck-race-worker");
		worker.setDaemon(true);
		worker.start();

		assertThat(workerInsideInvoke.await(2, TimeUnit.SECONDS))
			.as("worker must reach the simulated JDI invoke within 2s")
			.isTrue();

		// Listener promotes the entry while the worker is parked. This is the actual race we're
		// modelling — handleClassPrepareEvent calls promotePendingFieldsForClass synchronously.
		int listenerPromoted = tracker.promotePendingFieldsForClass(refType, vm, erm, service);
		assertThat(listenerPromoted)
			.as("listener path must promote the entry while the worker is still parked")
			.isEqualTo(1);

		releaseWorker.countDown();
		worker.join(WORKER_RELEASE_TIMEOUT_S * 1_000);

		assertThat(workerSawPromotion.get())
			.as("worker's recheck must observe the listener-side promotion and return 0 promotions")
			.isFalse();
		assertThat(tracker.getAllFieldBreakpoints().get(pendingId).getAccessRequest())
			.as("the listener-side request must remain bound under the synthetic id — not the worker's")
			.isSameAs(listenerAccessReq);
	}

	/**
	 * Guards the outer-monitor decoupling between {@link JDIConnectionService} and
	 * {@link BreakpointTracker#tryPromotePending}: {@code JDIConnectionService.getVM()} must NOT
	 * hold the service monitor across the call to {@code tryPromotePending}, because the promotion
	 * path can stall for a while (the per-entry passive class lookup walks
	 * {@code vm.allClasses()} which is O(loaded-classes) on the target VM). Holding the monitor
	 * across that window would block every other {@code getVM()} caller — and thus every other
	 * MCP tool call — until the slow lookup returns.
	 *
	 * <p>The connect / auto-reconnect work still runs under the monitor; only the opportunistic
	 * promotion call is moved outside. A second {@code getVM()} caller must therefore make progress
	 * while the first is parked.
	 *
	 * <p>Because the contended monitor is the {@link JDIConnectionService} instance itself, this
	 * test uses a real (un-mocked) {@code JDIConnectionService} with a {@link BreakpointTracker}
	 * subclass that signals via a latch — letting the test race a second {@code getVM()} call
	 * against the parked worker.
	 */
	@Test
	@DisplayName("getVM() second caller proceeds while another thread is mid-promotion")
	void getVm_secondCallerProceedsWhileFirstIsParkedInDeferredClassLoadPhase2() throws Exception {
		CountDownLatch workerInsideInvoke = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);

		BreakpointTracker blockingTracker = new BreakpointTracker() {
			@Override
			public int tryPromotePending(@Nullable JDIConnectionService jdiService) {
				workerInsideInvoke.countDown();
				try {
					if (!releaseWorker.await(WORKER_RELEASE_TIMEOUT_S, TimeUnit.SECONDS)) {
						throw new IllegalStateException("worker latch timed out");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return 0;
				}
				return 0;
			}
		};

		VirtualMachine vm = mock(VirtualMachine.class);
		JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			mock(JdiEventListener.class), blockingTracker, new EventHistory(),
			new one.edee.mcp.jdwp.watchers.WatcherManager(), new EvaluationGuard());
		JDIConnectionServiceTestSupport.setVm(service, vm);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "localhost", 5005);
		when(vm.name()).thenReturn("target-vm");

		Thread worker = new Thread(() -> {
			try {
				service.getVM();
			} catch (Exception ignored) {
				// We only care about the monitor-holding behaviour, not the result.
			}
		}, "outer-monitor-worker");
		worker.setDaemon(true);
		worker.start();

		assertThat(workerInsideInvoke.await(2, TimeUnit.SECONDS))
			.as("worker must reach tryPromotePending within 2s")
			.isTrue();

		Thread secondCaller = new Thread(() -> {
			try {
				service.getVM();
			} catch (Exception ignored) {
				// As above — the result is uninteresting; we are timing the monitor wait.
			}
		}, "outer-monitor-second-caller");
		secondCaller.setDaemon(true);
		secondCaller.start();

		// Both callers reach the test's tryPromotePending override and park on releaseWorker —
		// so both threads stay alive. The distinguishing signal is Thread.State: BLOCKED means the
		// second caller is still waiting on the JDIConnectionService monitor (the broken pre-fix
		// state), while TIMED_WAITING means it has progressed past the synchronized block and is
		// now parked on the latch inside tryPromotePending (the fixed behaviour).
		secondCaller.join(LISTENER_OBSERVATION_WINDOW_MS);
		Thread.State secondState = secondCaller.getState();
		boolean secondCallerBlockedOnMonitor = secondState == Thread.State.BLOCKED;

		// Always release so the test does not leak threads even when the assertion fails.
		releaseWorker.countDown();
		worker.join(WORKER_RELEASE_TIMEOUT_S * 1_000);
		secondCaller.join(WORKER_RELEASE_TIMEOUT_S * 1_000);

		assertThat(secondCallerBlockedOnMonitor)
			.as("a second getVM() caller must NOT block on the JDIConnectionService monitor while "
				+ "an unrelated worker is parked inside tryPromotePending — the monitor must be "
				+ "released across the promotion call. Observed state: %s.", secondState)
			.isFalse();
	}

	/**
	 * Drives the worker into a simulated slow per-entry lookup and observes whether the listener-
	 * side monitor acquire can make progress. Pre-fix the listener blocks on the tracker monitor
	 * held by the worker and stays alive past {@link #LISTENER_OBSERVATION_WINDOW_MS}; post-fix
	 * the listener completes immediately and the assertion below passes. Also asserts both
	 * threads terminate cleanly after the release latch.
	 */
	private static void runDeadlockProbe(BreakpointTracker tracker, JDIConnectionService service,
	                                     VirtualMachine vm, EventRequestManager erm,
	                                     ReferenceType refType) throws Exception {
		CountDownLatch workerInsideInvoke = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);

		// Simulate a slow per-entry lookup: signal that the worker is "inside the lookup", then
		// block until the listener side has had its chance to run. The contract under test is that
		// the call happens OUTSIDE the BreakpointTracker monitor, so the listener can acquire the
		// monitor while we wait here.
		when(service.findLoadedClass(eq("com.x.Foo")))
			.thenAnswer(inv -> {
				workerInsideInvoke.countDown();
				if (!releaseWorker.await(WORKER_RELEASE_TIMEOUT_S, TimeUnit.SECONDS)) {
					throw new IllegalStateException("worker latch timed out — test orchestration bug");
				}
				return refType;
			});

		Thread worker = new Thread(() -> tracker.tryPromotePending(service), "deadlock-probe-worker");
		worker.setDaemon(true);
		worker.start();

		assertThat(workerInsideInvoke.await(2, TimeUnit.SECONDS))
			.as("worker must reach the simulated JDI invoke within 2s")
			.isTrue();

		Thread listener = new Thread(
			() -> tracker.promotePendingFieldsForClass(refType, vm, erm, service),
			"deadlock-probe-listener");
		listener.setDaemon(true);
		listener.start();

		listener.join(LISTENER_OBSERVATION_WINDOW_MS);
		// Distinguish "blocked on the tracker monitor" (the pre-fix deadlock — Thread.State.BLOCKED)
		// from "still running but slow under a loaded CI host" (Thread.State.RUNNABLE / WAITING /
		// TIMED_WAITING — not a deadlock, must not fail the test). Same pattern as
		// getVm_secondCallerProceedsWhileFirstIsParkedInDeferredClassLoadPhase2 below.
		Thread.State listenerState = listener.getState();
		boolean listenerBlockedOnMonitor = listenerState == Thread.State.BLOCKED;

		// Always release the worker so the test does not leak threads regardless of the assertion outcome.
		releaseWorker.countDown();
		worker.join(WORKER_RELEASE_TIMEOUT_S * 1_000);
		listener.join(WORKER_RELEASE_TIMEOUT_S * 1_000);

		assertThat(listenerBlockedOnMonitor)
			.as("promotePendingFieldsForClass must not be BLOCKED on the BreakpointTracker monitor "
				+ "while a worker is mid-lookup inside findLoadedClass — listener state observed: %s",
				listenerState)
			.isFalse();
		assertThat(worker.isAlive())
			.as("worker thread must terminate after the JDI invoke simulation releases")
			.isFalse();
		assertThat(listener.isAlive())
			.as("listener thread must terminate cleanly")
			.isFalse();
	}
}

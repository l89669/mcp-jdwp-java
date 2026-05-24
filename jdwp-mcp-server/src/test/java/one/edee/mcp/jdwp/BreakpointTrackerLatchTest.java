package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the next-event latch in {@link BreakpointTracker}, which powers
 * {@code jdwp_resume_until_event}. The latch must be:
 * <ul>
 *   <li>Released by {@link BreakpointTracker#fireNextEvent()} after arming</li>
 *   <li>Cleared after firing so a subsequent arm starts fresh</li>
 *   <li>Replaceable — calling {@link BreakpointTracker#armNextEventLatch()} twice in a row
 *       discards the previous latch (the previous awaiter, if any, is left dangling on purpose;
 *       this contract matches the single-caller usage in {@code jdwp_resume_until_event})</li>
 *   <li>No-op when {@link BreakpointTracker#fireNextEvent()} is called with no latch armed</li>
 *   <li>Cleared by {@link BreakpointTracker#reset()}</li>
 * </ul>
 */
class BreakpointTrackerLatchTest {

	@Test
	@DisplayName("fireNextEvent releases an armed latch")
	void shouldReleaseArmedLatch() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch latch = tracker.armNextEventLatch();
		assertThat(latch.getCount()).isEqualTo(1);

		tracker.fireNextEvent();

		assertThat(latch.await(100, TimeUnit.MILLISECONDS)).isTrue();
		assertThat(latch.getCount()).isZero();
	}

	@Test
	@DisplayName("a fresh arm after a fire produces a new, separate latch")
	void shouldProduceFreshLatchAfterFire() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch first = tracker.armNextEventLatch();
		tracker.fireNextEvent();
		assertThat(first.await(100, TimeUnit.MILLISECONDS)).isTrue();

		CountDownLatch second = tracker.armNextEventLatch();

		assertThat(second).isNotSameAs(first);
		assertThat(second.getCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("the second fire after a fresh arm releases only the new latch")
	void shouldFireFreshLatchSeparately() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch first = tracker.armNextEventLatch();
		tracker.fireNextEvent();
		first.await(100, TimeUnit.MILLISECONDS);

		CountDownLatch second = tracker.armNextEventLatch();
		assertThat(second.getCount()).isEqualTo(1);

		tracker.fireNextEvent();

		assertThat(second.await(100, TimeUnit.MILLISECONDS)).isTrue();
	}

	@Test
	@DisplayName("re-arming before firing replaces the previous latch")
	void shouldReplacePreviouslyArmedLatch() {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch first = tracker.armNextEventLatch();
		CountDownLatch second = tracker.armNextEventLatch();

		assertThat(second).isNotSameAs(first);

		// firing should release the SECOND latch only — the first stays at 1
		tracker.fireNextEvent();
		assertThat(second.getCount()).isZero();
		assertThat(first.getCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("fireNextEvent is a no-op when no latch is armed")
	void shouldHandleFireWithNoLatch() {
		BreakpointTracker tracker = new BreakpointTracker();

		// Should not throw
		tracker.fireNextEvent();
		tracker.fireNextEvent();

		// Subsequent arm still works
		CountDownLatch latch = tracker.armNextEventLatch();
		assertThat(latch.getCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("await with timeout returns false if no event fires")
	void shouldTimeOutWhenNoEventFires() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch latch = tracker.armNextEventLatch();

		boolean fired = latch.await(50, TimeUnit.MILLISECONDS);

		assertThat(fired).isFalse();
		assertThat(latch.getCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("reset RELEASES the armed latch before clearing it (no waiter hangs on jdwp_reset)")
	void shouldReleaseLatchOnReset() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch latch = tracker.armNextEventLatch();

		tracker.reset();

		// The previously-armed latch must have been counted down so any waiter wakes up
		// immediately rather than blocking until its timeout.
		assertThat(latch.await(100, TimeUnit.MILLISECONDS)).isTrue();
		assertThat(latch.getCount()).isZero();
	}

	@Test
	@DisplayName("clearAll(erm) RELEASES the armed latch before clearing it (no waiter hangs on jdwp_reset)")
	void shouldReleaseLatchOnClearAll() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch latch = tracker.armNextEventLatch();

		// clearAll() takes an EventRequestManager and iterates breakpoint requests. With an
		// empty registry the loop bodies are no-ops, so passing null is safe here.
		tracker.clearAll(null);

		assertThat(latch.await(100, TimeUnit.MILLISECONDS)).isTrue();
		assertThat(latch.getCount()).isZero();
	}

	@Test
	@DisplayName("a thread waiting on the latch wakes when reset() is called from another thread")
	void shouldWakeWaiterOnReset() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch latch = tracker.armNextEventLatch();
		boolean[] firedFlag = { false };

		Thread waiter = new Thread(() -> {
			try {
				firedFlag[0] = latch.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}, "reset-waiter");
		waiter.start();

		Thread.sleep(20);
		tracker.reset();

		waiter.join(2_000);
		assertThat(firedFlag[0]).isTrue();
	}

	// ── pendingFire flag (P0-2/P0-3) ─────────────────────────────────────────

	@Test
	@DisplayName("consumePendingFire returns false when no event has fired")
	void shouldReturnFalseFromConsumeWhenNoEventFired() {
		BreakpointTracker tracker = new BreakpointTracker();
		assertThat(tracker.consumePendingFire()).isFalse();
	}

	@Test
	@DisplayName("fireNextEvent sets pendingFire; first consume returns true; second returns false")
	void shouldLatchPendingFireOnceUntilConsumed() {
		BreakpointTracker tracker = new BreakpointTracker();
		tracker.fireNextEvent();

		assertThat(tracker.consumePendingFire()).isTrue();
		assertThat(tracker.consumePendingFire())
			.as("consume must be one-shot — a second call returns false until the next fire")
			.isFalse();
	}

	@Test
	@DisplayName("multiple fires before consume coalesce into a single 'true' signal")
	void shouldCoalesceMultipleFiresIntoOneConsume() {
		BreakpointTracker tracker = new BreakpointTracker();
		tracker.fireNextEvent();
		tracker.fireNextEvent();
		tracker.fireNextEvent();

		// The flag is not a counter — a STEP followed by a BREAKPOINT on the same line should
		// still consume as a single "something happened" signal. The caller distinguishes the
		// specific event via getLastBreakpoint() / EventHistory.
		assertThat(tracker.consumePendingFire()).isTrue();
		assertThat(tracker.consumePendingFire()).isFalse();
	}

	@Test
	@DisplayName("pendingFire is cleared by reset() so a stale signal does not carry across sessions")
	void shouldClearPendingFireOnReset() {
		BreakpointTracker tracker = new BreakpointTracker();
		tracker.fireNextEvent();

		tracker.reset();

		assertThat(tracker.consumePendingFire())
			.as("reset() wipes session state — any unconsumed pendingFire must also be discarded")
			.isFalse();
	}

	@Test
	@DisplayName("pendingFire is cleared by clearAll(erm) for the same reason as reset()")
	void shouldClearPendingFireOnClearAll() {
		BreakpointTracker tracker = new BreakpointTracker();
		tracker.fireNextEvent();

		tracker.clearAll(null);

		assertThat(tracker.consumePendingFire()).isFalse();
	}

	@Test
	@DisplayName("fireNextEvent without an armed latch still latches pendingFire (the step-race fix)")
	void shouldSetPendingFireEvenWithoutArmedLatch() {
		BreakpointTracker tracker = new BreakpointTracker();
		// No armNextEventLatch() — simulates the STEP signal arriving before
		// jdwp_resume_until_event has armed its own latch.
		tracker.fireNextEvent();
		assertThat(tracker.consumePendingFire()).isTrue();
	}

	@Test
	@DisplayName("a worker thread awaiting the latch wakes up when fireNextEvent is called from another thread")
	void shouldWakeWaitingThreadAcrossThreads() throws InterruptedException {
		BreakpointTracker tracker = new BreakpointTracker();
		CountDownLatch latch = tracker.armNextEventLatch();
		boolean[] firedFlag = { false };

		Thread waiter = new Thread(() -> {
			try {
				firedFlag[0] = latch.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}, "latch-waiter");
		waiter.start();

		// Give the waiter a moment to actually start awaiting
		Thread.sleep(20);
		tracker.fireNextEvent();

		waiter.join(2_000);
		assertThat(firedFlag[0]).isTrue();
	}
}

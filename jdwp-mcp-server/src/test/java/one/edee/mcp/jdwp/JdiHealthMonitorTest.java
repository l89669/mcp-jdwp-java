package one.edee.mcp.jdwp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for {@link JdiHealthMonitor} — verifies the state-machine transitions
 * exposed through {@link JdiHealthMonitor#snapshot()} without actually wiring the monitor
 * to a live JDI {@code VirtualMachine}. The probe-on-silence and timeout-classification
 * paths are exercised by integration coverage; this class focuses on the bits the
 * {@code jdwp_diagnose} and soft-wait envelopes read.
 */
@DisplayName("JdiHealthMonitor")
class JdiHealthMonitorTest {

	private JdiHealthMonitor monitor;

	@BeforeEach
	void setUp() {
		monitor = new JdiHealthMonitor();
	}

	@AfterEach
	void tearDown() {
		monitor.stop();
	}

	@Test
	@DisplayName("fresh monitor reports DISCONNECTED with all timestamps null")
	void shouldReportDisconnectedBeforeStart() {
		final JdiHealthMonitor.JdiHealthSnapshot snapshot = monitor.snapshot();

		assertThat(snapshot.status()).isEqualTo(JdiHealthMonitor.Status.DISCONNECTED);
		assertThat(snapshot.lastTrafficAt()).isNull();
		assertThat(snapshot.silentFor()).isNull();
		assertThat(snapshot.lastProbeAt()).isNull();
		assertThat(snapshot.lastProbeOutcome()).isNull();
	}

	@Test
	@DisplayName("notifyTraffic advances lastTrafficAt and classifies as RESPONSIVE")
	void shouldFlipToResponsiveOnTraffic() throws Exception {
		// notifyTraffic now requires an active session (vmRef != null) before publishing. Plant
		// a mock vm so the publish path is exercised.
		plantActiveSession();

		monitor.notifyTraffic();

		final JdiHealthMonitor.JdiHealthSnapshot snapshot = monitor.snapshot();
		assertThat(snapshot.status()).isEqualTo(JdiHealthMonitor.Status.RESPONSIVE);
		assertThat(snapshot.lastTrafficAt()).isNotNull();
		assertThat(snapshot.silentFor()).isEqualTo(java.time.Duration.ZERO);
	}

	@Test
	@DisplayName("notifyTraffic preserves the last probe outcome — the diagnose renderer needs it")
	void shouldPreservePriorProbeOutcomeAcrossTrafficNotifications() throws Exception {
		// notifyTraffic now requires an active session before publishing.
		plantActiveSession();

		// Plant a synthetic UNRESPONSIVE snapshot by reflection so we don't need the real probe
		// thread to drive the state.
		final var snapshotField = JdiHealthMonitor.class.getDeclaredField("snapshotRef");
		snapshotField.setAccessible(true);
		@SuppressWarnings("unchecked")
		final java.util.concurrent.atomic.AtomicReference<JdiHealthMonitor.JdiHealthSnapshot> ref =
			(java.util.concurrent.atomic.AtomicReference<JdiHealthMonitor.JdiHealthSnapshot>) snapshotField.get(monitor);
		final java.time.Instant probeAt = java.time.Instant.now();
		ref.set(new JdiHealthMonitor.JdiHealthSnapshot(
			JdiHealthMonitor.Status.UNRESPONSIVE,
			null, java.time.Duration.ofSeconds(45),
			probeAt, "vm.allThreads() timed out after 5s"));

		monitor.notifyTraffic();

		final JdiHealthMonitor.JdiHealthSnapshot snapshot = monitor.snapshot();
		assertThat(snapshot.status()).isEqualTo(JdiHealthMonitor.Status.RESPONSIVE);
		assertThat(snapshot.lastProbeAt()).isEqualTo(probeAt);
		assertThat(snapshot.lastProbeOutcome()).isEqualTo("vm.allThreads() timed out after 5s");
	}

	@Test
	@DisplayName("stop() returns to DISCONNECTED even after observing traffic")
	void shouldRevertToDisconnectedOnStop() throws Exception {
		plantActiveSession();
		monitor.notifyTraffic();
		assertThat(monitor.snapshot().status()).isEqualTo(JdiHealthMonitor.Status.RESPONSIVE);

		monitor.stop();

		final JdiHealthMonitor.JdiHealthSnapshot snapshot = monitor.snapshot();
		assertThat(snapshot.status()).isEqualTo(JdiHealthMonitor.Status.DISCONNECTED);
		assertThat(snapshot.lastTrafficAt()).isNull();
		assertThat(snapshot.lastProbeAt()).isNull();
	}

	/**
	 * Race regression — degenerate case: a probe completes against a {@code vm} reference captured
	 * before {@code stop()} ran. An unguarded publish would revive the monitor by overwriting
	 * DISCONNECTED. The fix is {@code synchronized(this) + if (vmRef.get() == vm)} inside
	 * {@code runActiveProbe} so a concurrent {@code stop()} cannot interleave between the re-check
	 * and either snapshot write.
	 *
	 * <p>This test exercises the simplest case: {@code vmRef} is already null when
	 * {@code runActiveProbe} enters the synchronized block, so the guard short-circuits. The
	 * companion test {@link #shouldNotReviveSnapshotWhenStopRacesWithProbeCompletion()} drives the
	 * actual mid-probe race deterministically via a latch.
	 */
	@Test
	@DisplayName("runActiveProbe does not revive snapshot when vmRef is null at publish time")
	void shouldNotReviveSnapshotAfterStopRaceWithProbe() throws Exception {
		final com.sun.jdi.VirtualMachine vm = org.mockito.Mockito.mock(com.sun.jdi.VirtualMachine.class);
		org.mockito.Mockito.when(vm.allThreads()).thenReturn(java.util.List.of());

		// The monitor was constructed fresh in @BeforeEach, so vmRef is already null and the
		// snapshot is DISCONNECTED. Invoke runActiveProbe with a vm argument that does NOT match
		// vmRef (which is null) — the guard inside the method must skip the publish.
		final var probeMethod = JdiHealthMonitor.class.getDeclaredMethod(
			"runActiveProbe", com.sun.jdi.VirtualMachine.class, java.time.Instant.class, java.time.Duration.class);
		probeMethod.setAccessible(true);
		probeMethod.invoke(monitor,
			vm, java.time.Instant.now().minusSeconds(60), java.time.Duration.ofSeconds(60));

		final JdiHealthMonitor.JdiHealthSnapshot snapshot = monitor.snapshot();
		// The probe succeeded against the captured vm reference, but the guard suppressed the
		// snapshot publish because vmRef did not match. The DISCONNECTED state survives.
		assertThat(snapshot.status()).isEqualTo(JdiHealthMonitor.Status.DISCONNECTED);
	}

	/**
	 * Race regression — tight case: a probe is parked inside {@code vm.allThreads()} (mocked to
	 * block on a latch) while {@code stop()} runs on the main thread. When the probe is released
	 * after stop() has completed, its post-probe publish must observe the now-DISCONNECTED state
	 * inside the synchronized block and skip its write. The latch fully serialises probe-completion
	 * against stop() so the race is deterministic.
	 */
	@Test
	@DisplayName("runActiveProbe does not revive snapshot when stop() races with probe completion")
	void shouldNotReviveSnapshotWhenStopRacesWithProbeCompletion() throws Exception {
		final java.util.concurrent.CountDownLatch probeBlocked = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.CountDownLatch releaseProbe = new java.util.concurrent.CountDownLatch(1);
		final com.sun.jdi.VirtualMachine vm = org.mockito.Mockito.mock(com.sun.jdi.VirtualMachine.class);
		org.mockito.Mockito.when(vm.allThreads()).thenAnswer(inv -> {
			probeBlocked.countDown();
			releaseProbe.await();
			return java.util.List.of();
		});

		// Plant the vmRef so the probe enters with a "live" session. The probe will block in
		// vm.allThreads() until we release the latch.
		plantVmRef(vm);
		monitor.notifyTraffic();  // seed RESPONSIVE so we can detect a revival

		final var probeMethod = JdiHealthMonitor.class.getDeclaredMethod(
			"runActiveProbe", com.sun.jdi.VirtualMachine.class, java.time.Instant.class, java.time.Duration.class);
		probeMethod.setAccessible(true);

		final Thread probeThread = new Thread(() -> {
			try {
				probeMethod.invoke(monitor,
					vm, java.time.Instant.now().minusSeconds(60), java.time.Duration.ofSeconds(60));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, "race-test-probe");
		probeThread.setDaemon(true);
		probeThread.start();

		// Wait for the probe to be parked inside vm.allThreads(). Now stop() the monitor: it
		// acquires the lock, sets vmRef=null and snapshot=DISCONNECTED, releases the lock.
		assertThat(probeBlocked.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		monitor.stop();
		assertThat(monitor.snapshot().status()).isEqualTo(JdiHealthMonitor.Status.DISCONNECTED);

		// Release the probe — it will exit vm.allThreads(), enter the synchronized publish block,
		// see vmRef is now null (not equal to its captured vm), and skip the write.
		releaseProbe.countDown();
		probeThread.join(2_000);
		assertThat(probeThread.isAlive()).as("probe thread should have completed").isFalse();

		assertThat(monitor.snapshot().status())
			.as("DISCONNECTED must survive — probe completion cannot revive a stopped monitor")
			.isEqualTo(JdiHealthMonitor.Status.DISCONNECTED);
	}

	/**
	 * Same revival surface as the probe-completion test but for {@link JdiHealthMonitor#notifyTraffic()}:
	 * a JDI event drained from the queue right before disconnect could call notifyTraffic after
	 * stop() ran. The synchronized + null-vmRef guard inside notifyTraffic must skip the publish.
	 */
	@Test
	@DisplayName("notifyTraffic does not revive snapshot after stop() has run")
	void shouldNotReviveSnapshotWhenNotifyTrafficArrivesAfterStop() {
		// vmRef starts null (no session), so a notifyTraffic from any caller (a late event-listener
		// drain, an attach handshake after stop, etc.) must NOT publish RESPONSIVE.
		monitor.notifyTraffic();

		assertThat(monitor.snapshot().status()).isEqualTo(JdiHealthMonitor.Status.DISCONNECTED);
		assertThat(monitor.snapshot().lastTrafficAt())
			.as("lastTrafficAt must stay null when no session is active")
			.isNull();
	}

	/** Plants a mock VM into the monitor's {@code vmRef} so publish guards see an active session. */
	private void plantActiveSession() throws Exception {
		plantVmRef(org.mockito.Mockito.mock(com.sun.jdi.VirtualMachine.class));
	}

	@SuppressWarnings("unchecked")
	private void plantVmRef(com.sun.jdi.VirtualMachine vm) throws Exception {
		final var vmRefField = JdiHealthMonitor.class.getDeclaredField("vmRef");
		vmRefField.setAccessible(true);
		((java.util.concurrent.atomic.AtomicReference<com.sun.jdi.VirtualMachine>) vmRefField.get(monitor)).set(vm);
	}
}

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
	void shouldFlipToResponsiveOnTraffic() {
		monitor.notifyTraffic();

		final JdiHealthMonitor.JdiHealthSnapshot snapshot = monitor.snapshot();
		assertThat(snapshot.status()).isEqualTo(JdiHealthMonitor.Status.RESPONSIVE);
		assertThat(snapshot.lastTrafficAt()).isNotNull();
		assertThat(snapshot.silentFor()).isEqualTo(java.time.Duration.ZERO);
	}

	@Test
	@DisplayName("notifyTraffic preserves the last probe outcome — the diagnose renderer needs it")
	void shouldPreservePriorProbeOutcomeAcrossTrafficNotifications() throws Exception {
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
	void shouldRevertToDisconnectedOnStop() {
		monitor.notifyTraffic();
		assertThat(monitor.snapshot().status()).isEqualTo(JdiHealthMonitor.Status.RESPONSIVE);

		monitor.stop();

		final JdiHealthMonitor.JdiHealthSnapshot snapshot = monitor.snapshot();
		assertThat(snapshot.status()).isEqualTo(JdiHealthMonitor.Status.DISCONNECTED);
		assertThat(snapshot.lastTrafficAt()).isNull();
		assertThat(snapshot.lastProbeAt()).isNull();
	}
}

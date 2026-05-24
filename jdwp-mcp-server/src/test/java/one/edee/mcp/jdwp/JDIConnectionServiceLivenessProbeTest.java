package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the authoritative connect-time liveness probe ({@code isVMResponsive()}) reached via
 * {@link JDIConnectionService#getConnectionStatus()} and the {@link JDIConnectionService#connect}
 * "already connected" guard.
 *
 * <p>Regression context: the probe used to call {@code vm.name()}, which JDI caches after its first
 * fetch — so a VM whose socket had already closed (e.g. an orphaned test JVM left running by a
 * previous debug session) still read as alive. {@code connect()} then short-circuited with
 * "Already connected" and the caller debugged a dead/aliased VM, and {@code jdwp_diagnose} reported
 * a live connection that wasn't. The probe now round-trips through {@code vm.allThreads()}, so a
 * closed socket throws promptly and a wedged VM is bounded by a timeout.
 */
@DisplayName("JDIConnectionService liveness probe (isVMResponsive)")
class JDIConnectionServiceLivenessProbeTest {

	@Test
	@DisplayName("a responsive VM (allThreads returns) reports connected")
	void shouldReportConnectedWhenVmAnswersRoundTrip() {
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithMocks();
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(vm.allThreads()).thenReturn(List.of(mock(ThreadReference.class)));
		JDIConnectionServiceTestSupport.setVm(service, vm);

		assertThat(service.getConnectionStatus().connected()).isTrue();
	}

	@Test
	@DisplayName("a dead VM (allThreads throws) reports NOT connected — the cached-name() blind spot")
	void shouldReportNotConnectedWhenRoundTripThrows() {
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithMocks();
		final VirtualMachine vm = mock(VirtualMachine.class);
		// name() is cached and would still return a value on a dead handle; the round-tripping
		// allThreads() is what actually detects the closed socket.
		when(vm.name()).thenReturn("orphan-vm");
		when(vm.allThreads()).thenThrow(new VMDisconnectedException());
		JDIConnectionServiceTestSupport.setVm(service, vm);

		assertThat(service.getConnectionStatus().connected()).isFalse();
	}

	@Test
	@DisplayName("a wedged VM (allThreads blocks) reports NOT connected within a bounded time")
	void shouldReportNotConnectedAndNotHangWhenRoundTripWedges() throws Exception {
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithMocks();
		final VirtualMachine vm = mock(VirtualMachine.class);
		final CountDownLatch release = new CountDownLatch(1);
		// Simulate a wedged socket: allThreads() never returns until the test tears down. The probe
		// must time out and classify the VM as not connected rather than blocking the caller.
		when(vm.allThreads()).thenAnswer(inv -> {
			release.await();
			return List.of();
		});
		JDIConnectionServiceTestSupport.setVm(service, vm);

		try {
			final long startedAt = System.currentTimeMillis();
			final boolean connected = service.getConnectionStatus().connected();
			final long elapsed = System.currentTimeMillis() - startedAt;

			assertThat(connected).isFalse();
			// The bounded probe (2s timeout) must return well before an unbounded wait would; this
			// guards against a regression that drops the timeout and lets connect()/diagnose hang.
			assertThat(elapsed).isLessThan(5_000L);
		} finally {
			release.countDown();
		}
	}

	@Test
	@DisplayName("connect() to the same target does NOT alias a dead VM — it re-attaches instead of 'Already connected'")
	void shouldNotReportAlreadyConnectedWhenHeldVmIsDead() {
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithRealListener();
		final VirtualMachine deadVm = mock(VirtualMachine.class);
		when(deadVm.name()).thenReturn("orphan-vm");
		when(deadVm.allThreads()).thenThrow(new VMDisconnectedException());
		JDIConnectionServiceTestSupport.setVm(service, deadVm);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "same-host", 5005);

		// The held VM is dead, so the guard must fall through to a fresh attach rather than
		// short-circuiting with "Already connected". The fresh attach then fails (no live JDWP
		// server in the unit test), which is exactly the proof that we did NOT alias the dead VM.
		assertThatThrownBy(() -> service.connect("same-host", 5005))
			.isInstanceOf(Exception.class);
	}
}

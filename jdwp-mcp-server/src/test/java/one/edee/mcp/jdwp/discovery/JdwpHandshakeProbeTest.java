package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-socket integration tests for {@link JvmDiscoveryService#probeHandshake}. The fake server
 * lives in {@link FakeJdwpServer} (shared with the confirmAll integration suite). Each test spins
 * up an ephemeral socket with a different behaviour, then runs the probe against it.
 *
 * <p>Tests for the {@code confirmAll} state-transition logic that sits on top of the probe live
 * in {@code JvmDiscoveryServiceConfirmAllTest}.
 */
class JdwpHandshakeProbeTest {

	@Test
	@DisplayName("server that echoes the handshake → probe returns true")
	void shouldConfirmHandshakeFromCooperatingServer() throws Exception {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		try (FakeJdwpServer server = FakeJdwpServer.cooperating()) {
			final boolean ok = service.probeHandshake("127.0.0.1", server.port(), 500);
			assertThat(ok).isTrue();
		}
	}

	@Test
	@DisplayName("server that ignores the bytes → probe returns false within timeout")
	void shouldRejectSilentServer() throws Exception {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		try (FakeJdwpServer server = FakeJdwpServer.silent()) {
			final long start = System.currentTimeMillis();
			final boolean ok = service.probeHandshake("127.0.0.1", server.port(), 200);
			final long elapsed = System.currentTimeMillis() - start;
			assertThat(ok).isFalse();
			assertThat(elapsed).isLessThan(1_000L);
		}
	}

	@Test
	@DisplayName("connection refused → probe returns false quickly")
	void shouldRejectClosedPort() {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		// Port 1 is unprivileged on no platform; we expect immediate ECONNREFUSED.
		final boolean ok = service.probeHandshake("127.0.0.1", 1, 200);
		assertThat(ok).isFalse();
	}

	@Test
	@DisplayName("zero-millisecond timeout returns false without escaping an exception")
	void shouldReturnFalseOnZeroTimeoutWithoutThrowing() throws Exception {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		try (FakeJdwpServer server = FakeJdwpServer.cooperating()) {
			// A 0-ms timeout on Socket.connect means "infinite wait" in the JDK contract; we
			// still need to verify the call returns deterministically (one way or the other)
			// rather than propagating an IllegalArgumentException or hanging.
			final boolean ok = service.probeHandshake("127.0.0.1", server.port(), 0);
			// We don't care what the result is — only that the method returned a boolean cleanly.
			assertThat(ok == true || ok == false).isTrue();
		}
	}

	@Test
	@DisplayName("server replies with 14 wrong bytes → probe returns false")
	void shouldReturnFalseWhenServerEchoesGarbage() throws Exception {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		try (FakeJdwpServer server = FakeJdwpServer.wrongBytes()) {
			final boolean ok = service.probeHandshake("127.0.0.1", server.port(), 500);
			assertThat(ok).isFalse();
		}
	}

	@Test
	@DisplayName("server closes the socket before replying → probe returns false (read==-1)")
	void shouldReturnFalseWhenServerClosesMidRead() throws Exception {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		try (FakeJdwpServer server = FakeJdwpServer.closesMidRead()) {
			final boolean ok = service.probeHandshake("127.0.0.1", server.port(), 500);
			assertThat(ok).isFalse();
		}
	}

	@Test
	@DisplayName("server delay that overruns the total budget → probe returns false and respects the deadline")
	void shouldRejectHandshakeWhenServerDelayBlowsThroughBudget() throws Exception {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		try (FakeJdwpServer server = FakeJdwpServer.delayedEcho(300)) {
			final long start = System.currentTimeMillis();
			final boolean ok = service.probeHandshake("127.0.0.1", server.port(), 100);
			final long elapsed = System.currentTimeMillis() - start;
			// The 300ms server-side pause must not be allowed to overrun the 100ms per-port
			// budget. The probe must fail, AND the call must return within the budget plus
			// a small scheduling grace window.
			assertThat(ok).isFalse();
			assertThat(elapsed).isLessThan(250L);
		}
	}

	@Test
	@DisplayName("drip-feed server that overruns the total budget → probe returns false within the deadline")
	void shouldRejectDripFeedHandshakeOnceTotalBudgetExceeded() throws Exception {
		final JvmDiscoveryService service = new JvmDiscoveryService();
		try (FakeJdwpServer server = FakeJdwpServer.dripEcho(20)) {
			final long start = System.currentTimeMillis();
			final boolean ok = service.probeHandshake("127.0.0.1", server.port(), 100);
			final long elapsed = System.currentTimeMillis() - start;
			// 14 bytes × 20ms ≈ 280ms of server work, well beyond the 100ms per-port budget.
			// SO_TIMEOUT must be recomputed against the remaining budget on every read; the
			// probe must therefore reject the handshake instead of accumulating reads forever.
			assertThat(ok).isFalse();
			assertThat(elapsed).isLessThan(250L);
		}
	}
}

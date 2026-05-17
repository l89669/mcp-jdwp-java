package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JvmDiscoveryService#confirmAll}. Uses {@link FakeJdwpServer} to
 * stand in for real JDWP-speaking JVMs; verifies the four canonical state transitions and the
 * short-circuits that decline to probe (off-host descriptors, descriptors with no endpoint, the
 * "already attached" shortcut).
 */
@DisplayName("JvmDiscoveryService.confirmAll")
class JvmDiscoveryServiceConfirmAllTest {

	@Nested
	@DisplayName("state transitions")
	class StateTransitions {

		@Test
		@DisplayName("cooperating port → state moves UNKNOWN → LISTENING")
		void shouldMoveToListeningOnSuccessfulHandshake() throws Exception {
			try (FakeJdwpServer server = FakeJdwpServer.cooperating()) {
				final JvmDiscoveryService service = new JvmDiscoveryService();
				final JvmDescriptor d = descriptor(1001L, "127.0.0.1", server.port(), false);

				final List<JvmDescriptor> result = service.confirmAll(List.of(d), null, 0);

				assertThat(result).hasSize(1);
				assertThat(result.get(0).jdwp()).isNotNull();
				assert result.get(0).jdwp() != null;
				assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.LISTENING);
			}
		}

		@Test
		@DisplayName("cooperating port + suspend=y → state becomes SUSPENDED")
		void shouldMoveToSuspendedWhenSuspendY() throws Exception {
			try (FakeJdwpServer server = FakeJdwpServer.cooperating()) {
				final JvmDiscoveryService service = new JvmDiscoveryService();
				final JvmDescriptor d = descriptor(2002L, "127.0.0.1", server.port(), true);

				final List<JvmDescriptor> result = service.confirmAll(List.of(d), null, 0);

				assert result.get(0).jdwp() != null;
				assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.SUSPENDED);
			}
		}

		@Test
		@DisplayName("matching host:port → state becomes CONNECTED_TO_US (no probe)")
		void shouldShortCircuitWhenAlreadyConnected() {
			// Important: no real server here. CONNECTED_TO_US must short-circuit before any probe,
			// otherwise we would attempt a second simultaneous attach and race against ourselves.
			final JvmDiscoveryService service = new JvmDiscoveryService();
			final JvmDescriptor d = descriptor(3003L, "*", 5005, false);

			final List<JvmDescriptor> result = service.confirmAll(List.of(d), "localhost", 5005);

			assert result.get(0).jdwp() != null;
			assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.CONNECTED_TO_US);
		}

		@Test
		@DisplayName("closed port → state becomes UNREACHABLE")
		void shouldMoveToUnreachableOnRefused() {
			final JvmDiscoveryService service = new JvmDiscoveryService();
			final JvmDescriptor d = descriptor(4004L, "127.0.0.1", 1, false);

			final List<JvmDescriptor> result = service.confirmAll(List.of(d), null, 0);

			assert result.get(0).jdwp() != null;
			assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.UNREACHABLE);
		}
	}

	@Nested
	@DisplayName("pass-through cases")
	class PassThrough {

		@Test
		@DisplayName("descriptor with null endpoint passes through unchanged")
		void shouldPassThroughDescriptorWithoutEndpoint() {
			final JvmDiscoveryService service = new JvmDiscoveryService();
			final JvmDescriptor d = new JvmDescriptor(
				5005L, "com.example.Main", null, null,
				null, false, JvmDescriptor.Source.ATTACH_API
			);

			final List<JvmDescriptor> result = service.confirmAll(List.of(d), null, 0);

			assertThat(result).hasSize(1);
			assertThat(result.get(0)).isSameAs(d);
		}

		@Test
		@DisplayName("off-host descriptor (10.0.0.5) is not probed; state stays UNKNOWN")
		void shouldNotProbeOffHostDescriptor() {
			final JvmDiscoveryService service = new JvmDiscoveryService();
			// 10.0.0.5 is a routable address but we won't actually dial it — the off-host
			// short-circuit must catch it before any socket is opened.
			final JvmDescriptor d = descriptor(6006L, "10.0.0.5", 5005, false);

			final List<JvmDescriptor> result = service.confirmAll(List.of(d), null, 0);

			assert result.get(0).jdwp() != null;
			assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.UNKNOWN);
		}

		@Test
		@DisplayName("empty descriptor list returns the same empty list without spinning up the pool")
		void shouldReturnEmptyListWithoutExecutor() {
			final JvmDiscoveryService service = new JvmDiscoveryService();

			final List<JvmDescriptor> empty = List.of();
			final List<JvmDescriptor> result = service.confirmAll(empty, null, 0);

			assertThat(result).isSameAs(empty);
		}
	}

	@Nested
	@DisplayName("host mapping")
	class HostMapping {

		@Test
		@DisplayName("'0.0.0.0' bind address is probed via 127.0.0.1")
		void shouldDialZeroZeroZeroZeroAsLoopback() throws Exception {
			try (FakeJdwpServer server = FakeJdwpServer.cooperating()) {
				final JvmDiscoveryService service = new JvmDiscoveryService();
				// "0.0.0.0" means "bind on all interfaces" — we must dial 127.0.0.1 since
				// 0.0.0.0 itself is not a valid destination address on most stacks.
				final JvmDescriptor d = descriptor(7007L, "0.0.0.0", server.port(), false);

				final List<JvmDescriptor> result = service.confirmAll(List.of(d), null, 0);

				assert result.get(0).jdwp() != null;
				assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.LISTENING);
			}
		}

		@Test
		@DisplayName("wildcard connectedHost matches descriptor with concrete host on same port")
		void shouldMatchConnectedTargetViaWildcardHost() {
			// connectedHost="*" — a value the user might have passed to jdwp_connect — should
			// still match a descriptor advertised on localhost:5005, otherwise confirmAll would
			// race against the live attach.
			final JvmDiscoveryService service = new JvmDiscoveryService();
			final JvmDescriptor d = descriptor(8008L, "localhost", 5005, false);

			final List<JvmDescriptor> result = service.confirmAll(List.of(d), "*", 5005);

			assert result.get(0).jdwp() != null;
			assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.CONNECTED_TO_US);
		}

		@Test
		@DisplayName("IPv6 loopback '::1' is treated as local and probed (or marked UNREACHABLE)")
		void shouldProbeIpv6Loopback() {
			// '::1' is a recognised local host in JvmDiscoveryService.isLocalHost(), so confirmAll
			// will attempt to probe it. The probe may succeed (if IPv6 loopback is bound) or
			// fail (if the test host disabled IPv6); either way it must not stay at UNKNOWN.
			final JvmDiscoveryService service = new JvmDiscoveryService();
			final JvmDescriptor d = descriptor(9009L, "::1", 1, false);

			final List<JvmDescriptor> result = service.confirmAll(List.of(d), null, 0);

			assert result.get(0).jdwp() != null;
			final JdwpEndpoint.State state = result.get(0).jdwp().state();
			// Port 1 is closed on every reasonable host; we expect UNREACHABLE, not UNKNOWN.
			assertThat(state).isEqualTo(JdwpEndpoint.State.UNREACHABLE);
		}
	}

	@Nested
	@DisplayName("batches")
	class Batches {

		@Test
		@DisplayName("mixed batch (cooperating + closed + off-host) yields LISTENING, UNREACHABLE, UNKNOWN")
		void shouldHandleMixedBatchInOrder() throws Exception {
			try (FakeJdwpServer cooperating = FakeJdwpServer.cooperating()) {
				final JvmDiscoveryService service = new JvmDiscoveryService();
				final JvmDescriptor good = descriptor(1L, "127.0.0.1", cooperating.port(), false);
				final JvmDescriptor closed = descriptor(2L, "127.0.0.1", 1, false);
				final JvmDescriptor offHost = descriptor(3L, "10.0.0.5", 5005, false);

				final List<JvmDescriptor> result = service.confirmAll(
					List.of(good, closed, offHost), null, 0
				);

				assertThat(result).hasSize(3);
				assert result.get(0).jdwp() != null;
				assert result.get(1).jdwp() != null;
				assert result.get(2).jdwp() != null;
				assertThat(result.get(0).jdwp().state()).isEqualTo(JdwpEndpoint.State.LISTENING);
				assertThat(result.get(1).jdwp().state()).isEqualTo(JdwpEndpoint.State.UNREACHABLE);
				assertThat(result.get(2).jdwp().state()).isEqualTo(JdwpEndpoint.State.UNKNOWN);
				// Input PID order preserved on output.
				assertThat(result.get(0).pid()).isEqualTo(1L);
				assertThat(result.get(1).pid()).isEqualTo(2L);
				assertThat(result.get(2).pid()).isEqualTo(3L);
			}
		}
	}

	private static JvmDescriptor descriptor(long pid, String host, int port, boolean suspendOnStart) {
		return new JvmDescriptor(
			pid, "com.example.Main", null, null,
			new JdwpEndpoint(host, port, "dt_socket", true, suspendOnStart, JdwpEndpoint.State.UNKNOWN),
			false, JvmDescriptor.Source.PROC_FS
		);
	}
}

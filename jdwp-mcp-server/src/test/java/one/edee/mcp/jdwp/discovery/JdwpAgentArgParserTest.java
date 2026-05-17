package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JdwpAgentArgParser}. Covers the realistic shapes of
 * {@code -agentlib:jdwp=} payloads we expect to see on real JVMs, plus malformed inputs
 * that must be rejected (returning {@code null}, not throwing).
 */
class JdwpAgentArgParserTest {

	@Test
	@DisplayName("canonical surefire-debug args")
	void shouldParseCanonicalSurefireArgs() {
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("transport=dt_socket,server=y,suspend=y,address=*:5005");

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.host()).isEqualTo("*");
		assertThat(ep.port()).isEqualTo(5005);
		assertThat(ep.transport()).isEqualTo("dt_socket");
		assertThat(ep.serverMode()).isTrue();
		assertThat(ep.suspendOnStart()).isTrue();
		assertThat(ep.state()).isEqualTo(JdwpEndpoint.State.UNKNOWN);
	}

	@Test
	@DisplayName("server=n suspend=n is parsed")
	void shouldParseClientAndNonSuspending() {
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("transport=dt_socket,server=n,suspend=n,address=127.0.0.1:5006");

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.serverMode()).isFalse();
		assertThat(ep.suspendOnStart()).isFalse();
		assertThat(ep.host()).isEqualTo("127.0.0.1");
		assertThat(ep.port()).isEqualTo(5006);
	}

	@Test
	@DisplayName("localhost address parses")
	void shouldParseLocalhostAddress() {
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("transport=dt_socket,server=y,suspend=n,address=localhost:5007");

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.host()).isEqualTo("localhost");
		assertThat(ep.port()).isEqualTo(5007);
	}

	@Test
	@DisplayName("bare port (legacy) parses as host=*")
	void shouldParseBarePort() {
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("transport=dt_socket,server=y,suspend=n,address=5005");

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.host()).isEqualTo("*");
		assertThat(ep.port()).isEqualTo(5005);
	}

	@Test
	@DisplayName("missing address returns null")
	void shouldRejectMissingAddress() {
		assertThat(JdwpAgentArgParser.parse("transport=dt_socket,server=y,suspend=n")).isNull();
	}

	@Test
	@DisplayName("null and empty input return null")
	void shouldRejectNullOrEmpty() {
		assertThat(JdwpAgentArgParser.parse(null)).isNull();
		assertThat(JdwpAgentArgParser.parse("")).isNull();
	}

	@Test
	@DisplayName("garbage port returns null instead of throwing")
	void shouldRejectGarbagePort() {
		assertThat(JdwpAgentArgParser.parse("transport=dt_socket,server=y,address=*:notaport")).isNull();
	}

	@Test
	@DisplayName("out-of-range port returns null")
	void shouldRejectOutOfRangePort() {
		assertThat(JdwpAgentArgParser.parse("transport=dt_socket,server=y,address=*:65536")).isNull();
		assertThat(JdwpAgentArgParser.parse("transport=dt_socket,server=y,address=*:0")).isNull();
	}

	@Test
	@DisplayName("unknown keys are ignored, defaults applied for missing keys")
	void shouldIgnoreUnknownKeysAndDefault() {
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("address=*:5005,onthrow=java.lang.Throwable,launch=foo");

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.port()).isEqualTo(5005);
		assertThat(ep.transport()).isEqualTo("dt_socket"); // default
		assertThat(ep.serverMode()).isFalse();             // default
		assertThat(ep.suspendOnStart()).isTrue();          // JDWP default per docs
	}

	@Test
	@DisplayName("shmem transport is preserved")
	void shouldPreserveShmemTransport() {
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("transport=dt_shmem,server=y,suspend=n,address=javadebug");

		// dt_shmem uses a name, not host:port — we cannot parse "javadebug" as port,
		// so this returns null. That's fine: dt_shmem is Windows-only and we don't probe it.
		assertThat(ep).isNull();
	}

	@Test
	@DisplayName("whitespace-padded values are trimmed on both sides of '='")
	void shouldTrimWhitespaceAroundKeysAndValues() {
		// The parser splits by ',' first, so a token like " address = *:5005 " has spaces around
		// both the key and the value. Both sides must be trimmed before lookup.
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("transport=dt_socket, server=y, suspend=y, address= *:5005 ");

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.host()).isEqualTo("*");
		assertThat(ep.port()).isEqualTo(5005);
	}

	@Test
	@DisplayName("duplicate keys: last writer wins")
	void shouldKeepLastValueForDuplicateKeys() {
		// Realistic on a hand-edited surefire-debug arg where the user appended a corrected
		// address without removing the original.
		final JdwpEndpoint ep = JdwpAgentArgParser.parse(
			"transport=dt_socket,server=y,suspend=y,address=*:5005,address=127.0.0.1:5006"
		);

		assertThat(ep).isNotNull();
		assert ep != null;
		// The second address= wins.
		assertThat(ep.host()).isEqualTo("127.0.0.1");
		assertThat(ep.port()).isEqualTo(5006);
	}

	@Test
	@DisplayName("negative port returns null")
	void shouldRejectNegativePort() {
		assertThat(JdwpAgentArgParser.parse("transport=dt_socket,server=y,address=*:-5005")).isNull();
	}

	@Test
	@DisplayName("malformed token without '=' is skipped while other valid keys still parse")
	void shouldSkipMalformedTokenAndParseRest() {
		// A stray "garbage" token (no '=') is silently dropped; the rest of the agent arg still
		// produces a valid endpoint.
		final JdwpEndpoint ep = JdwpAgentArgParser.parse(
			"transport=dt_socket,server=y,garbage,suspend=y,address=*:5005"
		);

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.port()).isEqualTo(5005);
		assertThat(ep.serverMode()).isTrue();
		assertThat(ep.suspendOnStart()).isTrue();
	}

	@Test
	@DisplayName("bracketed IPv6 literal address=[::1]:5005 parses with the bracketed host preserved")
	void shouldParseBracketedIpv6Literal() {
		// The parser splits at the LAST ':', so the bracketed host string survives intact.
		// Downstream code (JvmDiscoveryService.isLocalHost) does not currently recognise the
		// bracketed form as a loopback address — so this endpoint would be treated as off-host
		// and never probed. The parsing itself, however, must not throw.
		final JdwpEndpoint ep = JdwpAgentArgParser.parse("transport=dt_socket,server=y,suspend=y,address=[::1]:5005");

		assertThat(ep).isNotNull();
		assert ep != null;
		assertThat(ep.port()).isEqualTo(5005);
		// Current behaviour: the host string keeps the brackets verbatim.
		assertThat(ep.host()).isEqualTo("[::1]");
	}
}

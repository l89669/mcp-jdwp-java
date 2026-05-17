package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-formatter tests for {@link DiagnoseReportRenderer}. Every method on the renderer is
 * stateless; we exercise each branch (header, MCP-server block, connection block with all four
 * connected/disconnected combinations, JVM list with empty and populated paths, attach hints
 * with their precedence rules, and the private formatters reached via the JVM-list path).
 */
@DisplayName("DiagnoseReportRenderer")
class DiagnoseReportRendererTest {

	@Nested
	@DisplayName("renderHeader")
	class Header {

		@Test
		@DisplayName("emits the banner with the report title and a trailing blank line")
		void shouldEmitBanner() {
			final String header = DiagnoseReportRenderer.renderHeader();

			assertThat(header).contains("MCP JDWP Inspector — Diagnostic Report");
			assertThat(header).contains("═══");
			// A blank line after the banner separates it from the first block.
			assertThat(header).endsWith("\n\n");
		}
	}

	@Nested
	@DisplayName("renderMcpServerBlock")
	class McpServer {

		@Test
		@DisplayName("renders all server fields with seconds-only uptime")
		void shouldRenderAllFieldsAndSecondsUptime() {
			final String block = DiagnoseReportRenderer.renderMcpServerBlock(
				1234L, Duration.ofSeconds(45), "OpenJDK 21.0.2", 30,
				"localhost", 5005, "/home/jno/work"
			);

			assertThat(block).contains("▸ MCP server");
			assertThat(block).contains("PID 1234");
			assertThat(block).contains("uptime 45s");
			assertThat(block).contains("Java:          OpenJDK 21.0.2");
			assertThat(block).contains("Tools:         30 registered");
			assertThat(block).contains("target host=localhost port=5005");
			assertThat(block).contains("Working dir:   /home/jno/work");
		}

		@Test
		@DisplayName("uptime under one hour is rendered as 'Xm Ys'")
		void shouldFormatMinutesAndSeconds() {
			final String block = DiagnoseReportRenderer.renderMcpServerBlock(
				1L, Duration.ofSeconds(187), "Java 21", 1, "h", 1, "/"
			);

			assertThat(block).contains("uptime 3m 7s");
		}

		@Test
		@DisplayName("uptime over one hour is rendered as 'Xh Ym' (seconds dropped)")
		void shouldFormatHoursAndMinutes() {
			final String block = DiagnoseReportRenderer.renderMcpServerBlock(
				1L, Duration.ofSeconds(2 * 3600 + 15 * 60 + 42), "Java 21", 1, "h", 1, "/"
			);

			assertThat(block).contains("uptime 2h 15m");
			assertThat(block).doesNotContain("42s");
		}

		@Test
		@DisplayName("negative uptime is clamped to '0s'")
		void shouldClampNegativeUptime() {
			final String block = DiagnoseReportRenderer.renderMcpServerBlock(
				1L, Duration.ofSeconds(-30), "Java", 1, "h", 1, "/"
			);

			assertThat(block).contains("uptime 0s");
		}
	}

	@Nested
	@DisplayName("renderConnectionBlock")
	class Connection {

		@Test
		@DisplayName("connected: emits host:port with a checkmark")
		void shouldRenderConnectedWithHost() {
			final DiagnoseReportRenderer.JDIConnectionStatusView status =
				new DiagnoseReportRenderer.JDIConnectionStatusView(true, "10.0.0.5", 5006, null, null);

			final String block = DiagnoseReportRenderer.renderConnectionBlock(status, 5005);

			assertThat(block).contains("✓ Connected to 10.0.0.5:5006");
			assertThat(block).doesNotContain("Not connected");
			assertThat(block).doesNotContain("Suggestion:");
		}

		@Test
		@DisplayName("connected with null lastHost: falls back to '<unknown>'")
		void shouldRenderConnectedFallbackHost() {
			final DiagnoseReportRenderer.JDIConnectionStatusView status =
				new DiagnoseReportRenderer.JDIConnectionStatusView(true, null, 5005, null, null);

			final String block = DiagnoseReportRenderer.renderConnectionBlock(status, 5005);

			assertThat(block).contains("✓ Connected to <unknown>:5005");
		}

		@Test
		@DisplayName("disconnected with prior failed attempt: shows timestamp and quoted error")
		void shouldRenderDisconnectedWithError() {
			final DiagnoseReportRenderer.JDIConnectionStatusView status =
				new DiagnoseReportRenderer.JDIConnectionStatusView(
					false, "localhost", 5005, Instant.parse("2025-01-15T10:30:45Z"), "Connection refused"
				);

			final String block = DiagnoseReportRenderer.renderConnectionBlock(status, 5005);

			assertThat(block).contains("⚠ Not connected");
			assertThat(block).contains("Last attempt:");
			// Quoted so the user can copy/paste the exact error.
			assertThat(block).contains("\"Connection refused\"");
			assertThat(block).contains("Suggestion:");
			assertThat(block).contains("address=*:5005");
		}

		@Test
		@DisplayName("disconnected with prior successful attempt: marks attempt 'succeeded'")
		void shouldRenderDisconnectedWithPriorSuccess() {
			final DiagnoseReportRenderer.JDIConnectionStatusView status =
				new DiagnoseReportRenderer.JDIConnectionStatusView(
					false, "localhost", 5005, Instant.parse("2025-01-15T10:30:45Z"), null
				);

			final String block = DiagnoseReportRenderer.renderConnectionBlock(status, 5005);

			assertThat(block).contains("Last attempt:");
			assertThat(block).contains("succeeded");
			// The quoted error form must not bleed through when there was no error.
			assertThat(block).doesNotContain("\"null\"");
		}

		@Test
		@DisplayName("disconnected with no prior attempt: prints 'Last attempt:  never'")
		void shouldRenderNeverAttemptedDisconnected() {
			final DiagnoseReportRenderer.JDIConnectionStatusView status =
				new DiagnoseReportRenderer.JDIConnectionStatusView(false, null, 0, null, null);

			final String block = DiagnoseReportRenderer.renderConnectionBlock(status, 5005);

			assertThat(block).contains("⚠ Not connected");
			assertThat(block).contains("Last attempt:  never");
			assertThat(block).contains("Suggestion:");
		}
	}

	@Nested
	@DisplayName("renderVmCapabilitiesBlock")
	class VmCapabilities {

		@Test
		@DisplayName("both capabilities yes: emits both lines and the perf warning")
		void shouldRenderBothCapabilitiesAndPerfWarning() {
			final String block = DiagnoseReportRenderer.renderVmCapabilitiesBlock(true, true);

			assertThat(block).contains("▸ VM capabilities (field watchpoints)");
			assertThat(block).contains("canWatchFieldAccess:       yes");
			assertThat(block).contains("canWatchFieldModification: yes");
			assertThat(block).contains("dominate target-VM CPU");
			assertThat(block).contains("narrow filters");
		}

		@Test
		@DisplayName("only one capability yes: still emits the block with the missing capability marked 'no'")
		void shouldRenderBlockWhenOnlyOneCapabilityIsAvailable() {
			final String block = DiagnoseReportRenderer.renderVmCapabilitiesBlock(false, true);

			assertThat(block).contains("canWatchFieldAccess:       no");
			assertThat(block).contains("canWatchFieldModification: yes");
		}

		@Test
		@DisplayName("both capabilities false: returns empty string so the report layout isn't disturbed")
		void shouldReturnEmptyStringWhenBothCapabilitiesAreFalse() {
			final String block = DiagnoseReportRenderer.renderVmCapabilitiesBlock(false, false);

			assertThat(block).isEmpty();
		}
	}

	@Nested
	@DisplayName("renderJvmListBlock")
	class JvmList {

		@Test
		@DisplayName("empty descriptor list: still renders header and explanatory note")
		void shouldRenderEmptyBlockWithHelpfulHint() {
			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(), "jno");

			assertThat(block).contains("▸ Local JVMs visible to user 'jno' (0 found)");
			assertThat(block).contains("no JVMs discovered");
			assertThat(block).doesNotContain("Legend:");
		}

		@Test
		@DisplayName("populated: renders columns, legend, and column-aligned PID/main-class/JDWP")
		void shouldRenderPopulatedBlock() {
			final JvmDescriptor d1 = new JvmDescriptor(
				42L, "com.example.Main", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS
			);
			final JvmDescriptor d2 = new JvmDescriptor(
				9999L, null, null, null, null,
				false, JvmDescriptor.Source.ATTACH_API
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(d1, d2), "jno");

			assertThat(block).contains("▸ Local JVMs visible to user 'jno' (2 found)");
			// Column header present.
			assertThat(block).contains("PID    Main class / JAR");
			// The PID column is right-padded to 5 chars, so a 2-digit PID has 3 leading spaces.
			assertThat(block).contains("     42  ");
			assertThat(block).contains("   9999  ");
			// Class column.
			assertThat(block).contains("com.example.Main");
			// Unknown main class is rendered explicitly.
			assertThat(block).contains("<unknown>");
			// State column.
			assertThat(block).contains("LISTENING");
			assertThat(block).contains("no JDWP");
			// Legend appears once.
			assertThat(block).contains("Legend: (s)=suspend=y");
		}
	}

	@Nested
	@DisplayName("formatJdwp (via JVM list rendering)")
	class FormatJdwp {

		@Test
		@DisplayName("null endpoint → em dash with 'no JDWP' state")
		void shouldRenderNullEndpoint() {
			final JvmDescriptor d = new JvmDescriptor(
				1L, "x", null, null, null, false, JvmDescriptor.Source.ATTACH_API
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(d), "u");

			assertThat(block).contains("—");
			assertThat(block).contains("no JDWP");
		}

		@Test
		@DisplayName("wildcard host '*' renders as ':<port>' without a leading host")
		void shouldRenderWildcardHost() {
			final JvmDescriptor d = new JvmDescriptor(
				1L, "x", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(d), "u");

			assertThat(block).contains(":5005");
			// The wildcard char itself must not be emitted in front of the port.
			assertThat(block).doesNotContain("*:5005");
		}

		@Test
		@DisplayName("concrete host renders verbatim before ':<port>'")
		void shouldRenderConcreteHost() {
			final JvmDescriptor d = new JvmDescriptor(
				1L, "x", null, null,
				new JdwpEndpoint("127.0.0.1", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(d), "u");

			assertThat(block).contains("127.0.0.1:5005");
		}

		@Test
		@DisplayName("suspend=y endpoint appends ' (s)' suffix")
		void shouldRenderSuspendFlag() {
			final JvmDescriptor d = new JvmDescriptor(
				1L, "x", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, true, JdwpEndpoint.State.SUSPENDED),
				false, JvmDescriptor.Source.PROC_FS
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(d), "u");

			assertThat(block).contains(":5005 (s)");
		}
	}

	@Nested
	@DisplayName("formatState (via JVM list rendering)")
	class FormatState {

		@Test
		@DisplayName("each JDWP state renders its canonical label")
		void shouldRenderEachStateLabel() {
			// Build one descriptor per state and assert every label appears in the rendered block.
			final JvmDescriptor listening = descriptor(1L, JdwpEndpoint.State.LISTENING);
			final JvmDescriptor attachable = descriptor(2L, JdwpEndpoint.State.ATTACHABLE);
			final JvmDescriptor suspended = descriptor(3L, JdwpEndpoint.State.SUSPENDED);
			final JvmDescriptor connectedToUs = descriptor(4L, JdwpEndpoint.State.CONNECTED_TO_US);
			final JvmDescriptor unreachable = descriptor(5L, JdwpEndpoint.State.UNREACHABLE);
			final JvmDescriptor unknown = descriptor(6L, JdwpEndpoint.State.UNKNOWN);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(
				List.of(listening, attachable, suspended, connectedToUs, unreachable, unknown),
				"u"
			);

			assertThat(block).contains("LISTENING");
			assertThat(block).contains("ATTACHABLE");
			assertThat(block).contains("SUSPENDED");
			// CONNECTED_TO_US renders as "ATTACHED" to read as plain English to a human.
			assertThat(block).contains("ATTACHED");
			assertThat(block).contains("UNREACHABLE");
			assertThat(block).contains("UNKNOWN");
		}

		private JvmDescriptor descriptor(long pid, JdwpEndpoint.State state) {
			return new JvmDescriptor(
				pid, "com.example.M" + pid, null, null,
				new JdwpEndpoint("*", (int) (5000 + pid), "dt_socket", true, false, state),
				false, JvmDescriptor.Source.PROC_FS
			);
		}
	}

	@Nested
	@DisplayName("formatMainClass (via JVM list rendering)")
	class FormatMainClass {

		@Test
		@DisplayName("long main class name is truncated and ends with a horizontal ellipsis")
		void shouldTruncateLongMainClass() {
			final String longName = "com.example.really.long.package.name.with.many.segments.MainClass";
			final JvmDescriptor d = new JvmDescriptor(
				1L, longName, null, null, null,
				false, JvmDescriptor.Source.ATTACH_API
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(d), "u");

			// Full name is too long to fit in the 38-char column.
			assertThat(block).doesNotContain(longName);
			// Truncation marker present.
			assertThat(block).contains("…");
		}

		@Test
		@DisplayName("THIS PROCESS marker is preserved even when main class would overflow the column")
		void shouldKeepThisProcessMarkerOnOverflow() {
			final String longName = "com.example.really.long.package.name.with.many.segments.MainClass";
			final JvmDescriptor d = new JvmDescriptor(
				1L, longName, null, null, null,
				true, JvmDescriptor.Source.ATTACH_API
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(d), "u");

			// The marker is the most actionable piece of info on the row; the truncator must
			// shorten the class name to make room for it instead of dropping the marker itself.
			assertThat(block).contains("(THIS PROCESS)");
			assertThat(block).contains("…");
		}
	}

	@Nested
	@DisplayName("renderAttachHints")
	class AttachHints {

		@Test
		@DisplayName("SUSPENDED endpoint wins over LISTENING — even if LISTENING appears first")
		void shouldPreferSuspendedOverListening() {
			final JvmDescriptor listening = new JvmDescriptor(
				1L, "first", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS
			);
			final JvmDescriptor suspended = new JvmDescriptor(
				2L, "second", null, null,
				new JdwpEndpoint("*", 5006, "dt_socket", true, true, JdwpEndpoint.State.SUSPENDED),
				false, JvmDescriptor.Source.PROC_FS
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(listening, suspended), "u");

			assertThat(block).contains("💡 To attach");
			// The hint must reference port 5006 (the SUSPENDED JVM is more urgent).
			assertThat(block).contains("port=5006");
			assertThat(block).doesNotContain("port=5005");
		}

		@Test
		@DisplayName("only-LISTENING list: hints at the first LISTENING endpoint")
		void shouldFallBackToListeningWhenNoneSuspended() {
			final JvmDescriptor listening = new JvmDescriptor(
				1L, "x", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(listening), "u");

			assertThat(block).contains("💡 To attach");
			assertThat(block).contains("port=5005");
		}

		@Test
		@DisplayName("no listening/suspended endpoints: hint is omitted entirely")
		void shouldOmitHintWhenNoAttachableEndpoint() {
			final JvmDescriptor unreachable = new JvmDescriptor(
				1L, "x", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.UNREACHABLE),
				false, JvmDescriptor.Source.PROC_FS
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(unreachable), "u");

			assertThat(block).doesNotContain("💡 To attach");
		}

		@Test
		@DisplayName("THIS PROCESS row is skipped when picking an attach target")
		void shouldSkipThisProcessWhenChoosingTarget() {
			final JvmDescriptor self = new JvmDescriptor(
				1L, "JdwpMcpServerApplication", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, true, JdwpEndpoint.State.SUSPENDED),
				true, JvmDescriptor.Source.ATTACH_API
			);
			final JvmDescriptor other = new JvmDescriptor(
				2L, "com.example.Other", null, null,
				new JdwpEndpoint("*", 5006, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS
			);

			final String block = DiagnoseReportRenderer.renderJvmListBlock(List.of(self, other), "u");

			// The hint must NOT point at the MCP server itself (port 5005 belongs to THIS PROCESS).
			assertThat(block).contains("💡 To attach");
			assertThat(block).contains("port=5006");
			assertThat(block).doesNotContain("port=5005");
		}
	}
}

package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JdwpEndpoint;
import one.edee.mcp.jdwp.discovery.JvmDescriptor;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the three-block {@code jdwp_diagnose} output. Each test pins one
 * observable feature of the rendered report: the MCP-server / connection / local-JVM block
 * structure, the connected-path branches (column alignment, attach-hint precedence), the
 * disconnected branches (last-attempt rendering with and without a prior error), and the
 * graceful degradation when discovery throws or {@code getConnectionStatus} fails outright.
 */
@DisplayName("jdwp_diagnose three-block report")
class JDWPToolsDiagnoseTest {

	private JDIConnectionService jdiService;
	private JvmDiscoveryService discovery;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker tracker = new BreakpointTracker();
		final WatcherManager watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		discovery = mock(JvmDiscoveryService.class);
		when(discovery.discover()).thenReturn(List.of());
		when(discovery.confirmAll(Mockito.anyList(), Mockito.any(), Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		when(discovery.inspectAll(Mockito.anyList())).thenAnswer(inv -> inv.getArgument(0));
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), discovery);
	}

	@Test
	@DisplayName("disconnected: renders three blocks with last-attempt error")
	void shouldRenderThreeBlocksWhenDisconnected() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, "localhost", 5005, Instant.now(), "Connection refused"
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("MCP JDWP Inspector — Diagnostic Report");
		assertThat(result).contains("▸ MCP server");
		assertThat(result).contains("▸ JDWP connection");
		assertThat(result).contains("Not connected");
		assertThat(result).contains("Connection refused");
		assertThat(result).contains("▸ Local JVMs visible to user");
	}

	@Test
	@DisplayName("connected: connection block shows host:port + appended breakpoint report")
	void shouldAppendBreakpointReportWhenConnected() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			true, "localhost", 5005, Instant.now(), null
		));

		final String result = tools.jdwp_diagnose(false);

		assertThat(result).contains("✓ Connected to localhost:5005");
		// The legacy breakpoint diagnostic header appears verbatim.
		assertThat(result).contains("[DIAGNOSTIC] Current debugger state:");
		assertThat(result).contains("Active line breakpoints: 0");
		assertThat(result).contains("INTERPRETATION:");
	}

	/**
	 * Regression for P1-5: the MCP server's own JVM row must be filtered out of the rendered
	 * inventory — the agent can never attach to it, so the row is pure noise. Other JVMs on the
	 * list continue to render normally.
	 */
	@Test
	@DisplayName("THIS PROCESS row is filtered from the JVM block (P1-5)")
	void shouldFlagThisProcessRow() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final long selfPid = ProcessHandle.current().pid();
		when(discovery.discover()).thenReturn(List.of(
			new JvmDescriptor(selfPid, "JdwpMcpServerApplication", null, null,
				null, true, JvmDescriptor.Source.ATTACH_API),
			new JvmDescriptor(98765L, "com.example.Other", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.BOTH)
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).doesNotContain("(THIS PROCESS)");
		assertThat(result).doesNotContain("JdwpMcpServerApplication");
		// Sibling row must still render normally.
		assertThat(result).contains("98765");
		assertThat(result).contains(":5005");
		assertThat(result).contains("LISTENING");
	}

	@Test
	@DisplayName("inspectAll=true delegates to JvmDiscoveryService.inspectAll, default does not")
	void shouldRouteInspectAllFlag() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));

		tools.jdwp_diagnose(null);
		tools.jdwp_diagnose(false);
		verify(discovery, never()).inspectAll(Mockito.anyList());

		tools.jdwp_diagnose(true);
		verify(discovery, times(1)).inspectAll(Mockito.anyList());
	}

	@Test
	@DisplayName("when discovery throws, the rest of the report still renders")
	void shouldDegradeGracefullyOnDiscoveryFailure() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		when(discovery.discover()).thenThrow(new RuntimeException("attach api blew up"));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("▸ MCP server");
		assertThat(result).contains("▸ JDWP connection");
		assertThat(result).contains("Local JVMs");
		assertThat(result).contains("discovery failed: attach api blew up");
	}

	/**
	 * Regression for P1-5: a long-named THIS-PROCESS JVM is now filtered entirely — neither the
	 * truncated form nor the marker appear. The block reports "0 found" instead.
	 */
	@Test
	@DisplayName("long main class on THIS PROCESS row is filtered out (P1-5)")
	void shouldTruncateLongMainClassPreservingThisProcessMarker() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final long selfPid = ProcessHandle.current().pid();
		final String longMain = "com.example.really.long.package.with.many.segments.AndSubpackages.MainClass";
		when(discovery.discover()).thenReturn(List.of(
			new JvmDescriptor(selfPid, longMain, null, null, null, true, JvmDescriptor.Source.ATTACH_API)
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).doesNotContain(longMain);
		assertThat(result).doesNotContain("(THIS PROCESS)");
		assertThat(result).contains("(0 found)");
	}

	@Test
	@DisplayName("attach hint prefers the SUSPENDED port over a coexisting LISTENING port")
	void shouldPreferSuspendedPortForAttachHint() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		when(discovery.discover()).thenReturn(List.of(
			new JvmDescriptor(11111L, "com.first.Listening", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS),
			new JvmDescriptor(22222L, "com.second.Suspended", null, null,
				new JdwpEndpoint("*", 5006, "dt_socket", true, true, JdwpEndpoint.State.SUSPENDED),
				false, JvmDescriptor.Source.PROC_FS)
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("💡 To attach");
		// SUSPENDED port wins regardless of input order. Use containsPattern to nail the hint
		// line specifically — the MCP-server block also prints "port=5005" in its Configured
		// line, so a plain doesNotContain("port=5005") would be too coarse.
		assertThat(result).containsPattern("💡 To attach: jdwp_wait_for_attach\\(port=5006\\)");
	}

	@Test
	@DisplayName("disconnected with no prior attempt prints 'Last attempt:  never'")
	void shouldRenderNeverAttemptedWhenStatusHasNoTimestamp() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("Last attempt:  never");
	}

	@Test
	@DisplayName("connected path does NOT emit the 'Suggestion:' line (suggestions are disconnected-only)")
	void shouldNotEmitSuggestionLineWhenConnected() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			true, "localhost", 5005, Instant.now(), null
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("✓ Connected to localhost:5005");
		assertThat(result).doesNotContain("Suggestion:");
	}

	@Test
	@DisplayName("rendered 'Tools:' line shows a positive registered-tool count")
	void shouldReportPositiveToolCount() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));

		final String result = tools.jdwp_diagnose(null);

		// The reflection-based counter must produce at least one tool — JDWPTools is full of
		// @McpTool methods. Match shape: "Tools:<whitespace><N> registered".
		assertThat(result).containsPattern("Tools:\\s+\\d+\\s+registered");
		assertThat(result).doesNotContain("Tools:         0 registered");
	}

	@Test
	@DisplayName("when getConnectionStatus() throws, all three top-level blocks still render")
	void shouldRenderAllBlocksWhenConnectionStatusThrows() {
		when(jdiService.getConnectionStatus()).thenThrow(new RuntimeException("vm reference is dead"));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("▸ MCP server");
		// The connection block always renders, even when getConnectionStatus blows up — the code
		// falls back to a synthetic disconnected ConnectionStatus.
		assertThat(result).contains("▸ JDWP connection");
		assertThat(result).contains("Local JVMs");
	}

	@Test
	@DisplayName("confirmAll is called with (lastHost, lastPort) from getConnectionStatus")
	void shouldPassConnectedHostAndPortToConfirmAll() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			true, "10.0.0.5", 5050, Instant.now(), null
		));

		tools.jdwp_diagnose(null);

		final ArgumentCaptor<String> hostCaptor = ArgumentCaptor.forClass(String.class);
		final ArgumentCaptor<Integer> portCaptor = ArgumentCaptor.forClass(Integer.class);
		verify(discovery).confirmAll(Mockito.anyList(), hostCaptor.capture(), portCaptor.capture());
		assertThat(hostCaptor.getValue()).isEqualTo("10.0.0.5");
		assertThat(portCaptor.getValue()).isEqualTo(5050);
	}
}

package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.mcp.annotation.McpResource;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the two {@code @McpResource} URIs and what their bodies return. The resources are
 * thin delegations to existing render helpers, so we only need to assert (a) the rendered
 * content matches the surface promised by the resource description, and (b) the URI / name
 * metadata stays stable — the URI is the user-facing contract surfaced in Claude Code's
 * {@code @}-mention picker, so a rename would be a breaking change.
 */
@DisplayName("JDWPTools @McpResource surface")
class JDWPToolsResourceTest {

    private JDIConnectionService jdiService;
    private JDWPTools tools;

    @BeforeEach
    void setUp() {
        jdiService = mock(JDIConnectionService.class);
        final BreakpointTracker tracker = new BreakpointTracker();
        final WatcherManager watcherManager = new WatcherManager();
        final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
        final EventHistory eventHistory = new EventHistory();
        final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);
        when(discovery.discover()).thenReturn(List.of());
        when(discovery.confirmAll(Mockito.anyList(), Mockito.any(), Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(discovery.inspectAll(Mockito.anyList())).thenAnswer(inv -> inv.getArgument(0));
        tools = JDWPToolsTestSupport.newTools(jdiService, tracker, watcherManager, evaluator, eventHistory, new EvaluationGuard(), discovery);
    }

    @Test
    @DisplayName("diagnoseResource returns the same three-block report as jdwp_diagnose(false)")
    void diagnoseResourceMatchesTool() {
        when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
            false, "localhost", 5005, Instant.now(), "Connection refused"
        ));

        final String resource = tools.diagnoseResource();

        assertThat(resource).contains("MCP JDWP Inspector — Diagnostic Report");
        assertThat(resource).contains("▸ MCP server");
        assertThat(resource).contains("▸ JDWP connection");
        assertThat(resource).contains("▸ Local JVMs visible to user");
        assertThat(resource).contains("Connection refused");
    }

    @Test
    @DisplayName("jvmsResource returns only the local-JVM block (no MCP-server or connection headers)")
    void jvmsResourceIsJvmsOnly() {
        when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
            false, null, 0, null, null
        ));

        final String resource = tools.jvmsResource();

        assertThat(resource).contains("▸ Local JVMs visible to user");
        assertThat(resource).doesNotContain("▸ MCP server");
        assertThat(resource).doesNotContain("▸ JDWP connection");
        assertThat(resource).doesNotContain("MCP JDWP Inspector — Diagnostic Report");
    }

    @Test
    @DisplayName("jvmsResource still renders when getConnectionStatus throws (defensive)")
    void jvmsResourceSurvivesConnectionStatusError() {
        when(jdiService.getConnectionStatus()).thenThrow(new RuntimeException("simulated"));

        final String resource = tools.jvmsResource();

        assertThat(resource).contains("▸ Local JVMs visible to user");
    }

    @Test
    @DisplayName("@McpResource URIs are stable: jdwp://diagnose and jdwp://jvms")
    void resourceUrisArePinned() throws NoSuchMethodException {
        final Method diagnose = JDWPTools.class.getMethod("diagnoseResource");
        final Method jvms = JDWPTools.class.getMethod("jvmsResource");

        final McpResource diagnoseAnno = diagnose.getAnnotation(McpResource.class);
        final McpResource jvmsAnno = jvms.getAnnotation(McpResource.class);

        assertThat(diagnoseAnno).isNotNull();
        assertThat(diagnoseAnno.uri()).isEqualTo("jdwp://diagnose");
        assertThat(diagnoseAnno.name()).isEqualTo("jdwp-diagnose");

        assertThat(jvmsAnno).isNotNull();
        assertThat(jvmsAnno.uri()).isEqualTo("jdwp://jvms");
        assertThat(jvmsAnno.name()).isEqualTo("jdwp-jvms");
    }
}

package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ThreadReference;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the discovery-failure and pre-warm semantics of
 * {@link JdiExpressionEvaluator#configureCompilerClasspath(ThreadReference)} and
 * {@link JdiExpressionEvaluator#prewarmClasspath(ThreadReference)}.
 *
 * <p>Covers the classpath-warming lifecycle contract: a failed discovery must reset
 * the compiler (so no stale config survives), surface an actionable exception from the strict path,
 * and stay silent on the best-effort pre-warm path.
 */
class JdiExpressionEvaluatorConfigureClasspathTest {

	private InMemoryJavaCompiler compiler;
	private JDIConnectionService jdiService;
	private JdiExpressionEvaluator evaluator;
	private ThreadReference thread;

	@BeforeEach
	void setUp() {
		compiler = mock(InMemoryJavaCompiler.class);
		jdiService = mock(JDIConnectionService.class);
		// No-op local-classpath provider — these tests only care about the remote-side reset/configure
		// semantics; LocalProjectClasspathProviderTestSupport supplies a provider that never touches
		// the real environment, filesystem, or Maven.
		evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(),
			LocalProjectClasspathProviderTestSupport.noOpProvider());
		thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(42L);
	}

	@Test
	@DisplayName("Discovery failure (no JDK) throws an actionable JdiEvaluationException")
	void shouldThrowWhenJdkNotDiscovered() {
		// Not cached → proceed into discovery; discovery leaves the JDK path null → failure.
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null);
		when(jdiService.discoverClasspath(thread)).thenReturn(null);

		assertThatThrownBy(() -> evaluator.configureCompilerClasspath(thread))
			.isInstanceOf(JdiEvaluationException.class)
			.hasMessageContaining("Classpath discovery failed")
			.hasMessageContaining("application types cannot be resolved");
	}

	@Test
	@DisplayName("Discovery failure resets the compiler and never configures it with stale state")
	void shouldResetCompilerOnDiscoveryFailure() {
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null);
		when(jdiService.discoverClasspath(thread)).thenReturn(null);

		assertThatThrownBy(() -> evaluator.configureCompilerClasspath(thread))
			.isInstanceOf(JdiEvaluationException.class);

		verify(compiler).reset();
		verify(compiler, never()).configure(any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	@DisplayName("Already-configured connection short-circuits without touching the compiler")
	void shouldShortCircuitWhenAlreadyConfigured() throws Exception {
		// A non-null discovered JDK path means the connection is already configured.
		when(jdiService.getDiscoveredJdkPath()).thenReturn("/opt/jdk");

		evaluator.configureCompilerClasspath(thread);

		verify(compiler, never()).reset();
		verify(compiler, never()).configure(any(), any(), org.mockito.ArgumentMatchers.anyInt());
		verify(jdiService, never()).discoverClasspath(any());
	}

	@Test
	@DisplayName("Successful discovery resets then configures the compiler with discovered values")
	void shouldConfigureOnSuccessfulDiscovery() throws Exception {
		// First call (guard) returns null so we proceed; second call (post-discovery) returns the path.
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("/app/classes:/app/lib.jar");
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		evaluator.configureCompilerClasspath(thread);

		verify(compiler).reset();
		verify(compiler).configure("/opt/jdk", "/app/classes:/app/lib.jar", 21);
	}

	@Test
	@DisplayName("prewarmClasspath swallows a discovery failure instead of throwing")
	void prewarmShouldNotThrowOnFailure() {
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null);
		when(jdiService.discoverClasspath(thread)).thenReturn(null);

		assertThatCode(() -> evaluator.prewarmClasspath(thread)).doesNotThrowAnyException();
		verify(compiler).reset();
	}

	@Test
	@DisplayName("prewarmClasspath short-circuits when the connection is already configured")
	void prewarmShouldShortCircuitWhenConfigured() {
		when(jdiService.getDiscoveredJdkPath()).thenReturn("/opt/jdk");

		evaluator.prewarmClasspath(thread);

		verify(jdiService, never()).discoverClasspath(any());
		verify(compiler, never()).reset();
	}
}

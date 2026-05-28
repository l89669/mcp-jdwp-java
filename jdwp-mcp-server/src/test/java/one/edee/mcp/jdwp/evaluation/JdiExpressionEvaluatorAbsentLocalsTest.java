package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Regression for the "Expression evaluation failed: null" field-logpoint failure observed against a
 * target compiled without a local-variable table. A frame from a target built with {@code -g:none}
 * (or plain {@code javac}, whose default omits the variable table) makes
 * {@link StackFrame#visibleVariables()} throw {@link AbsentInformationException} — with a {@code null}
 * message. {@code buildContext} used to let that abort the whole evaluation, so even an expression
 * naming only synthetic bindings ({@code $oldValue}/{@code $newValue}/…) and no locals failed with an
 * opaque {@code null}. The fix skips locals when the table is absent and evaluates against
 * {@code this} + the synthetics instead.
 *
 * <p>Verified live before the fix: two JDWP probes differing <em>only</em> in {@code -g} — the
 * {@code -g} build rendered {@code "value 0 -> 1"}, the no-{@code -g} build returned the {@code null}.
 */
class JdiExpressionEvaluatorAbsentLocalsTest {

	private InMemoryJavaCompiler compiler;
	private JdiExpressionEvaluator evaluator;

	@BeforeEach
	void setUp() throws Exception {
		compiler = mock(InMemoryJavaCompiler.class);
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(),
			LocalProjectClasspathProviderTestSupport.noOpProvider());
		// Echo back the requested class name so the post-compile bytecode lookup hits.
		when(compiler.compile(anyString(), anyString()))
			.thenAnswer(inv -> Map.of(inv.getArgument(0, String.class), new byte[]{1, 2, 3}));
	}

	/**
	 * Wires a frame whose {@code this} is a public type but whose {@code visibleVariables()} throws
	 * {@link AbsentInformationException} (null message) — exactly what the JVM does for a frame with
	 * no local-variable table.
	 */
	private StackFrame frameWithNoLocalTable() throws Exception {
		final StackFrame frame = mock(StackFrame.class);
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		when(frame.thread()).thenReturn(thread);
		when(frame.virtualMachine()).thenReturn(mock(VirtualMachine.class));
		// The crux: no local-variable table → AbsentInformationException with a null message.
		when(frame.visibleVariables()).thenThrow(new AbsentInformationException());

		final ObjectReference thisRef = mock(ObjectReference.class);
		final ClassType thisType = mock(ClassType.class);
		when(thisType.name()).thenReturn("com.app.Holder");
		when(thisType.isPublic()).thenReturn(true);
		when(thisType.allFields()).thenReturn(List.of());
		when(thisType.classLoader()).thenReturn(mock(ClassLoaderReference.class));
		when(thisRef.referenceType()).thenReturn(thisType);
		when(frame.thisObject()).thenReturn(thisRef);
		return frame;
	}

	@Test
	@DisplayName("a synthetic-only expression still evaluates when the frame has no local-variable table")
	void shouldEvaluateSyntheticBindingsWhenLocalsAbsent() throws Exception {
		final StackFrame frame = frameWithNoLocalTable();

		// $oldValue bound as a primitive int — mirrors a field-logpoint modification on an int field.
		final IntegerValue oldValue = mock(IntegerValue.class);
		final PrimitiveType intType = mock(PrimitiveType.class);
		when(oldValue.type()).thenReturn(intType);
		when(intType.name()).thenReturn("int");

		final Value expected = mock(Value.class);
		try (MockedStatic<RemoteCodeExecutor> mocked = mockStatic(RemoteCodeExecutor.class)) {
			mocked.when(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()))
				.thenReturn(expected);

			final Value result = evaluator.evaluate(frame, "\"value \" + $oldValue", Map.of("$oldValue", oldValue));

			// Before the fix this threw JdiEvaluationException("Expression evaluation failed: null").
			assertThat(result).isSameAs(expected);
			// Proof it got past buildContext all the way to execution rather than aborting on the locals.
			mocked.verify(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()),
				times(1));
		}
	}

	@Test
	@DisplayName("a null-message failure is wrapped with the exception class name, never 'failed: null'")
	void shouldFallBackToClassNameWhenCauseMessageIsNull() throws Exception {
		final StackFrame frame = frameWithNoLocalTable();

		try (MockedStatic<RemoteCodeExecutor> mocked = mockStatic(RemoteCodeExecutor.class)) {
			// A bare exception with no message — the old code rendered "Expression evaluation failed: null".
			mocked.when(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()))
				.thenThrow(new IllegalStateException());

			assertThatThrownBy(() -> evaluator.evaluate(frame, "1 + 1", Map.of()))
				.isInstanceOf(JdiEvaluationException.class)
				.hasMessageContaining("IllegalStateException")
				.hasMessageNotContaining(": null");
		}
	}
}

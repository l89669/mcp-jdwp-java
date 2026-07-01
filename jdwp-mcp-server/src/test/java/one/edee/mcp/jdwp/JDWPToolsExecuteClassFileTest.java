package one.edee.mcp.jdwp;

import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.CompiledClassExecutor;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("jdwp_execute_class_file")
class JDWPToolsExecuteClassFileTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private CompiledClassExecutor compiledClassExecutor;
	private EvaluationGuard evaluationGuard;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		compiledClassExecutor = mock(CompiledClassExecutor.class);
		evaluationGuard = new EvaluationGuard();
		tools = JDWPToolsTestSupport.newTools(
			jdiService,
			breakpointTracker,
			mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class),
			new EventHistory(),
			evaluationGuard,
			new JvmDiscoveryService(),
			compiledClassExecutor
		);
		vm = mock(VirtualMachine.class);
	}

	@Test
	@DisplayName("loads a class file and invokes run() on the last breakpoint thread")
	void shouldExecuteClassFileOnLastBreakpointThread(@TempDir Path tempDir) throws Exception {
		final Path classFile = Files.write(tempDir.resolve("Probe.class"), new byte[]{1, 2, 3});
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		final StringReference value = mock(StringReference.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(breakpointTracker.getLastBreakpointThread()).thenReturn(thread);
		when(thread.uniqueID()).thenReturn(7L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(compiledClassExecutor.executeClassFile(
			eq(vm),
			eq(thread),
			eq(frame),
			eq(classFile.toAbsolutePath().normalize()),
			eq("debug.Probe"),
			eq("run")
		)).thenReturn(value);
		when(jdiService.formatFieldValue(value)).thenReturn("\"ok\"");

		final String result = tools.jdwp_execute_class_file(
			classFile.toString(), "debug.Probe", null, null, null);

		assertThat(result).isEqualTo("Executed debug.Probe.run() from "
			+ classFile.toAbsolutePath().normalize() + ": \"ok\"");
		assertThat(evaluationGuard.depth(7L)).isZero();
	}

	@Test
	@DisplayName("uses explicit thread, frame, and method when provided")
	void shouldRespectExplicitThreadFrameAndMethod(@TempDir Path tempDir) throws Exception {
		final Path classFile = Files.write(tempDir.resolve("Probe.class"), new byte[]{1});
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(java.util.List.of(thread));
		when(thread.uniqueID()).thenReturn(11L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(2)).thenReturn(frame);
		when(jdiService.formatFieldValue(null)).thenReturn("null");

		tools.jdwp_execute_class_file(classFile.toString(), "debug.Probe", "inspect", 11L, 2);

		verify(compiledClassExecutor).executeClassFile(
			eq(vm),
			eq(thread),
			eq(frame),
			eq(classFile.toAbsolutePath().normalize()),
			eq("debug.Probe"),
			eq("inspect")
		);
		assertThat(evaluationGuard.depth(11L)).isZero();
	}

	@Test
	@DisplayName("rejects non-class paths before touching the target VM")
	void shouldRejectNonClassPath(@TempDir Path tempDir) throws Exception {
		final Path textFile = Files.writeString(tempDir.resolve("Probe.txt"), "not bytecode");

		final String result = tools.jdwp_execute_class_file(
			textFile.toString(), "debug.Probe", null, null, null);

		assertThat(result).startsWith("Error: classFilePath must point to a .class file:");
	}

	@Test
	@DisplayName("clears the evaluation guard when execution fails")
	void shouldClearGuardWhenExecutionFails(@TempDir Path tempDir) throws Exception {
		final Path classFile = Files.write(tempDir.resolve("Probe.class"), new byte[]{1});
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(breakpointTracker.getLastBreakpointThread()).thenReturn(thread);
		when(thread.uniqueID()).thenReturn(13L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(compiledClassExecutor.executeClassFile(
			eq(vm),
			eq(thread),
			eq(frame),
			eq(classFile.toAbsolutePath().normalize()),
			eq("debug.Probe"),
			eq("run")
		)).thenThrow(new JdiEvaluationException("boom"));

		final String result = tools.jdwp_execute_class_file(
			classFile.toString(), "debug.Probe", null, null, null);

		assertThat(result).isEqualTo("Error executing class file: boom");
		assertThat(evaluationGuard.depth(13L)).isZero();
	}
}

package one.edee.mcp.jdwp;

import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that a breakpoint deferred against an <em>unloaded, misspelled</em> class name surfaces
 * a "did you mean…" hint pointing at the resembling loaded class (issue #24). Without it, a typo'd
 * fully-qualified name defers forever with no signal.
 */
@DisplayName("deferred breakpoint — near-match class suggestion")
class JDWPToolsDeferredSuggestionTest {

	private JDIConnectionService jdiService;
	private JDWPTools tools;
	private VirtualMachine vm;
	private EventRequestManager erm;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
	}

	private ReferenceType loadedClass(String name) {
		final ReferenceType rt = mock(ReferenceType.class);
		when(rt.name()).thenReturn(name);
		return rt;
	}

	@Test
	@DisplayName("a deferred BP on a misspelled class names the resembling loaded class")
	void shouldSuggestNearMatchOnDeferral() throws Exception {
		when(jdiService.findLoadedClass("com.example.Config")).thenReturn(null);
		when(vm.classesByName("com.example.Config")).thenReturn(List.of());
		when(erm.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));
		// Build the loaded-class mocks into a local first — stubbing them inside the outer
		// thenReturn(...) would trip Mockito's unfinished-stubbing guard.
		final List<ReferenceType> loaded = List.of(
			loadedClass("com.example.Configuration"),
			loadedClass("com.example.service.OrderService"),
			loadedClass("java.lang.String"));
		when(vm.allClasses()).thenReturn(loaded);

		final String result = tools.jdwp_set_breakpoint("com.example.Config", 10, "all", null, null, null, null);

		assertThat(result)
			.startsWith("Breakpoint deferred for com.example.Config:10")
			.contains("Did you mean")
			.contains("com.example.Configuration");
	}

	@Test
	@DisplayName("a deferred BP on a name with no near-match adds no suggestion line")
	void shouldNotSuggestWhenNothingClose() throws Exception {
		when(jdiService.findLoadedClass("com.example.Zphyrqx")).thenReturn(null);
		when(vm.classesByName("com.example.Zphyrqx")).thenReturn(List.of());
		when(erm.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));
		final List<ReferenceType> loaded = List.of(
			loadedClass("com.example.service.OrderService"),
			loadedClass("java.lang.String"));
		when(vm.allClasses()).thenReturn(loaded);

		final String result = tools.jdwp_set_breakpoint("com.example.Zphyrqx", 10, "all", null, null, null, null);

		assertThat(result)
			.startsWith("Breakpoint deferred for com.example.Zphyrqx:10")
			.doesNotContain("Did you mean");
	}
}

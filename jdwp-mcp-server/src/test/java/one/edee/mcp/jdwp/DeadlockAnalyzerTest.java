package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.DeadlockAnalyzer.WaitForEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DeadlockAnalyzer} — pure cycle detection over a monitor wait-for graph,
 * with no JDI involvement. Covers the empty graph, the canonical AB-BA two-thread deadlock, larger
 * cycles, chains that merely feed a cycle, independent simultaneous deadlocks, and edge guards.
 */
class DeadlockAnalyzerTest {

	@Test
	@DisplayName("no edges → no deadlock")
	void shouldFindNothingInEmptyGraph() {
		assertThat(DeadlockAnalyzer.findDeadlockCycles(List.of())).isEmpty();
	}

	@Test
	@DisplayName("acyclic wait chain → no deadlock")
	void shouldFindNothingInAcyclicChain() {
		// 3 waits-for 2, 2 waits-for 1, 1 waits for nobody (runnable owner) → no cycle.
		final List<WaitForEdge> edges = List.of(
			new WaitForEdge(3, 2),
			new WaitForEdge(2, 1)
		);
		assertThat(DeadlockAnalyzer.findDeadlockCycles(edges)).isEmpty();
	}

	@Test
	@DisplayName("AB-BA two-thread deadlock → one cycle of {A,B}")
	void shouldDetectClassicTwoThreadDeadlock() {
		final List<WaitForEdge> edges = List.of(
			new WaitForEdge(1, 2),
			new WaitForEdge(2, 1)
		);
		final List<List<Long>> cycles = DeadlockAnalyzer.findDeadlockCycles(edges);
		assertThat(cycles).hasSize(1);
		assertThat(cycles.get(0)).containsExactlyInAnyOrder(1L, 2L);
	}

	@Test
	@DisplayName("three-thread cycle A→B→C→A → one cycle of {A,B,C}")
	void shouldDetectThreeThreadCycle() {
		final List<WaitForEdge> edges = List.of(
			new WaitForEdge(1, 2),
			new WaitForEdge(2, 3),
			new WaitForEdge(3, 1)
		);
		final List<List<Long>> cycles = DeadlockAnalyzer.findDeadlockCycles(edges);
		assertThat(cycles).hasSize(1);
		assertThat(cycles.get(0)).containsExactlyInAnyOrder(1L, 2L, 3L);
	}

	@Test
	@DisplayName("a thread that only feeds into a cycle is not reported as a cycle member")
	void shouldExcludeFeederFromCycle() {
		// 9 waits-for 1, and 1↔2 deadlock. 9 is blocked but not part of the loop.
		final List<WaitForEdge> edges = List.of(
			new WaitForEdge(9, 1),
			new WaitForEdge(1, 2),
			new WaitForEdge(2, 1)
		);
		final List<List<Long>> cycles = DeadlockAnalyzer.findDeadlockCycles(edges);
		assertThat(cycles).hasSize(1);
		assertThat(cycles.get(0)).containsExactlyInAnyOrder(1L, 2L);
		assertThat(cycles.get(0)).doesNotContain(9L);
	}

	@Test
	@DisplayName("two independent deadlocks → two distinct cycles")
	void shouldDetectTwoIndependentDeadlocks() {
		final List<WaitForEdge> edges = List.of(
			new WaitForEdge(1, 2),
			new WaitForEdge(2, 1),
			new WaitForEdge(3, 4),
			new WaitForEdge(4, 3)
		);
		final List<List<Long>> cycles = DeadlockAnalyzer.findDeadlockCycles(edges);
		assertThat(cycles).hasSize(2);
		assertThat(cycles).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrder(1L, 2L));
		assertThat(cycles).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrder(3L, 4L));
	}

	@Test
	@DisplayName("self-edge is ignored, not reported as a one-node cycle")
	void shouldIgnoreSelfEdge() {
		assertThat(DeadlockAnalyzer.findDeadlockCycles(List.of(new WaitForEdge(7, 7)))).isEmpty();
	}

	@Test
	@DisplayName("a self-edge alongside a real deadlock is dropped while the genuine cycle still reports")
	void shouldDropSelfEdgeButKeepRealCycle() {
		// 7 has a (physically impossible) self-edge that must be discarded; 1↔2 is a true deadlock.
		final List<WaitForEdge> edges = List.of(
			new WaitForEdge(7, 7),
			new WaitForEdge(1, 2),
			new WaitForEdge(2, 1)
		);
		final List<List<Long>> cycles = DeadlockAnalyzer.findDeadlockCycles(edges);
		assertThat(cycles).hasSize(1);
		assertThat(cycles.get(0)).containsExactlyInAnyOrder(1L, 2L);
		assertThat(cycles.get(0)).doesNotContain(7L);
	}

	@Test
	@DisplayName("a later start walking into already-settled feeders does not re-report the shared cycle")
	void shouldNotReReportCycleReachedViaSettledFeeders() {
		// Two acyclic feeder branches converge on a single 1↔2 deadlock. Whichever feeder is walked
		// first settles node 1 (and 2); the second feeder then walks into the already-settled node
		// and terminates without recording the cycle a second time.
		//   8 → 7 → 6 → 1     (long chain feeder)
		//   5 → 1             (second feeder branch)
		//   1 ↔ 2             (the deadlock)
		final List<WaitForEdge> edges = List.of(
			new WaitForEdge(8, 7),
			new WaitForEdge(7, 6),
			new WaitForEdge(6, 1),
			new WaitForEdge(5, 1),
			new WaitForEdge(1, 2),
			new WaitForEdge(2, 1)
		);
		final List<List<Long>> cycles = DeadlockAnalyzer.findDeadlockCycles(edges);
		assertThat(cycles).hasSize(1);
		assertThat(cycles.get(0)).containsExactlyInAnyOrder(1L, 2L);
		assertThat(cycles.get(0)).doesNotContain(5L, 6L, 7L, 8L);
	}
}

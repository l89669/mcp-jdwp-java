package one.edee.jdwp.sandbox.race;

import java.util.concurrent.CyclicBarrier;

/**
 * A counter with a non-atomic increment. The read and the write-back straddle a barrier, so two
 * threads deterministically read the same value before either writes — the classic lost update.
 */
public class RaceCounter {

	private int count = 0;

	public int getCount() {
		return count;
	}

	/**
	 * Reads the current count, syncs with the other thread at the barrier, then writes back
	 * {@code read + 1}. Because both threads read before either writes, one increment is lost:
	 * two calls leave the count at 1, not 2.
	 *
	 * <p>The barrier is also the bug's defence against clumsy observation: if you set a suspending
	 * breakpoint here, the suspended thread never reaches {@code await()}, so the other thread's
	 * {@code await()} times out and the run takes a different path. A non-suspending logpoint at
	 * the read leaves the timing intact and records both threads reading the same value.
	 */
	public void increment(CyclicBarrier barrier) throws Exception {
		int observed = count;
		barrier.await(); // both threads have read by the time anyone proceeds past here
		count = observed + 1;
	}
}

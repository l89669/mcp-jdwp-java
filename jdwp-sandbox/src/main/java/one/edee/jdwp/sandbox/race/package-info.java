/**
 * Flight #7: The Heisenbug Race — a lost update you can only watch without stopping the world.
 *
 * <p>{@code RaceCounter.increment} reads the count, waits at a {@code CyclicBarrier} so both
 * threads have read before either writes, then writes back {@code read + 1}. Two threads therefore
 * both read 0 and both write 1 — the final count is 1, not 2. The symptom is a plain "expected 2
 * but was 1".
 *
 * <p>The lesson is about <em>how</em> you observe it. Suspending breakpoints serialize the threads
 * and force you to juggle two parked threads to reconstruct the interleaving; the natural,
 * non-intrusive read is a logpoint at the read site —
 * {@code jdwp_set_logpoint(…, "\"" + Thread.currentThread().getName() + " read \" + count")} (or a
 * field logpoint on {@code count}). {@code jdwp_get_events} then shows both {@code racer-1} and
 * {@code racer-2} reading 0 before either write, which is the lost update laid out in order.
 */
package one.edee.jdwp.sandbox.race;

/**
 * Flight #9: The Polite Standoff — a deadlock you diagnose with no breakpoints at all.
 *
 * <p>{@code Account.transfer} locks the source account, pauses, then locks the destination. Two
 * transfers in opposite directions ({@code A→B} and {@code B→A}) each grab their first lock and
 * then wait forever for the other's — a classic lock-ordering deadlock. The test just hangs (its
 * join times out) and fails with "both transfers should have finished".
 *
 * <p>No breakpoint can fire — the threads are stuck below the lock acquisition, executing nothing.
 * No exception is thrown. The diagnosis is pure thread inspection: {@code jdwp_get_threads} shows
 * {@code transfer-A-to-B} and {@code transfer-B-to-A} in {@code MONITOR} state, and
 * {@code jdwp_get_stack} on each shows the inverse lock order. Note that
 * {@code jdwp_evaluate_expression} / {@code jdwp_to_string} on these threads is refused — invoking
 * a method on a monitor-blocked thread would hang the debugger, which is itself the tell.
 */
package one.edee.jdwp.sandbox.deadlock;

/**
 * Flight #3: The Time Traveler's Config — a field is written correctly, then silently overwritten
 * by a second write from another thread, and only the write history reveals it.
 *
 * <p>{@code ConfigurationProvider}'s constructor calls {@code init(5000)}, so the timeout is set
 * correctly. Then {@code runMaintenanceSweep()} starts a background {@code config-reaper} thread
 * that wrongly treats the live instance as stale and calls {@code resetToDefaults()}, writing the
 * timeout back to 0. By the time the test reads it, the heap shows 0 and no local variable holds
 * 5000 — so a single breakpoint-context dump looks like the value was simply never set, with no
 * way to tell that 5000 was written and then clobbered.
 *
 * <p>The bug only yields to the write timeline: a modification watchpoint
 * ({@code jdwp_set_field_breakpoint("…Configuration", "timeout", mode="modification")}) records
 * both stores — 0→5000 from the constructor thread, then 5000→0 from {@code config-reaper}. A
 * {@code jdwp_get_stack} on the second event names the reaper as the culprit.
 */
package one.edee.jdwp.sandbox.config;

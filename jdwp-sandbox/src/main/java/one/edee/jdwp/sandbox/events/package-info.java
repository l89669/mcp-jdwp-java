/**
 * Flight #2: The Swallowed Exception — a handler fails on a background dispatch thread and the
 * failure is swallowed by the JDK with no user-code catch frame to inspect.
 *
 * <p>An {@code OrderEvent} narrows its quantity through {@code byte} (200 → -56), so
 * {@code Inventory.reserve} throws {@code IllegalStateException}. {@code EventBus.dispatch} runs
 * each handler on a single-thread executor as a fire-and-forget task: the {@code Future} is never
 * inspected, so the exception is captured inside {@code java.util.concurrent.FutureTask.run}
 * (a JDK frame) and discarded. No frame in the sandbox ever holds the throwable, so there is no
 * catch block to break on and {@code getErrorSummary()} stays empty — stock is simply unchanged
 * and nobody complains.
 *
 * <p>Because the exception <em>is</em> caught (by FutureTask), an {@code uncaught=true}-only
 * exception breakpoint never fires; the throw is reachable only with
 * {@code jdwp_set_exception_breakpoint("java.lang.IllegalStateException", caught=true)}, and a
 * trigger gate keeps bootstrap exceptions from drowning the signal.
 */
package one.edee.jdwp.sandbox.events;

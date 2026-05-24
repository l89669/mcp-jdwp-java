package one.edee.jdwp.sandbox.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dispatches order events to registered handlers on a background executor.
 * Handler failures are never observed by any caller, so the bus reports success.
 */
public class EventBus {

	private final List<EventHandler> handlers = new ArrayList<>();
	private final List<String> errors = new ArrayList<>();

	public void register(EventHandler handler) {
		handlers.add(handler);
	}

	/**
	 * Dispatches an event to all registered handlers on a single-thread executor and waits
	 * for completion. Each handler is submitted fire-and-forget — the returned Future is never
	 * inspected, so any exception a handler throws is captured by the JDK's FutureTask and
	 * silently discarded. No frame in this class (or any caller) ever catches it, and the
	 * error list below is never populated.
	 */
	public void dispatch(OrderEvent event) {
		final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
			final Thread t = new Thread(r, "event-dispatch");
			t.setDaemon(true);
			return t;
		});
		try {
			for (EventHandler handler : handlers) {
				executor.submit(() -> handler.handle(event));
			}
		} finally {
			executor.shutdown();
			try {
				// Wait for the dispatch to run so the test observes the post-dispatch state.
				executor.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Returns recorded error messages. Always empty — nothing in the dispatch path records a
	 * failure, because nothing catches one.
	 */
	public List<String> getErrorSummary() {
		return errors;
	}
}

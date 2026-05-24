package one.edee.jdwp.sandbox.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

	private Inventory inventory;
	private EventBus eventBus;

	@BeforeEach
	void setUp() {
		inventory = new Inventory();
		eventBus = new EventBus();
		eventBus.register(new InventoryHandler(inventory));
	}

	@Test
	void shouldDispatchAndUpdateInventory() {
		inventory.restock("WIDGET", 100);
		OrderEvent event = new OrderEvent("ORD-1", "WIDGET", 200); // looks like qty=200

		eventBus.dispatch(event);

		// Fails: stock is still 100. The handler threw on the dispatch thread and the failure
		// was swallowed by the executor — no exception ever reached this thread.
		assertThat(inventory.getStock("WIDGET"))
			.describedAs("Stock should have been reduced by the reservation")
			.isLessThan(100);

		// Also fails to help: the bus recorded nothing, because nothing caught the failure.
		assertThat(eventBus.getErrorSummary())
			.describedAs("No error was recorded — yet the reservation clearly did not happen")
			.isEmpty();
	}
}

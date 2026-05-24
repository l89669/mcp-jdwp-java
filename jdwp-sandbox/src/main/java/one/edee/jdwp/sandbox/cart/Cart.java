package one.edee.jdwp.sandbox.cart;

import java.util.ArrayList;
import java.util.List;

/**
 * A shopping cart. Carries a copy constructor added for a "snapshot" feature — the copy has
 * identical field values to the original, differing only in object identity.
 */
public class Cart {

	private final String id;
	private final List<String> items;
	private double total;

	public Cart(String id, List<String> items) {
		this.id = id;
		this.items = new ArrayList<>(items);
		this.total = 0.0;
	}

	/**
	 * Copy constructor — produces a snapshot whose fields equal {@code other}'s. Same values,
	 * different instance.
	 */
	public Cart(Cart other) {
		this.id = other.id;
		this.items = new ArrayList<>(other.items);
		this.total = other.total;
	}

	public String getId() {
		return id;
	}

	public List<String> getItems() {
		return items;
	}

	public double getTotal() {
		return total;
	}

	public void setTotal(double total) {
		this.total = total;
	}
}

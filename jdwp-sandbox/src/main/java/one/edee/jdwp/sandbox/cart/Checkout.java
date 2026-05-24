package one.edee.jdwp.sandbox.cart;

/**
 * Runs a cart through a multi-stage checkout pipeline. Each stage returns the cart to operate on;
 * {@link #process} threads it through them and returns the result.
 */
public class Checkout {

	/**
	 * Validates, prices and discounts the cart. Callers that rely on the cart being mutated
	 * in place (ignoring the return value) will see no change — see {@link #validate}.
	 */
	public Cart process(Cart cart) {
		cart = validate(cart);
		cart = price(cart);
		cart = applyDiscount(cart, 10.0);
		return cart;
	}

	/**
	 * Validates the cart and returns a defensive snapshot so later stages cannot corrupt the
	 * caller's instance. Because the snapshot is a <em>different</em> object, every later stage
	 * mutates the copy — the caller's original cart is never touched.
	 */
	private Cart validate(Cart cart) {
		if (cart.getItems().isEmpty()) {
			throw new IllegalStateException("Cannot check out an empty cart");
		}
		return new Cart(cart);
	}

	private Cart price(Cart cart) {
		cart.setTotal(cart.getItems().size() * 25.0);
		return cart;
	}

	private Cart applyDiscount(Cart cart, double percent) {
		cart.setTotal(cart.getTotal() * (1 - percent / 100));
		return cart;
	}
}

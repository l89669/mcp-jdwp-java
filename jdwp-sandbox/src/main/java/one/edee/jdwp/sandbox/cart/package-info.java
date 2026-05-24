/**
 * Flight #6: The Doppelgänger Cart — the object you get back is not the object you passed in.
 *
 * <p>{@code Checkout.process} runs a cart through validate → price → discount. {@code validate}
 * was changed to return a defensive snapshot — {@code return new Cart(cart)} — so every later
 * stage mutates the <em>copy</em>. {@code process} reassigns its local to the copy as it threads
 * the stages, so no single frame ever holds both the caller's cart and the copy at once. The test
 * ignores the return value (it assumes the cart is mutated in place), so its cart stays at total 0.
 *
 * <p>The copy has identical field values to the original — same id, same items — so a breakpoint
 * dump at any pipeline stage looks correct in isolation. The only way to prove the stages are
 * working on a different instance is to compare object identity across frames: mark the test's
 * cart with {@code jdwp_mark_instance(label="input", …)}, then break in the pipeline with a
 * condition like {@code cart != $input} — it fires, proving the doppelgänger.
 */
package one.edee.jdwp.sandbox.cart;

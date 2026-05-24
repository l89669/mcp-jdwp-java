package one.edee.jdwp.sandbox.deadlock;

/**
 * A bank account whose {@code transfer} locks the source account and then the destination account.
 * Two transfers running in opposite directions therefore acquire the two locks in opposite orders
 * — the textbook recipe for deadlock.
 */
public class Account {

	private final String id;
	private int balance;

	public Account(String id, int balance) {
		this.id = id;
		this.balance = balance;
	}

	public String getId() {
		return id;
	}

	public int getBalance() {
		return balance;
	}

	/**
	 * Transfers {@code amount} from this account to {@code to}. Locks {@code this} first, then
	 * {@code to}. The brief pause while holding the first lock widens the window so two opposing
	 * transfers reliably each grab their first lock before either can take the second — and then
	 * wait on each other forever.
	 */
	public void transfer(Account to, int amount) {
		synchronized (this) {
			pause();
			synchronized (to) {
				this.balance -= amount;
				to.balance += amount;
			}
		}
	}

	private static void pause() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

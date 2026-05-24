package one.edee.jdwp.sandbox.config;

/**
 * Builds and holds the application Configuration, and runs a maintenance sweep that is supposed
 * to clear <em>stale</em> configurations. The sweep misfires on the freshly-initialized instance:
 * it runs on a background thread and resets the timeout back to its default, clobbering the value
 * that {@link #buildConfig()} just set.
 */
public class ConfigurationProvider {

	private final Configuration instance;

	public ConfigurationProvider() {
		this.instance = new Configuration("MyApp");
		this.instance.init(5000); // correct value is written here…
	}

	public Configuration getConfig() {
		return instance;
	}

	/**
	 * Runs the "stale config" maintenance sweep on a background thread and waits for it. Intended
	 * to reset configs that have gone stale; here it wrongly treats the live, just-initialized
	 * instance as stale and zeroes its timeout — a second write that overwrites the 5000 set in
	 * the constructor. The reader sees only the final value (0) and no surviving trace of the 5000.
	 */
	public void runMaintenanceSweep() throws InterruptedException {
		final Thread reaper = new Thread(instance::resetToDefaults, "config-reaper");
		reaper.start();
		reaper.join();
	}
}

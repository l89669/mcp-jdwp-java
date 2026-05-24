package one.edee.jdwp.sandbox.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationProviderTest {

	@Test
	void shouldRetainInitializedTimeout() throws Exception {
		ConfigurationProvider provider = new ConfigurationProvider();

		// Run the maintenance sweep (background thread). It should leave a live config untouched.
		provider.runMaintenanceSweep();

		// Fails: timeout is 0, not 5000. The constructor set 5000, but the sweep wrote 0 over it.
		// At this point the heap shows 0 and there is no local holding 5000 — the only evidence
		// that 5000 was ever written is the field-modification history.
		assertThat(provider.getConfig().getTimeout())
			.describedAs("Timeout set during construction should survive the maintenance sweep")
			.isEqualTo(5000);
	}
}

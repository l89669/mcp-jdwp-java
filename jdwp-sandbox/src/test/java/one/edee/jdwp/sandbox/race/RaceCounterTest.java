package one.edee.jdwp.sandbox.race;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

class RaceCounterTest {

	@Test
	void shouldCountTwoIncrements() throws Exception {
		RaceCounter counter = new RaceCounter();
		CyclicBarrier barrier = new CyclicBarrier(2);

		Runnable task = () -> {
			try {
				counter.increment(barrier);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		};
		Thread racer1 = new Thread(task, "racer-1");
		Thread racer2 = new Thread(task, "racer-2");
		racer1.start();
		racer2.start();
		racer1.join(2000);
		racer2.join(2000);

		// Fails: both threads read 0 before either wrote, so both wrote 1 — one increment is lost.
		assertThat(counter.getCount())
			.describedAs("Two increments should yield 2")
			.isEqualTo(2);
	}
}

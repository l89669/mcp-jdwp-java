package one.edee.jdwp.sandbox.parser;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DateParserTest {

	@Test
	void shouldParseDateFromFeed() {
		DateParser parser = new DateParser();

		// The upstream feed delivers the date with a trailing space.
		String fromFeed = "2026-05-15 ";

		// Fails: Integer.parseInt("15 ") throws NumberFormatException — the day part carries the space.
		LocalDate result = parser.parse(fromFeed);

		assertThat(result).isEqualTo(LocalDate.of(2026, 5, 15));
	}
}

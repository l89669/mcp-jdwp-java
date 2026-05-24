package one.edee.jdwp.sandbox.parser;

import java.time.LocalDate;

/**
 * Parses an ISO-like {@code YYYY-MM-DD} date string into a {@link LocalDate}. Assumes its input is
 * already clean — it does not trim, so any stray whitespace on the ends flows straight into
 * {@link Integer#parseInt}.
 */
public class DateParser {

	/**
	 * Parses {@code input} as {@code YYYY-MM-DD}. Throws {@link NumberFormatException} if any
	 * component is not a clean integer — e.g. a trailing space on the day part ("15 ").
	 */
	public LocalDate parse(String input) {
		String[] parts = input.split("-");
		int year = Integer.parseInt(parts[0]);
		int month = Integer.parseInt(parts[1]);
		int day = Integer.parseInt(parts[2]);
		return LocalDate.of(year, month, day);
	}
}

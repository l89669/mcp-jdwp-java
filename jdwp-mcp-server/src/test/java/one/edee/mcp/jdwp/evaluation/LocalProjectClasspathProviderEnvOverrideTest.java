package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@code JDWP_EXTRA_CLASSPATH} env-override source of
 * {@link LocalProjectClasspathProvider}. Pins the parser's behaviour on the host
 * {@link java.io.File#pathSeparator}, on whitespace and blank-entry trimming, and on the
 * special "set but empty" case that the diagnose path treats differently from "unset".
 */
@DisplayName("LocalProjectClasspathProvider — JDWP_EXTRA_CLASSPATH env override")
class LocalProjectClasspathProviderEnvOverrideTest {

	private static final String SEP = java.io.File.pathSeparator;
	/**
	 * Guaranteed-non-existent working directory so the depth-5 filesystem scan short-circuits at the
	 * first {@code isDirectory} probe. A relative {@code /tmp/no-such-project} would be unreliable on
	 * hosts where that path happens to exist (or where {@code /tmp} contains
	 * {@code target/classes} subtrees from unrelated tooling).
	 */
	private static final Path NONEXISTENT_CWD =
		Path.of("/nonexistent/jdwp-mcp-env-test-" + java.util.UUID.randomUUID());

	@Test
	@DisplayName("parses File.pathSeparator-delimited env var into insertion-ordered entries")
	void shouldParsePathSeparatorDelimitedEnvVarIntoOrderedEntries() {
		// Use File.pathSeparator so the test is correct on both Linux (`:`) and Windows (`;`).
		// Splitting on `[;:]` is broken on Windows because `C:\foo` contains a colon — the env
		// var is parsed by the MCP server on the HOST OS, so the host separator is the right one.
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			NONEXISTENT_CWD,
			envName -> "JDWP_EXTRA_CLASSPATH".equals(envName)
				? "/opt/libs/a.jar" + SEP + "/opt/libs/b.jar" + SEP + "/srv/classes"
				: null,
			(cmd, cwd, timeoutSeconds) -> List.of() // no-op Maven runner
		);

		final Set<String> entries = provider.discover();

		assertThat(entries).containsExactly(
			"/opt/libs/a.jar", "/opt/libs/b.jar", "/srv/classes"
		);
	}

	@Test
	@DisplayName("returns empty set when env var is an empty string")
	void shouldReturnEmptySetWhenEnvVarIsEmpty() {
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			NONEXISTENT_CWD,
			envName -> "",
			(cmd, cwd, timeoutSeconds) -> List.of()
		);

		assertThat(provider.discover()).isEmpty();
	}

	@Test
	@DisplayName("returns empty set when env lookup returns null (unset)")
	void shouldReturnEmptySetWhenEnvVarIsUnset() {
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			NONEXISTENT_CWD,
			envName -> null,
			(cmd, cwd, timeoutSeconds) -> List.of()
		);

		assertThat(provider.discover()).isEmpty();
	}

	@Test
	@DisplayName("trims whitespace around entries and drops empty/blank tokens")
	void shouldTrimWhitespaceAndDropBlankEntries() {
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			NONEXISTENT_CWD,
			envName -> "  /a.jar  " + SEP + SEP + "  /b.jar ",
			(cmd, cwd, timeoutSeconds) -> List.of()
		);

		assertThat(provider.discover()).containsExactly("/a.jar", "/b.jar");
	}

	/**
	 * Even when {@code JDWP_EXTRA_CLASSPATH} is set but parses to zero usable entries (e.g. all
	 * tokens were blank), the breakdown must report it as a 0-count source. The diagnose path
	 * downstream is supposed to differentiate "set but empty" from "unset" — pinning the count
	 * here is the cleanest seam to that rendering.
	 */
	@Test
	@DisplayName("breakdown reports zero env-override entries when env is set but only blanks")
	void shouldReportZeroEnvEntriesWhenEnvIsBlanksOnly() {
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			NONEXISTENT_CWD,
			envName -> "   " + SEP + " " + SEP + "  ",
			(cmd, cwd, timeoutSeconds) -> List.of()
		);

		final LocalProjectClasspathProvider.Breakdown breakdown = provider.discoverWithBreakdown();

		assertThat(breakdown.envOverride()).isZero();
		assertThat(breakdown.all()).isEmpty();
	}
}

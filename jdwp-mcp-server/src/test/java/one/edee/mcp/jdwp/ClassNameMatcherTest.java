package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClassNameMatcher#suggest}. The driving case is the test-flight friction
 * (issue #24): a deferred breakpoint on {@code Config} should point the user at the loaded
 * {@code com.example.Configuration} rather than defer silently forever.
 */
class ClassNameMatcherTest {

	private static final List<String> LOADED = List.of(
		"com.example.Configuration",
		"com.example.ConfigurationManager",
		"com.example.service.OrderService",
		"com.example.model.Account",
		"java.lang.String",
		"com.example.Configuration$Builder"
	);

	@Test
	@DisplayName("a truncated name suggests the full class (Config → Configuration)")
	void shouldSuggestFullNameForTruncation() {
		final List<String> out = ClassNameMatcher.suggest(LOADED, "Config", 3);
		assertThat(out).contains("com.example.Configuration");
		// the shorter, closer Configuration ranks ahead of the longer ConfigurationManager
		assertThat(out.indexOf("com.example.Configuration"))
			.isLessThan(out.indexOf("com.example.ConfigurationManager"));
	}

	@Test
	@DisplayName("a single-character typo is matched via edit distance")
	void shouldMatchTypo() {
		assertThat(ClassNameMatcher.suggest(LOADED, "com.example.Configuraton", 3))
			.contains("com.example.Configuration");
	}

	@Test
	@DisplayName("an exact simple-name match in a different package is suggested first")
	void shouldRankExactSimpleNameFirst() {
		final List<String> out = ClassNameMatcher.suggest(LOADED, "com.other.Account", 3);
		assertThat(out).first().isEqualTo("com.example.model.Account");
	}

	@Test
	@DisplayName("the count cap is honoured")
	void shouldHonourMax() {
		assertThat(ClassNameMatcher.suggest(LOADED, "Config", 1)).hasSize(1);
	}

	@Test
	@DisplayName("a wholly unrelated name yields no suggestions")
	void shouldReturnNothingForUnrelatedName() {
		assertThat(ClassNameMatcher.suggest(LOADED, "com.acme.Zphyrqx", 3)).isEmpty();
	}

	@Test
	@DisplayName("the exact target is never echoed back as its own suggestion")
	void shouldNotSuggestExactTarget() {
		assertThat(ClassNameMatcher.suggest(LOADED, "com.example.Configuration", 3))
			.doesNotContain("com.example.Configuration");
	}

	@Test
	@DisplayName("array and synthetic names are skipped")
	void shouldSkipArrayAndSyntheticNames() {
		final List<String> loaded = List.of(
			"com.example.Foo$$Lambda$1",
			"com.example.Foo[]",
			"com.example.Foobar");
		assertThat(ClassNameMatcher.suggest(loaded, "com.example.Foo", 5))
			.containsExactly("com.example.Foobar");
	}

	@Test
	@DisplayName("blank target or zero max returns empty")
	void shouldGuardDegenerateInput() {
		assertThat(ClassNameMatcher.suggest(LOADED, "  ", 3)).isEmpty();
		assertThat(ClassNameMatcher.suggest(LOADED, "Config", 0)).isEmpty();
	}

	@Test
	@DisplayName("matching is case-insensitive on the simple name")
	void shouldMatchCaseInsensitively() {
		assertThat(ClassNameMatcher.suggest(LOADED, "config", 3))
			.contains("com.example.Configuration");
		assertThat(ClassNameMatcher.suggest(LOADED, "CONFIGURATION", 3))
			.contains("com.example.Configuration");
	}

	@Test
	@DisplayName("the nested simple name after the last $ drives the match")
	void shouldMatchNestedSimpleName() {
		// Target's simple name is "Bildr" (after the last $); it resolves to the loaded
		// Configuration$Builder via a small edit distance on the nested simple name.
		assertThat(ClassNameMatcher.suggest(LOADED, "com.example.Configuration$Bildr", 3))
			.contains("com.example.Configuration$Builder");
	}

	@Test
	@DisplayName("a target whose simple name is empty returns empty without throwing")
	void shouldGuardEmptySimpleName() {
		assertThat(ClassNameMatcher.suggest(LOADED, "com.example.", 3)).isEmpty();
		assertThat(ClassNameMatcher.suggest(LOADED, "com.example.Outer$", 3)).isEmpty();
	}

	@Test
	@DisplayName("a candidate whose simple-name length is far from the target yields no suggestion")
	void shouldPruneOnLengthGap() {
		// "Z" shares no prefix with the long loaded names and its simple-name length differs by
		// far more than the bounded-Levenshtein threshold, so the length-gap prune rejects it.
		assertThat(ClassNameMatcher.suggest(LOADED, "Z", 5)).isEmpty();
	}
}

package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the memoisation, reset semantics, per-source breakdown shape, immutability of the
 * cached view, and concurrent-access safety of {@link LocalProjectClasspathProvider}.
 */
@DisplayName("LocalProjectClasspathProvider — caching and breakdown")
class LocalProjectClasspathProviderCacheTest {

	@Test
	@DisplayName("subsequent discover() and discoverWithBreakdown() calls reuse the cached result")
	void shouldReuseCachedResultOnSubsequentCalls(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final AtomicInteger mavenInvocations = new AtomicInteger();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> { mavenInvocations.incrementAndGet(); return List.of("/m2/foo.jar"); }
		);

		provider.discover();
		provider.discover();
		provider.discoverWithBreakdown();

		assertThat(mavenInvocations.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("reset() clears the cache so the next discover() recomputes from sources")
	void shouldClearCacheAndForceRecomputationOnReset(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final AtomicInteger mavenInvocations = new AtomicInteger();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> { mavenInvocations.incrementAndGet(); return List.of("/m2/foo.jar"); }
		);

		provider.discover();
		provider.reset();
		provider.discover();

		assertThat(mavenInvocations.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("breakdown reports per-source entry counts and a total matching the union")
	void shouldReportPerSourceCountsInBreakdown(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target/classes"));
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp,
			envName -> "JDWP_EXTRA_CLASSPATH".equals(envName)
				? "/opt/extra.jar"
				: null,
			(cmd, cwd, t) -> List.of("/m2/a.jar", "/m2/b.jar")
		);

		final var breakdown = provider.discoverWithBreakdown();

		assertThat(breakdown.envOverride()).isEqualTo(1);
		assertThat(breakdown.filesystem()).isEqualTo(1);
		assertThat(breakdown.maven()).isEqualTo(2);
		assertThat(breakdown.all()).hasSize(4);
	}

	/**
	 * Concurrent callers reaching {@link LocalProjectClasspathProvider#discover()} from the eval
	 * path and the diagnose path must not race the underlying Maven invocation. Memoisation +
	 * synchronisation means Maven runs at most once per cache lifetime even under contention.
	 */
	@Test
	@DisplayName("concurrent discover() invocations trigger Maven at most once")
	void shouldInvokeMavenAtMostOnceUnderConcurrentDiscovery(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final AtomicInteger mavenInvocations = new AtomicInteger();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> {
				mavenInvocations.incrementAndGet();
				try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				return List.of("/m2/foo.jar");
			}
		);

		final int threadCount = 8;
		final var pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
		try {
			final var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
			for (int i = 0; i < threadCount; i++) {
				futures.add(pool.submit(provider::discover));
			}
			for (var f : futures) f.get();
		} finally {
			pool.shutdown();
		}

		assertThat(mavenInvocations.get()).isEqualTo(1);
	}

	/**
	 * The cached breakdown's {@code all()} set must be unmodifiable so consumers cannot poison the
	 * cache by mutating the returned view. The provider wraps a defensive LinkedHashSet copy in
	 * {@link java.util.Collections#unmodifiableSet} — pin the invariant.
	 */
	@Test
	@DisplayName("the cached breakdown.all() view is unmodifiable")
	void shouldExposeUnmodifiableEntrySetInBreakdown(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> List.of("/m2/foo.jar")
		);

		final var breakdown = provider.discoverWithBreakdown();

		assertThat(breakdown.all())
			.as("cached breakdown view must be unmodifiable")
			.isUnmodifiable();
	}

	/**
	 * The cached breakdown is a value object — the second call must return the SAME reference as
	 * the first so callers can identity-compare to detect a cache hit without measuring elapsed
	 * time. This pins the memoisation seam more strictly than the "invocation count == 1" test.
	 */
	@Test
	@DisplayName("the second discoverWithBreakdown() returns the same Breakdown reference as the first")
	void shouldReturnSameBreakdownReferenceOnSecondCall(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> List.of("/m2/foo.jar")
		);

		final var first = provider.discoverWithBreakdown();
		final var second = provider.discoverWithBreakdown();

		assertThat(second).isSameAs(first);
	}

	/**
	 * The non-blocking peek seam used by the diagnose renderer must return null before any
	 * discovery has run — otherwise the renderer cannot tell "cold cache, do not block" from
	 * "warm cache with zero entries".
	 */
	@Test
	@DisplayName("peekCachedBreakdown() returns null before the first discovery")
	void shouldReturnNullFromPeekBeforeDiscovery(@TempDir Path tmp) {
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		assertThat(provider.peekCachedBreakdown()).isNull();
	}

	@Test
	@DisplayName("peekCachedBreakdown() returns the populated breakdown after discovery has run")
	void shouldReturnPopulatedBreakdownFromPeekAfterDiscovery(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> List.of("/m2/foo.jar")
		);

		provider.discover();

		assertThat(provider.peekCachedBreakdown())
			.isNotNull()
			.satisfies(b -> assertThat(b.maven()).isEqualTo(1));
	}
}

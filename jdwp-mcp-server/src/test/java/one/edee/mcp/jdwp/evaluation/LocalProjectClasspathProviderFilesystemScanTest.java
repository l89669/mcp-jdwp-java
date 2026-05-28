package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the filesystem-scan source of {@link LocalProjectClasspathProvider}. Pins the
 * inclusive depth-5 boundary, the {@code SKIP_DIRS} blacklist, the "module classes regardless of
 * sub-modules" probe, and the independence of {@code target/classes} from {@code target/test-classes}.
 */
@DisplayName("LocalProjectClasspathProvider — filesystem scan for target/classes")
class LocalProjectClasspathProviderFilesystemScanTest {

	@Test
	@DisplayName("picks up target/classes and target/test-classes at the working-directory root")
	void shouldPickUpTargetClassesAtCwdRoot(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target/classes"));
		Files.createDirectories(tmp.resolve("target/test-classes"));

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		final Set<String> entries = provider.discover();

		assertThat(entries).contains(
			tmp.resolve("target/classes").toString(),
			tmp.resolve("target/test-classes").toString()
		);
	}

	@Test
	@DisplayName("walks into reactor modules up to depth 5; entries deeper than depth 5 are excluded")
	void shouldWalkIntoReactorModulesUpToDepthFive(@TempDir Path tmp) throws Exception {
		// Real-world reactors are deeper than 3 — e.g. parent/group/subgroup/module/target/classes
		// is depth 4 from CWD. Depth 5 matches the harvester and covers the layouts seen in
		// projects like Camel / Hadoop / Eclipse. Cost stays bounded by SKIP_DIRS.
		Files.createDirectories(tmp.resolve("module-a/target/classes"));
		Files.createDirectories(tmp.resolve("group/subgroup/module/target/classes"));
		// depth 6 — must NOT be picked up to avoid runaway scans
		Files.createDirectories(tmp.resolve("a/b/c/d/e/module/target/classes"));

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		final Set<String> entries = provider.discover();

		assertThat(entries)
			.contains(tmp.resolve("module-a/target/classes").toString())
			.contains(tmp.resolve("group/subgroup/module/target/classes").toString())
			.doesNotContain(tmp.resolve("a/b/c/d/e/module/target/classes").toString());
	}

	/**
	 * Boundary test that nails the INCLUSIVE side of MAX_SCAN_DEPTH = 5: with the scan starting at
	 * depth 0 on the CWD itself and {@code depth == MAX_SCAN_DEPTH} permitted to probe but not
	 * recurse, a target/classes whose module directory is 5 levels under CWD is the deepest one
	 * the scanner is required to find. The complementary "depth 6" test pins exclusion just past
	 * the boundary so a future off-by-one tightening (`>` → `>=`) trips immediately.
	 */
	@Test
	@DisplayName("picks up target/classes at the inclusive depth-5 boundary")
	void shouldPickUpTargetClassesAtInclusiveDepthFiveBoundary(@TempDir Path tmp) throws Exception {
		// tmp (depth 0) → a (1) → b (2) → c (3) → d (4) → e (5) → target/classes probed here.
		final Path edge = tmp.resolve("a/b/c/d/e/target/classes");
		Files.createDirectories(edge);

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		assertThat(provider.discover()).contains(edge.toString());
	}

	@Test
	@DisplayName("skips hidden directories and noise directories on the scan path")
	void shouldSkipHiddenAndNoiseDirectories(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("module-a/target/classes"));
		Files.createDirectories(tmp.resolve(".git/target/classes"));
		Files.createDirectories(tmp.resolve("node_modules/foo/target/classes"));

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		assertThat(provider.discover())
			.contains(tmp.resolve("module-a/target/classes").toString())
			.doesNotContain(tmp.resolve(".git/target/classes").toString())
			.doesNotContain(tmp.resolve("node_modules/foo/target/classes").toString());
	}

	/**
	 * Parameterised sweep across every {@code SKIP_DIRS} entry. Even though three of these names
	 * also start with `.` and would be filtered by the hidden-name guard, the scan code is
	 * documented to honour SKIP_DIRS independently. Pinning each name keeps the rule observable.
	 */
	@ParameterizedTest(name = "skips ''{0}'' subtree even when it contains a valid target/classes")
	@ValueSource(strings = {".git", ".idea", ".vscode", "node_modules", ".gradle", ".mvn"})
	@DisplayName("every SKIP_DIRS entry hides target/classes beneath it from the scan")
	void shouldSkipEverySkipDirsEntry(String skipDir, @TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve(skipDir + "/module/target/classes"));
		Files.createDirectories(tmp.resolve("visible/target/classes"));

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		final Set<String> entries = provider.discover();

		assertThat(entries)
			.contains(tmp.resolve("visible/target/classes").toString())
			.doesNotContain(tmp.resolve(skipDir + "/module/target/classes").toString());
	}

	/**
	 * The scanner skips nested {@code target/} directories — once we enter a {@code target/} we
	 * never recurse into a {@code target/foo/target/classes}. Anything nested inside the build
	 * output belongs to Maven and must not contribute to the project's source classpath.
	 */
	@Test
	@DisplayName("does not recurse into a nested target/ inside another target/")
	void shouldNotRecurseIntoNestedTargetInsideTarget(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target/classes"));
		Files.createDirectories(tmp.resolve("target/sub/target/classes"));

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		final Set<String> entries = provider.discover();

		assertThat(entries).contains(tmp.resolve("target/classes").toString());
		assertThat(entries).doesNotContain(tmp.resolve("target/sub/target/classes").toString());
	}

	/**
	 * A module with only {@code target/classes} (no {@code target/test-classes}) must still
	 * contribute its main classes — and vice versa. The two probes are independent so a
	 * test-classes-only module (e.g. a fixtures jar) is also picked up.
	 */
	@Test
	@DisplayName("target/classes and target/test-classes contribute independently")
	void shouldContributeTargetClassesAndTestClassesIndependently(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("main-only/target/classes"));
		Files.createDirectories(tmp.resolve("test-only/target/test-classes"));

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		final Set<String> entries = provider.discover();

		assertThat(entries)
			.contains(tmp.resolve("main-only/target/classes").toString())
			.contains(tmp.resolve("test-only/target/test-classes").toString())
			.doesNotContain(tmp.resolve("main-only/target/test-classes").toString())
			.doesNotContain(tmp.resolve("test-only/target/classes").toString());
	}
}

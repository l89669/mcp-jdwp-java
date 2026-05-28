package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProcessBuilderMavenRunner}: the {@code .jdwp-mcp-classpath} harvester (parent
 * must be {@code target/}, content split on the host path separator, files never deleted), the
 * non-zero exit propagation, and Windows-specific wrapper preference.
 */
@DisplayName("ProcessBuilderMavenRunner — harvest, no-delete, exit propagation")
class ProcessBuilderMavenRunnerTest {

	@Test
	@DisplayName("only harvests .jdwp-mcp-classpath files whose direct parent is a target/ directory")
	void shouldOnlyHarvestOutputFilesUnderTargetParent(@TempDir Path tmp) throws Exception {
		// Per the file-safety policy, the harvester accepts a candidate ONLY when its direct
		// parent directory is named `target/`. Maven writes there because mdep.outputFile is
		// `target/.jdwp-mcp-classpath`. Anything else is NOT ours and must be ignored.
		Files.createDirectories(tmp.resolve("target"));
		Files.writeString(tmp.resolve("target/.jdwp-mcp-classpath"),
			"/m2/foo.jar:/m2/bar.jar");
		Files.createDirectories(tmp.resolve("module-a/target"));
		Files.writeString(tmp.resolve("module-a/target/.jdwp-mcp-classpath"),
			"/m2/baz.jar:/m2/foo.jar"); // overlap — must dedupe

		// SAFETY: a file with the same name OUTSIDE a target/ dir (e.g. left over from a previous
		// buggy run, or a name collision with something the user has) MUST be ignored.
		Files.writeString(tmp.resolve(".jdwp-mcp-classpath"),
			"/should/not/be/harvested.jar");

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result).containsExactlyInAnyOrder("/m2/foo.jar", "/m2/bar.jar", "/m2/baz.jar");
		assertThat(result).doesNotContain("/should/not/be/harvested.jar");
	}

	@Test
	@DisplayName("does not delete harvested files (re-runs of Maven overwrite them in place)")
	void shouldNotDeleteHarvestedFiles(@TempDir Path tmp) throws Exception {
		// File-safety invariant: the runner reads but never deletes. Re-runs of Maven overwrite
		// the file in place; `mvn clean` is the only thing that removes it.
		Files.createDirectories(tmp.resolve("target"));
		final Path output = tmp.resolve("target/.jdwp-mcp-classpath");
		Files.writeString(output, "/m2/foo.jar");

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);
		runner.run(List.of("echo"), tmp, 10);

		assertThat(Files.exists(output))
			.as("Harvester must NEVER delete files — see file-safety policy")
			.isTrue();
	}

	@Test
	@DisplayName("returns empty list when the command executor reports a non-zero exit code")
	void shouldReturnEmptyListWhenMavenExitsNonzero(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));
		Files.writeString(tmp.resolve("target/.jdwp-mcp-classpath"), "/m2/foo.jar");
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 1);

		final List<String> result = runner.run(List.of("false"), tmp, 10);

		assertThat(result).isEmpty();
	}

	/**
	 * When the {@link ProcessBuilderMavenRunner.CommandExecutor} lambda throws
	 * {@link InterruptedException}, the executor must call {@code destroyForcibly()} on the spawned
	 * process before propagating, otherwise the child process is leaked.
	 */
	@Test
	@DisplayName("destroys the spawned process when waitFor is interrupted")
	void shouldDestroyProcessOnInterrupt(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));
		// Stage a sentinel that we can use to confirm the runner did NOT manage to read the
		// output file (because the executor threw before harvest).
		Files.writeString(tmp.resolve("target/.jdwp-mcp-classpath"), "/sentinel.jar");

		final AtomicReference<Process> spawned = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> {
			final Process p = new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
			spawned.set(p);
			throw new InterruptedException("simulated interrupt during waitFor");
		});

		// Run shouldn't blow up at the call site — the runner is documented to swallow the
		// exception, log a WARN, and return an empty list. The bug is in the leak, not the API.
		final List<String> result = runner.run(List.of("sleep", "30"), tmp, 10);

		assertThat(result).isEmpty();
		assertThat(spawned.get())
			.as("Spawned process must not be leaked when waitFor is interrupted")
			.isNotNull();
		assertThat(spawned.get().isAlive())
			.as("Spawned process must be destroyed on interrupt — currently leaked")
			.isFalse();
	}

	/**
	 * {@link Files#walk(Path, int, java.nio.file.FileVisitOption...)} descends into every directory
	 * up to the depth limit. The harvester must skip non-Maven trees ({@code node_modules},
	 * {@code .git}, {@code .idea}, etc.) so a stray
	 * {@code node_modules/foo/target/.jdwp-mcp-classpath} (e.g. a packaged JS module shipping its own
	 * Maven artifact) is NOT picked up.
	 */
	@Test
	@DisplayName("does not harvest .jdwp-mcp-classpath files under SKIP_DIRS (e.g. node_modules)")
	void shouldSkipNodeModulesDuringHarvest(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));
		Files.writeString(tmp.resolve("target/.jdwp-mcp-classpath"), "/real/m2/foo.jar");
		Files.createDirectories(tmp.resolve("node_modules/foo/target"));
		Files.writeString(tmp.resolve("node_modules/foo/target/.jdwp-mcp-classpath"),
			"/from/node_modules/should-not-be-harvested.jar");

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result)
			.contains("/real/m2/foo.jar")
			.doesNotContain("/from/node_modules/should-not-be-harvested.jar");
	}

	/**
	 * On Windows the wrapper script is {@code mvnw.cmd}, not {@code mvnw}. The provider must probe
	 * for both, preferring {@code mvnw} (Unix) when present and falling back to {@code mvnw.cmd} on
	 * Windows hosts. This test is meaningful only on a real Windows runner — the
	 * {@link EnabledOnOs} skips it elsewhere.
	 */
	@Test
	@DisplayName("prefers mvnw.cmd on Windows when no Unix mvnw wrapper exists")
	@EnabledOnOs(OS.WINDOWS)
	void shouldPreferMvnwCmdOnWindows(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final Path mvnwCmd = tmp.resolve("mvnw.cmd");
		Files.writeString(mvnwCmd, "@echo off\r\n");

		final AtomicReference<List<String>> capturedCmd = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> { capturedCmd.set(cmd); return List.of(); }
		);
		provider.discover();

		assertThat(capturedCmd.get().get(0)).endsWith("mvnw.cmd");
	}
}

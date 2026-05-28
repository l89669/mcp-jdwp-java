package one.edee.mcp.jdwp.evaluation;

import java.nio.file.Path;
import java.util.List;

/**
 * Shared scaffolding for tests that construct a {@link LocalProjectClasspathProvider}. Centralises
 * the "no-op" provider — an instance with a working directory that is guaranteed to contain neither
 * a {@code pom.xml} nor a {@code target/} tree, an env lookup that always returns {@code null}, and
 * a Maven runner that returns an empty list without ever shelling out. Used by every evaluator-side
 * test that only needs the provider's *shape* rather than its real I/O behaviour.
 */
final class LocalProjectClasspathProviderTestSupport {

	private LocalProjectClasspathProviderTestSupport() {
	}

	/**
	 * Returns a deterministic provider that contributes zero entries from every source. Working
	 * directory is the JVM's temp dir — guaranteed to exist but neither a Maven project nor a
	 * reactor with {@code target/classes} sub-trees, so the filesystem and Maven sources are
	 * structurally silent.
	 */
	static LocalProjectClasspathProvider noOpProvider() {
		return new LocalProjectClasspathProvider(
			Path.of(System.getProperty("java.io.tmpdir")),
			name -> null,
			(command, workingDirectory, timeoutSeconds) -> List.of()
		);
	}
}

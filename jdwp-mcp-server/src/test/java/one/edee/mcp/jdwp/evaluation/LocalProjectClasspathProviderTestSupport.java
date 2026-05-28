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
	 * directory is a guaranteed-non-existent path so the depth-5 filesystem scan short-circuits
	 * at the first {@code isDirectory} probe — keeps tests fast and isolated from whatever happens
	 * to live under {@code java.io.tmpdir} on the running host (CI machines sometimes contain
	 * unrelated {@code target/classes} trees there).
	 */
	static LocalProjectClasspathProvider noOpProvider() {
		return new LocalProjectClasspathProvider(
			Path.of("/nonexistent/jdwp-mcp-no-op-" + java.util.UUID.randomUUID()),
			name -> null,
			(command, workingDirectory, timeoutSeconds) -> List.of()
		);
	}
}

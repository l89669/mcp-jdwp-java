package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Computes a local-project classpath used as an additive fallback when remote classloader-based
 * discovery returns an incomplete view of the target VM's classpath (Tomcat / Spring Boot dev-tools
 * / custom URLClassLoaders that hide their JARs from {@code getURLs()}).
 *
 * <p>Three composable sources, evaluated in order. The result preserves insertion order so the
 * first source to contribute an entry wins. The caller is responsible for unioning the returned
 * entries with whatever the remote discovery produced — this provider never consults the target VM.
 * <ul>
 *   <li>{@code JDWP_EXTRA_CLASSPATH} env var, parsed by {@link File#pathSeparator}</li>
 *   <li>Filesystem scan of {@code target/classes} / {@code target/test-classes} under the server CWD</li>
 *   <li>Maven {@code dependency:build-classpath} per detected {@code pom.xml}</li>
 * </ul>
 *
 * <p>The first {@link #discoverWithBreakdown()} call is memoised; subsequent calls return the cached
 * result until {@link #reset()} is invoked (typically on JDI reconnect). Discovery is synchronised
 * so concurrent callers see the same memoised snapshot.
 *
 * <p>Registered as a Spring {@code @Service}, but the second constructor takes seams so the env
 * lookup and Maven invocation can be stubbed in tests without touching real environment variables
 * or shelling out.
 */
@Service
public class LocalProjectClasspathProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalProjectClasspathProvider.class);
    /** Environment variable callers can set to inject extra classpath entries verbatim. */
    private static final String ENV_NAME = "JDWP_EXTRA_CLASSPATH";
    /**
     * Filesystem-scan depth cap. Five levels covers reactor / module / sub-module layouts without
     * walking into Node / Gradle / IDE caches that an unbounded walk would otherwise stat-flood.
     */
    private static final int MAX_SCAN_DEPTH = 5;
    /**
     * Cold-cache {@code mvn} may download dependencies on first run, which routinely takes 1-3
     * minutes on a fresh machine. 180s covers the common case; a real timeout still logs a clear
     * WARN with the captured stdout so the user knows what to retry.
     */
    private static final int MAVEN_TIMEOUT_SECONDS = 180;
    /**
     * Directory names the filesystem scan must never descend into — VCS, IDE, package-manager
     * caches, and {@code target} itself (we look at it explicitly and never recurse). Without the
     * list a {@code target/} hit in {@code node_modules} would balloon I/O and yield junk entries.
     */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".idea", ".vscode", "node_modules", ".gradle", ".mvn",
        "target" // skip target itself — we look at it explicitly, never recurse into it
    );

    /**
     * Subset of {@link #SKIP_DIRS} that other components (notably the Maven-output harvester) must
     * also skip when walking the project tree. {@code target} is excluded here because the harvester
     * specifically looks for files whose direct parent is {@code target/} — that gate handles the
     * "skip nested target dirs we shouldn't descend into" concern without needing this list.
     */
    static final Set<String> HARVEST_SKIP_DIRS = Set.of(
        ".git", ".idea", ".vscode", "node_modules", ".gradle", ".mvn"
    );

    /**
     * Per-source breakdown of the most recent discovery; exposed by {@code jdwp_diagnose}.
     *
     * @param envOverride number of entries contributed by {@code JDWP_EXTRA_CLASSPATH}
     * @param filesystem  number of {@code target/classes} / {@code target/test-classes} entries the
     *                    filesystem scan added on top of the env override
     * @param maven       number of entries Maven's {@code dependency:build-classpath} added on top
     *                    of env + filesystem (dedup'd against earlier sources)
     * @param all         insertion-ordered union of all contributing entries; never null
     */
    public record Breakdown(int envOverride, int filesystem, int maven, Set<String> all) {}

    /** Root the scan is anchored at; usually the MCP server's launch directory. */
    private final Path workingDirectory;
    /** Indirection for {@link System#getenv(String)} so tests can drive the env-override branch. */
    private final Function<String, @Nullable String> envLookup;
    /** Indirection for the Maven shell-out so tests can stub success/failure modes. */
    private final MavenRunner mavenRunner;
    /** Memoised result of the first {@link #discoverWithBreakdown()} call; cleared by {@link #reset()}. */
    private @Nullable Breakdown cachedBreakdown;

    /**
     * Test seam: build the provider with explicit working directory, env-lookup function, and Maven
     * runner. Spring wires the no-seam constructor in production — tests use this one to drive every
     * code path without touching real environment variables or shelling out.
     */
    public LocalProjectClasspathProvider(Path workingDirectory,
                                         Function<String, @Nullable String> envLookup,
                                         MavenRunner mavenRunner) {
        this.workingDirectory = workingDirectory;
        this.envLookup = envLookup;
        this.mavenRunner = mavenRunner;
    }

    /**
     * Default Spring wiring: uses the JVM's launch directory as the working directory and the real
     * process environment for the env-var lookup. The {@link ProcessBuilderMavenRunner} is injected
     * so Maven invocations go through the real shell-out path in production.
     */
    @Autowired
    public LocalProjectClasspathProvider(ProcessBuilderMavenRunner mavenRunner) {
        // `user.dir` is documented as always-present on a normal JVM launch, but a sandboxed
        // / restricted launch can drop it. Defaulting to "." rather than letting Path.of(null)
        // NPE keeps the bean constructible — the scan then runs against the process's effective
        // working directory, which is the same place "." would resolve to anyway.
        this(Path.of(Objects.toString(System.getProperty("user.dir"), ".")), System::getenv, mavenRunner);
    }

    /**
     * Returns the union of all contributing entries from the memoised breakdown. Convenience
     * wrapper around {@link #discoverWithBreakdown()} for callers that only need the merged set.
     *
     * @return insertion-ordered, immutable set of local classpath entries
     */
    public Set<String> discover() {
        return discoverWithBreakdown().all();
    }

    /**
     * Working directory the provider is rooted at — exposed for the {@code jdwp_diagnose} report so
     * operators see the absolute path the scan started from without re-reading {@code user.dir}.
     */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * True when a {@code pom.xml} sits at the working-directory root. Cheap I/O; called only from
     * the diagnose path (once per call). Surfaced so the report can say "pom.xml: yes/no" without
     * duplicating the path logic.
     */
    public boolean hasPomAtRoot() {
        return Files.isRegularFile(workingDirectory.resolve("pom.xml"));
    }

    /**
     * Non-blocking peek at the cached breakdown. Returns {@code null} when no discovery has run
     * yet on this provider instance. Used by the diagnose path so the report never blocks the
     * caller waiting for a cold-cache Maven invocation; if the cache is cold, the diagnose
     * renderer prints a one-line "not yet computed" hint and moves on.
     */
    public synchronized @Nullable Breakdown peekCachedBreakdown() {
        return cachedBreakdown;
    }

    /**
     * Reports whether the {@code JDWP_EXTRA_CLASSPATH} env var is present in the lookup, regardless
     * of whether it parses to any entries. The diagnose path uses this to distinguish three states:
     * <ul>
     *   <li>{@code (unset)} — the variable is absent from the environment</li>
     *   <li>{@code (set, no entries)} — set but blank or whitespace-only</li>
     *   <li>{@code (set)} — set with at least one entry</li>
     * </ul>
     * Operators chase a frustrating false-negative when "set-but-blank" is rendered the same as
     * "unset". Exposing presence separately fixes the conflation without touching the breakdown.
     */
    public boolean isEnvOverrideSet() {
        return envLookup.apply(ENV_NAME) != null;
    }

    /**
     * Reports whether the {@code JDWP_EXTRA_CLASSPATH} env var has any non-blank entries after
     * splitting on the host {@link File#pathSeparator}. Lets the diagnose path render the three-way
     * "set, no entries" state on a cold cache without triggering full discovery — the answer
     * depends only on the env var, not on the filesystem or Maven.
     */
    public boolean envOverrideHasEntries() {
        final String raw = envLookup.apply(ENV_NAME);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (String part : raw.split(Pattern.quote(File.pathSeparator), -1)) {
            if (!part.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs (or returns the memoised result of) the three-source discovery. Synchronised so two
     * concurrent callers cannot both pay the Maven invocation cost; the second caller blocks and
     * receives the first caller's cached breakdown.
     *
     * <p>On an empty result, emits one INFO log line that explains which sources had what state
     * (env unset vs blank, {@code target/} present, {@code pom.xml} present) so operators can
     * diagnose "why is nothing being added" without enabling DEBUG.
     *
     * @return memoised breakdown — never null; {@link Breakdown#all()} is immutable
     */
    public synchronized Breakdown discoverWithBreakdown() {
        final Breakdown cached = cachedBreakdown;
        if (cached != null) {
            return cached;
        }
        final Set<String> entries = new LinkedHashSet<>();
        addEnvOverride(entries);
        final int afterEnv = entries.size();
        addFilesystemScan(entries);
        final int afterFs = entries.size();
        addMavenDependencies(entries);
        final int afterMaven = entries.size();
        final Breakdown breakdown = new Breakdown(
            afterEnv,
            afterFs - afterEnv,
            afterMaven - afterFs,
            // Wrap a defensive LinkedHashSet copy so the cached view is immutable but still
            // preserves insertion order — callers rely on containsExactly-style assertions and
            // Set.copyOf would drop order (it returns a hash-based immutable set).
            Collections.unmodifiableSet(new LinkedHashSet<>(entries))
        );
        cachedBreakdown = breakdown;
        if (entries.isEmpty()) {
            // Required by the logging policy: ONE INFO line on empty result explaining the why.
            log.info("[LocalClasspath] discover() found 0 entries — env={}, fs={}, maven={} (cwd={}, pom.xml={})",
                envLookup.apply(ENV_NAME) == null ? "unset" : "set-but-empty",
                Files.isDirectory(workingDirectory.resolve("target")) ? "target/-present" : "no-target/",
                Files.isRegularFile(workingDirectory.resolve("pom.xml")) ? "pom-present" : "no-pom",
                workingDirectory,
                Files.isRegularFile(workingDirectory.resolve("pom.xml")));
        }
        return breakdown;
    }

    /**
     * Clears the memoised breakdown so the next {@link #discoverWithBreakdown()} call re-scans.
     * Called by {@link JdiExpressionEvaluator#configureCompilerClasspath} on a JDI reconnect because
     * the new connection may target a different project layout — a stale breakdown would mismatch.
     */
    public synchronized void reset() {
        cachedBreakdown = null;
        log.debug("[LocalClasspath] Cache reset");
    }

    /**
     * Adds Maven-resolved dependency jars to {@code entries}. No-ops cleanly when no {@code pom.xml}
     * sits at the working-directory root. Logs at INFO with the count and elapsed time on every
     * outcome (added &gt; 0, added == 0, failure) so operators see one line per discovery.
     */
    private void addMavenDependencies(Set<String> entries) {
        if (!Files.isRegularFile(workingDirectory.resolve("pom.xml"))) {
            log.debug("[LocalClasspath] No pom.xml under {} — skipping Maven source", workingDirectory);
            return;
        }
        final List<String> command = buildMavenCommand();
        final long startTime = System.currentTimeMillis();
        try {
            final List<String> jars = mavenRunner.run(command, workingDirectory, MAVEN_TIMEOUT_SECONDS);
            final int before = entries.size();
            entries.addAll(jars);
            final int added = entries.size() - before;
            final long elapsed = System.currentTimeMillis() - startTime;
            if (added > 0) {
                log.info("[LocalClasspath] Maven contributed {} dependency jars in {}ms", added, elapsed);
            } else {
                // "Reason-out on empty" — required by the logging policy. The runner already logs the
                // shell-level failure; this line gives an operator the user-facing context.
                log.info("[LocalClasspath] Maven contributed 0 jars in {}ms — check earlier WARN lines "
                    + "for the cause (timeout, non-zero exit, missing output files)", elapsed);
            }
        } catch (Exception e) {
            log.warn("[LocalClasspath] Maven dependency:build-classpath failed after {}ms: {}: {} (cwd={})",
                System.currentTimeMillis() - startTime, e.getClass().getSimpleName(), e.getMessage(),
                workingDirectory);
        }
    }

    /**
     * Builds the {@code dependency:build-classpath} command line, picking up the project's Maven
     * wrapper when available (see {@link #resolveMavenExecutable}). The output file lives under each
     * module's {@code target/}, never at a bare project-root filename.
     */
    private List<String> buildMavenCommand() {
        final String executable = resolveMavenExecutable();
        // `-q` keeps Maven CLI noise off the operator's terminal; the runner captures stdout to a
        // size-capped buffer so the WARN path can still surface diagnostic output on failure.
        // `-DincludeScope=runtime` matches what's actually on the app's runtime classpath.
        // The output file lives under each module's `target/` — Maven-owned build output, gitignored
        // everywhere, wiped by `mvn clean`. NEVER use a bare filename here (`.jdwp-mcp-classpath`):
        // that would land in project source directories which we must not write to. See the
        // file-safety policy.
        // We do NOT pass `-Dmdep.pathSeparator=:` — that flag forces a Unix separator on Windows and
        // would corrupt drive letters in jar paths. Letting Maven use the platform default and
        // splitting on File.pathSeparator on the read side keeps things consistent.
        return List.of(
            executable,
            "-q",
            "dependency:build-classpath",
            "-DincludeScope=runtime",
            "-Dmdep.outputFile=target/.jdwp-mcp-classpath"
        );
    }

    /**
     * Picks the Maven executable in priority order: {@code mvnw} (Unix wrapper) when executable,
     * {@code mvnw.cmd} (Windows wrapper) when running on Windows and the file exists, then plain
     * {@code mvn} from the PATH. {@code mvnw.cmd} cannot be probed with
     * {@link Files#isExecutable(Path)} on Windows because the NTFS execute bit isn't a thing —
     * {@link Files#isRegularFile(Path, LinkOption...)} is the right gate.
     */
    private String resolveMavenExecutable() {
        final Path mvnw = workingDirectory.resolve("mvnw");
        if (Files.isExecutable(mvnw)) {
            return mvnw.toAbsolutePath().toString();
        }
        if (isWindows()) {
            final Path mvnwCmd = workingDirectory.resolve("mvnw.cmd");
            if (Files.isRegularFile(mvnwCmd)) {
                return mvnwCmd.toAbsolutePath().toString();
            }
        }
        return "mvn";
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    /**
     * Walks the working directory for {@code target/classes} / {@code target/test-classes} dirs and
     * adds each as an entry. Always emits one INFO line summarising the count and elapsed time so
     * operators can see whether the scan contributed or not without enabling DEBUG.
     */
    private void addFilesystemScan(Set<String> entries) {
        final long startTime = System.currentTimeMillis();
        final int before = entries.size();
        scanForClassDirs(workingDirectory, 0, entries);
        final int added = entries.size() - before;
        final long elapsed = System.currentTimeMillis() - startTime;
        if (added > 0) {
            log.info("[LocalClasspath] Filesystem scan contributed {} target/classes entries in {}ms",
                added, elapsed);
        } else {
            log.info("[LocalClasspath] Filesystem scan found 0 target/classes under {} (depth <= {}, {}ms)",
                workingDirectory, MAX_SCAN_DEPTH, elapsed);
        }
    }

    /**
     * Recursively probes {@code dir} (and descendants, up to {@link #MAX_SCAN_DEPTH}) for
     * {@code target/classes} / {@code target/test-classes}. Skips {@link #SKIP_DIRS} and
     * dot-prefixed directories. Tolerates per-directory failures (logged at WARN) rather than
     * aborting — a partial classpath beats no classpath.
     */
    private static void scanForClassDirs(Path dir, int depth, Set<String> entries) {
        // NOFOLLOW_LINKS everywhere: a symlink to another tree on the same filesystem could create
        // walk cycles or pull in jars from outside the project. The documented invariant is that
        // this scan never follows links — enforce it at every probe.
        if (depth > MAX_SCAN_DEPTH || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // At each directory, probe for target/classes and target/test-classes BEFORE recursing — we
        // want a module's classes whether or not it has child modules.
        final Path classes = dir.resolve("target/classes");
        if (Files.isDirectory(classes, LinkOption.NOFOLLOW_LINKS)) {
            entries.add(classes.toString());
        }
        final Path testClasses = dir.resolve("target/test-classes");
        if (Files.isDirectory(testClasses, LinkOption.NOFOLLOW_LINKS)) {
            entries.add(testClasses.toString());
        }
        if (depth == MAX_SCAN_DEPTH) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            children
                // `Files::isDirectory` (the method reference) FOLLOWS links — use the explicit
                // overload with NOFOLLOW_LINKS so a symlinked directory is treated as "not a
                // directory" and skipped.
                .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> {
                    final String name = p.getFileName().toString();
                    return !name.startsWith(".") && !SKIP_DIRS.contains(name);
                })
                .forEach(child -> scanForClassDirs(child, depth + 1, entries));
        } catch (AccessDeniedException e) {
            // Permission denied on a single directory is expected (CI agents, restricted dev envs).
            // Log at WARN so an operator sees it once, but never abort the whole scan.
            log.warn("[LocalClasspath] Permission denied listing {} — skipping subtree", dir);
        } catch (Exception e) {
            // Any other failure on a single directory is unexpected — log it visibly. We do NOT
            // throw: a partial classpath is better than no classpath, and the agent will see the
            // diagnose breakdown to understand what happened.
            log.warn("[LocalClasspath] Could not list {}: {}: {}",
                dir, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Parses {@link #ENV_NAME} (when set) on the host {@link File#pathSeparator} and adds each
     * non-blank, non-duplicate entry. Logs at INFO with the added count; at DEBUG when the variable
     * is unset or blank.
     */
    private void addEnvOverride(Set<String> entries) {
        final String raw = envLookup.apply(ENV_NAME);
        if (raw == null || raw.isBlank()) {
            log.debug("[LocalClasspath] {} is unset or blank — env override contributed 0 entries", ENV_NAME);
            return;
        }
        // Split on the HOST OS path separator. A regex like `[;:]` is WRONG on Windows because
        // `C:\foo` contains a colon — splitting would shred the drive letter. The env var is set
        // by the user on the host, so the host's File.pathSeparator is the correct delimiter.
        final String[] parts = raw.split(Pattern.quote(File.pathSeparator), -1);
        int added = 0;
        for (String part : parts) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty() && entries.add(trimmed)) {
                added++;
            }
        }
        log.info("[LocalClasspath] {} contributed {} entries", ENV_NAME, added);
    }

    /**
     * Seam for executing a Maven command and harvesting its output. The production implementation
     * is {@link ProcessBuilderMavenRunner}; tests provide in-memory stubs.
     *
     * <p>Implementations MUST follow the empty-list-on-failure contract: any non-zero exit,
     * timeout, exception, or interruption is reflected by returning an empty list (after logging
     * the cause). This keeps the provider's outer flow declarative — it can union the result with
     * other sources without per-source null/error handling.
     */
    @FunctionalInterface
    public interface MavenRunner {
        /**
         * Runs {@code command} with {@code workingDirectory} as the process CWD, bounded by
         * {@code timeoutSeconds}, and returns the classpath entries the command's output files
         * contributed.
         *
         * @param command          full argv (executable + arguments) to invoke
         * @param workingDirectory CWD for the spawned process; output files are harvested under it
         * @param timeoutSeconds   wall-clock budget; on timeout the implementation MUST kill the
         *                         process and return an empty list
         * @return harvested classpath entries; empty on any failure (never null)
         */
        List<String> run(List<String> command, Path workingDirectory, int timeoutSeconds);
    }
}

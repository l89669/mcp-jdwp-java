package one.edee.mcp.jdwp.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Production {@link LocalProjectClasspathProvider.MavenRunner} that shells out to {@code mvn} (or
 * the project's Maven wrapper) via {@link ProcessBuilder} and harvests the per-module
 * {@code .jdwp-mcp-classpath} files Maven writes under each module's {@code target/}.
 *
 * <p>Responsibility boundary: this class owns the process invocation, timeout enforcement, stdout
 * draining, and output-file collection. It NEVER deletes files — {@code target/} is Maven-owned
 * and gets wiped by {@code mvn clean}; arbitrary deletion in the project tree is prohibited by the
 * file-safety policy. Honours the {@link LocalProjectClasspathProvider.MavenRunner} empty-list-on-failure
 * contract: every error path returns {@link List#of()} after logging.
 *
 * <p>Thread model: each {@link #run} invocation spawns ONE daemon platform thread named
 * {@code jdwp-mcp-maven-stdout-drainer} to drain the child process's merged stdout/stderr stream.
 * The drainer terminates when the child closes its pipe (i.e. when the process exits or is forcibly
 * destroyed), so its lifetime is bounded by the process. Platform (not virtual) threads are used
 * because the project targets Java 17.
 */
@Service
public class ProcessBuilderMavenRunner implements LocalProjectClasspathProvider.MavenRunner {

    /** Filename Maven writes the classpath into under each module's {@code target/}. */
    private static final String OUTPUT_FILE_NAME = ".jdwp-mcp-classpath";
    /** Upper bound on captured child stdout/stderr included in the WARN log on failure. */
    private static final int STDOUT_CAPTURE_BYTES = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(ProcessBuilderMavenRunner.class);

    /** Seam for the actual process execution; replaced in tests to drive timeout / failure paths. */
    private final CommandExecutor commandExecutor;

    /** Default Spring wiring — uses the real {@link ProcessBuilder}-based command executor. */
    public ProcessBuilderMavenRunner() {
        this(ProcessBuilderMavenRunner::executeRealCommand);
    }

    /** Test seam: swap in a stub {@link CommandExecutor} to drive success, failure, and timeout. */
    ProcessBuilderMavenRunner(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    @Override
    public List<String> run(List<String> command, Path workingDirectory, int timeoutSeconds) {
        // Snapshot any pre-existing descendant PIDs so the cleanup below only targets processes
        // that this call spawned. Without the snapshot, an InterruptedException from the executor
        // would either leak the spawned child (current behaviour) or, worse, race against unrelated
        // descendant processes started for some other reason.
        final Set<Long> preExistingDescendants = ProcessHandle.current().descendants()
            .map(ProcessHandle::pid)
            .collect(Collectors.toSet());
        try {
            final int exitCode = commandExecutor.execute(new CommandRequest(command, workingDirectory, timeoutSeconds));
            // executeRealCommand already logs WARN with the captured stdout on non-zero/timeout;
            // no second log line here.
            if (exitCode != 0) {
                return List.of();
            }
            return harvestOutputFiles(workingDirectory);
        } catch (InterruptedException ie) {
            // Restore the interrupt flag so callers higher up the stack can observe the cancellation.
            // Then destroy any descendant process this call spawned — the child holds an OS pipe
            // open and would block any caller that later tries to drain stdout. Targeted at PIDs
            // new since the snapshot taken before the executor call.
            destroyNewDescendants(preExistingDescendants);
            Thread.currentThread().interrupt();
            log.warn("[LocalClasspath] Maven invocation interrupted (cwd={}); destroyed spawned descendants",
                workingDirectory);
            return List.of();
        } catch (Exception e) {
            log.warn("[LocalClasspath] Maven invocation failed: {}: {} (cwd={})",
                e.getClass().getSimpleName(), e.getMessage(), workingDirectory);
            return List.of();
        }
    }

    private static void destroyNewDescendants(Set<Long> preExistingPids) {
        // Snapshot the new descendants up-front so we can both issue destroy AND await termination
        // before returning. Issuing destroy without awaiting leaves the caller racing the OS — a
        // test that checks `isAlive()` immediately after run() can flake. 2s is enough for SIGKILL
        // to land on a sleep(1)-style process on any sane kernel.
        final List<ProcessHandle> newDescendants = ProcessHandle.current().descendants()
            .filter(h -> !preExistingPids.contains(h.pid()))
            .toList();
        newDescendants.forEach(ProcessHandle::destroyForcibly);
        for (ProcessHandle h : newDescendants) {
            try {
                h.onExit().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best-effort: a stuck/orphaned process is logged elsewhere; we already issued
                // destroyForcibly. The caller still gets a timely return.
            }
        }
    }

    /**
     * Walks {@code root} (depth 5, covering {@code <root>/<group>/<module>/target/<file>}) for
     * Maven-written classpath files and aggregates their entries.
     *
     * <p><b>File-safety invariant.</b> A candidate file is accepted ONLY if BOTH conditions hold:
     * its direct parent directory is named {@code target} AND no ancestor segment between
     * {@code root} and the candidate is in {@link LocalProjectClasspathProvider#HARVEST_SKIP_DIRS}.
     * This gate keeps the harvester confined to Maven-owned build output even on projects that
     * happen to contain a stray file with the matching name elsewhere in the tree. The harvester
     * NEVER deletes; the next Maven run overwrites the file in place and {@code mvn clean} removes
     * it.
     */
    private static List<String> harvestOutputFiles(Path root) throws IOException {
        final Set<String> entries = new LinkedHashSet<>();
        // File-safety: ONLY accept candidates whose direct parent directory is named `target`.
        // Maven-owned build output, gitignored, wiped by `mvn clean`. A bare match on file name
        // anywhere in the tree would risk picking up a file we did not create — see the
        // file-safety policy. Depth-5 covers: <root>/<group>/<module>/target/<file>.
        try (Stream<Path> walk = Files.walk(root, 5)) {
            walk.filter(p -> p.getFileName() != null
                          && OUTPUT_FILE_NAME.equals(p.getFileName().toString())
                          && p.getParent() != null
                          && p.getParent().getFileName() != null
                          && "target".equals(p.getParent().getFileName().toString())
                          && !hasSkippedAncestor(root, p))
                .forEach(file -> {
                    try {
                        final String content = Files.readString(file).trim();
                        if (content.isEmpty()) {
                            log.debug("[LocalClasspath] Output file {} was empty", file);
                            return;
                        }
                        int parsed = 0;
                        // Use the HOST File.pathSeparator: Maven wrote this file on the host, so
                        // its entries are separated by the host's separator. Splitting on `[;:]`
                        // would shred `C:\foo` on Windows.
                        final String separator = Pattern.quote(File.pathSeparator);
                        for (String part : content.split(separator, -1)) {
                            final String trimmed = part.trim();
                            if (!trimmed.isEmpty() && entries.add(trimmed)) {
                                parsed++;
                            }
                        }
                        log.debug("[LocalClasspath] Harvested {} new entries from {}", parsed, file);
                        // NO DELETION. The file is in a Maven-owned `target/` directory; the next
                        // Maven run overwrites it in place, and `mvn clean` removes it. Deleting
                        // arbitrary files in the project tree is forbidden by the file-safety policy.
                    } catch (IOException e) {
                        log.warn("[LocalClasspath] Could not read classpath output {}: {}: {}",
                            file, e.getClass().getSimpleName(), e.getMessage());
                    }
                });
        }
        return new ArrayList<>(entries);
    }

    /**
     * Rejects candidates whose path between {@code root} (exclusive) and the candidate's parent
     * (inclusive) contains a segment listed in
     * {@link LocalProjectClasspathProvider#HARVEST_SKIP_DIRS}. Shares the provider's skip list so
     * additions there extend both code paths uniformly.
     */
    private static boolean hasSkippedAncestor(Path root, Path candidate) {
        final Path relative;
        try {
            relative = root.relativize(candidate);
        } catch (IllegalArgumentException e) {
            // Different filesystem roots; cannot relativize. Fall back to skipping the candidate.
            return true;
        }
        // Iterate every segment except the final file name — only directories count as ancestors.
        final int segments = relative.getNameCount();
        for (int i = 0; i < segments - 1; i++) {
            final String name = relative.getName(i).toString();
            if (LocalProjectClasspathProvider.HARVEST_SKIP_DIRS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the Maven command via {@link ProcessBuilder} with stderr merged into stdout. A daemon
     * platform thread drains the merged stream into a {@link #STDOUT_CAPTURE_BYTES}-bounded buffer
     * so the child never blocks on a full pipe and the WARN log on failure includes Maven's actual
     * diagnostic output. Returns the process exit code, or {@code -1} if the wall-clock budget was
     * exceeded (after {@link Process#destroyForcibly()}). On {@link InterruptedException}, kills
     * the child, restores the interrupt flag, and rethrows so the caller can propagate cancellation.
     */
    private static int executeRealCommand(CommandRequest req) throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(req.command())
            .directory(req.workingDirectory().toFile())
            .redirectErrorStream(true);
        final Process process = pb.start();
        // Drain stdout so the process never blocks on a full pipe buffer (a common cause of
        // ProcessBuilder hangs). Capture the first STDOUT_CAPTURE_BYTES into a buffer so the WARN
        // log on non-zero exit / timeout can include Maven's actual diagnostic output. The cap
        // prevents a runaway log volume from a Maven misconfiguration.
        // NOTE: a daemon platform thread is used here rather than a virtual thread because the
        // project targets Java 17 (virtual threads were finalized in Java 21). The semantics are
        // identical: "drain in the background, don't block the process pipe".
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final Thread drainer = new Thread(() -> {
            try (var in = process.getInputStream()) {
                final byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (captured.size() < STDOUT_CAPTURE_BYTES) {
                        captured.write(buf, 0, Math.min(n, STDOUT_CAPTURE_BYTES - captured.size()));
                    }
                    // Continue reading even after the cap so the process never blocks on output.
                }
            } catch (IOException ignored) {}
        }, "jdwp-mcp-maven-stdout-drainer");
        drainer.setDaemon(true);
        drainer.start();
        try {
            if (!process.waitFor(req.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                // Let any in-flight writes from the child reach the drainer before snapshotting
                // the buffer: otherwise the WARN below would print a truncated tail.
                try {
                    drainer.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                log.warn("[LocalClasspath] Maven invocation timed out after {}s. Captured output (up to {} bytes):\n{}",
                    req.timeoutSeconds(), STDOUT_CAPTURE_BYTES,
                    captured.toString(StandardCharsets.UTF_8));
                return -1;
            }
        } catch (InterruptedException ie) {
            // waitFor was interrupted — kill the child, restore the interrupt flag, propagate so
            // the outer run() returns an empty list and surfaces the cancellation to its caller.
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw ie;
        }
        drainer.join(1000);
        if (drainer.isAlive()) {
            // The drainer outlived its budget — a very large Maven output stream or a stuck child
            // still holding the pipe open. We have already collected the exit code, so this is not
            // a correctness issue, but a one-line DEBUG helps anyone investigating slow shutdowns.
            log.debug("[LocalClasspath] stdout drainer still alive after 1s join — leaving as daemon");
        }
        final int exit = process.exitValue();
        if (exit != 0) {
            log.warn("[LocalClasspath] Maven exited with code {}. Captured output (up to {} bytes):\n{}",
                exit, STDOUT_CAPTURE_BYTES,
                captured.toString(StandardCharsets.UTF_8));
        } else {
            log.debug("[LocalClasspath] Maven succeeded ({} bytes of output captured)", captured.size());
        }
        return exit;
    }

    /** Seam for the actual process execution; returns the child's exit code (or {@code -1} on timeout). */
    @FunctionalInterface
    interface CommandExecutor {
        int execute(CommandRequest request) throws Exception;
    }

    /** Bundles the inputs the {@link CommandExecutor} needs so the seam stays a single-arg lambda. */
    record CommandRequest(List<String> command, Path workingDirectory, int timeoutSeconds) {}
}

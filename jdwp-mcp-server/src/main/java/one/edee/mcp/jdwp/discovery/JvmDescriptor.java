package one.edee.mcp.jdwp.discovery;

import org.jspecify.annotations.Nullable;

/**
 * One row of the local-JVM inventory. Built by combining the {@code jdk.attach} API
 * ({@link Source#ATTACH_API}) with Linux-specific {@code /proc/<pid>/cmdline} parsing
 * ({@link Source#PROC_FS}); when both strategies see the same PID the descriptor is
 * marked {@link Source#BOTH}.
 *
 * <p>All nullable slots carry {@link Nullable} explicitly so the {@code @NullMarked}
 * package default does not lie about availability — {@code mainClass}, {@code javaHome}
 * and {@code maskedCmdline} are missing on some platforms / for some processes.
 *
 * @param pid           OS process id
 * @param mainClass     best-effort main class or jar path. {@code null} when cmdline parse
 *                      failed and the attach API gave no display name. May be a jar path
 *                      (not a class) when the target was launched via {@code -jar}.
 * @param javaHome      filesystem path inferred from {@code /proc/<pid>/exe} symlink.
 *                      {@code null} off-Linux or when the symlink is unreadable (different
 *                      user, container boundary).
 * @param maskedCmdline full argv joined by single spaces with values of credential-looking
 *                      keys ({@code password}, {@code secret}, {@code token},
 *                      {@code apikey}, {@code api_key}) replaced by {@code ***}.
 *                      {@code null} when only the attach API saw this PID.
 * @param jdwp          parsed JDWP endpoint, or {@code null} when no JDWP agent was
 *                      detected. Distinct from "detected but unreachable", which is
 *                      represented as a non-null endpoint with state
 *                      {@link JdwpEndpoint.State#UNREACHABLE}.
 * @param isThisProcess {@code true} when this row is the MCP server's own PID — the
 *                      service never attaches to it, even with {@code inspectAll=true},
 *                      to avoid self-attach deadlocks.
 * @param source        which discovery strategy produced this row
 */
public record JvmDescriptor(
    long pid,
    @Nullable String mainClass,
    @Nullable String javaHome,
    @Nullable String maskedCmdline,
    @Nullable JdwpEndpoint jdwp,
    boolean isThisProcess,
    Source source
) {
    public enum Source {
        /**
         * Visible via {@code jdk.attach} {@code VirtualMachine.list()} only. Carries no
         * JDWP endpoint (the attach API does not expose the agent port without a real attach).
         */
        ATTACH_API,
        /**
         * Visible via {@code /proc/<pid>/cmdline} parsing only. Carries a JDWP endpoint
         * when the target was launched with {@code -agentlib:jdwp=...} / {@code -Xrunjdwp:...}.
         */
        PROC_FS,
        /**
         * PID seen by both strategies; fields are merged with {@code /proc} winning on
         * conflict (it carries the JDWP endpoint), borrowing the attach-API main class
         * when the {@code /proc} extraction came back null.
         */
        BOTH
    }
}

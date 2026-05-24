package one.edee.mcp.jdwp.discovery;

/**
 * Parsed and (optionally) probed JDWP endpoint for a single JVM. Built from the
 * {@code -agentlib:jdwp=} cmdline arg or from the {@code sun.jdwp.listenerAddress}
 * agent property, then enriched by {@link JvmDiscoveryService#confirmAll}.
 *
 * @param host           bind host as declared on the JVM cmdline; {@code "*"} or
 *                       {@code "0.0.0.0"} mean "all interfaces" (wildcard bind)
 * @param port           TCP port (1-65535)
 * @param transport      JDI transport name, almost always {@code "dt_socket"}
 * @param serverMode     {@code true} when the JVM is listening for an attach
 *                       ({@code server=y}); {@code false} when it dials out to a
 *                       waiting debugger ({@code server=n})
 * @param suspendOnStart {@code true} when the JVM is configured with {@code suspend=y}
 *                       and is therefore halted at VMStart until something attaches
 * @param state          classification of the endpoint at the time of observation;
 *                       mutates across the pipeline (cmdline parse → optional
 *                       attach-API enrichment → handshake confirmation)
 */
public record JdwpEndpoint(
    String host,
    int port,
    String transport,
    boolean serverMode,
    boolean suspendOnStart,
    State state
) {
    public enum State {
        /** Cmdline says JDWP is configured to listen, but the port has not been probed yet. */
        LISTENING,
        /**
         * Reserved; not currently emitted by the pipeline. Originally intended to mean "TCP
         * accepts, but handshake not yet verified" — collapsed into {@link #LISTENING} once
         * the only producer started always running the handshake. Kept so the renderer's
         * exhaustive switch stays valid and so a future probe split can re-use the name.
         */
        ATTACHABLE,
        /** Listening AND configured with {@code suspend=y} (waiting for initial attach). */
        SUSPENDED,
        /** This is the JVM we are currently attached to. */
        CONNECTED_TO_US,
        /** Cmdline says JDWP, but the port did not accept a TCP connection within the budget. */
        UNREACHABLE,
        /** Detected via attach API only; cmdline unavailable and port was not probed. */
        UNKNOWN
    }
}

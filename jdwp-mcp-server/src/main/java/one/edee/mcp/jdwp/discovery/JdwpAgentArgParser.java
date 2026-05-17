package one.edee.mcp.jdwp.discovery;

import org.jspecify.annotations.Nullable;

/**
 * Pure function: converts the substring that follows {@code -agentlib:jdwp=} or
 * {@code -Xrunjdwp:} on the JVM command line into a {@link JdwpEndpoint} with state
 * {@link JdwpEndpoint.State#UNKNOWN} (the port is not probed at this layer).
 *
 * <p>Recognised keys: {@code transport}, {@code server}, {@code suspend}, {@code address}.
 * Unknown keys are ignored. Missing or malformed input returns {@code null} rather than
 * throwing — callers treat absence of an endpoint the same as "no JDWP".
 *
 * <p>Address grammar follows the JDWP agent docs:
 * <ul>
 *   <li>{@code address=*:5005} — listen on all interfaces (rendered as host {@code "*"}).</li>
 *   <li>{@code address=127.0.0.1:5005} or {@code address=localhost:5005}.</li>
 *   <li>{@code address=5005} — bare port (legacy single-host form, treated as {@code "*"}).</li>
 * </ul>
 */
public final class JdwpAgentArgParser {

    private JdwpAgentArgParser() {}

    /**
     * Parses an agent-arg substring (everything after {@code -agentlib:jdwp=} or
     * {@code -Xrunjdwp:}, up to the next whitespace / NUL separator).
     *
     * @return parsed endpoint with state {@link JdwpEndpoint.State#UNKNOWN}, or {@code null}
     *     if the address is missing or unparseable.
     */
    @Nullable
    public static JdwpEndpoint parse(@Nullable String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return null;
        }

        String transport = "dt_socket";
        boolean serverMode = false;
        boolean suspend = true;
        String address = null;

        for (String token : agentArgs.split(",")) {
            final int eq = token.indexOf('=');
            if (eq < 0) {
                continue;
            }
            final String key = token.substring(0, eq).trim();
            final String value = token.substring(eq + 1).trim();
            switch (key) {
                case "transport" -> transport = value;
                case "server" -> serverMode = "y".equalsIgnoreCase(value);
                case "suspend" -> suspend = "y".equalsIgnoreCase(value);
                case "address" -> address = value;
                default -> { /* ignore unknown keys */ }
            }
        }

        if (address == null || address.isEmpty()) {
            return null;
        }

        final String host;
        final int port;
        final int lastColon = address.lastIndexOf(':');
        if (lastColon < 0) {
            // bare port: "address=5005"
            host = "*";
            try {
                port = Integer.parseInt(address);
            } catch (NumberFormatException nfe) {
                return null;
            }
        } else {
            host = address.substring(0, lastColon);
            try {
                port = Integer.parseInt(address.substring(lastColon + 1));
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
        if (port <= 0 || port > 65535) {
            return null;
        }

        return new JdwpEndpoint(host, port, transport, serverMode, suspend, JdwpEndpoint.State.UNKNOWN);
    }
}

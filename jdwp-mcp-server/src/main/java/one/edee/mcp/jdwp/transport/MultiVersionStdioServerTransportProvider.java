package one.edee.mcp.jdwp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;

import java.util.List;

/**
 * Overrides {@link StdioServerTransportProvider#protocolVersions()} to advertise every
 * MCP protocol version the bundled SDK knows about, not just the oldest one.
 *
 * <p>The upstream stdio transport (mcp-core 1.1.0 through 2.0.0-M2) hardcodes
 * {@code List.of("2024-11-05")}. Clients that request a newer version (Claude Code
 * 2.1.143 asks for {@code 2025-11-25}) get a downgrade response, then the session
 * silently stops responding to further requests — surfaced in the client as
 * {@code -32000 Failed to reconnect}.
 */
public final class MultiVersionStdioServerTransportProvider extends StdioServerTransportProvider {

    public MultiVersionStdioServerTransportProvider(McpJsonMapper jsonMapper) {
        super(jsonMapper);
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(
            ProtocolVersions.MCP_2025_11_25,
            ProtocolVersions.MCP_2025_06_18,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2024_11_05
        );
    }
}

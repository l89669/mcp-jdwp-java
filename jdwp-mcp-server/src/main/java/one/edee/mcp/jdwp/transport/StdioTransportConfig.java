package one.edee.mcp.jdwp.transport;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces Spring AI's auto-configured stdio transport with one that advertises
 * the full set of MCP protocol versions. The auto-config bean is
 * {@code @ConditionalOnMissingBean}, so defining ours suppresses it.
 */
@Configuration
public class StdioTransportConfig {

    @Bean
    public McpServerTransportProviderBase stdioServerTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
        return new MultiVersionStdioServerTransportProvider(new JacksonMcpJsonMapper(jsonMapper));
    }
}

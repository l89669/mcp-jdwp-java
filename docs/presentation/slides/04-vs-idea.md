<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Why not just use the IntelliJ MCP?

| Concern               | IntelliJ MCP                            | mcp-jdwp-inspector             |
|-----------------------|-----------------------------------------|--------------------------------|
| **Disambiguation**    | Routes through the *active* IDE window  | Scoped to a JVM **port**       |
| **No IDE / Docker / CI** | Needs IntelliJ running               | Needs only JDWP                |
| **Run configurations**| Agent **can't create** one ([IJPL-235149](https://youtrack.jetbrains.com/issue/IJPL-235149)) | `mvn test` + JDWP is enough    |
| **Log firehose**      | App logs spam the chat → tokens         | We don't surface app logs      |
| **Power profile**     | Broad write surface (refactor, exec)    | Debug-only, R-mostly, small blast radius |
| **Token economy**     | —                                       | Pairs with **rtk** + whitelisted `mvn` |

<small>Complementary, not competitive. Use the IJ MCP for refactors; use this when you want to *watch the JVM run*.</small>

Note:
- Disambiguation: IJ MCP routes through the focused window — multi-session × multi-project = chaos. We scope to a port.
- Run config: IJ agent can't create one on the fly ([IJPL-235149](https://youtrack.jetbrains.com/issue/IJPL-235149)). We just need `mvn test -Dmaven.surefire.debug`.
- Log firehose: IJ MCP pushes app logs to chat — every WARN burns tokens. We surface only what the agent asks for via `jdwp_get_events`.
- Token economy: pairs with `rtk` proxy + whitelisted `mvn` to keep context clean
- Power profile: our worst-case is mutating a local in one suspended thread. IJ MCP can refactor your repo. Different risk.

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Why not just use the IntelliJ MCP?

| Concern               | IntelliJ MCP                            | mcp-jdwp-inspector             |
|-----------------------|-----------------------------------------|--------------------------------|
| **Disambiguation**    | Routes through the *active* IDE window  | Scoped to a JVM **port**       |
| **No IDE / Docker / CI** | Needs IntelliJ running               | Needs only JDWP                |
| **Run configurations**| Agent **can't create** one ([IJPL-235149](https://youtrack.jetbrains.com/issue/IJPL-235149)) | `mvn test` + JDWP is enough    |
| **Log firehose**      | `execute_run_configuration` caps console at 2000 lines — Java logs easily 50k+ tokens | Agent reads only what it asks via `jdwp_get_events` |
| **Power profile**     | Broad write surface (refactor, exec)    | Debug-only, R-mostly, small blast radius |
| **Token economy**     | Overflow → `tool_response_too_large` → context rot | Pairs with **rtk** + whitelisted `mvn` |

<small>Complementary, not competitive. Use the IJ MCP for refactors; use this when you want to *watch the JVM run*.</small>

Note:
- Disambiguation: IJ MCP routes through the focused window — multi-session × multi-project = chaos. We scope to a port.
- Run config: IJ agent can't create one on the fly ([IJPL-235149](https://youtrack.jetbrains.com/issue/IJPL-235149)). We just need `mvn test -Dmaven.surefire.debug`.
- Log firehose — the architectural clash:
  - JetBrains' `execute_run_configuration` captures stdout/stderr and **caps at 2000 lines** (per the official MCP docs)
  - In Java/Spring with Hibernate at DEBUG, 2000 lines easily becomes **50k–100k tokens** — a single failing test blows the per-call budget
  - When the response is too big, the MCP client drops it as `tool_response_too_large`; when it lands, the agent's context is poisoned with noise → "context rot" (loses logical reasoning across turns)
  - `read_file` has the same truncation problem for large files
  - JDWP side: no bulk dump. Agent calls `jdwp_get_events` for the specific event it cares about, or `jdwp_get_locals` for the frame it's inspecting. Surgical reads, not firehose.
- Token economy: same root cause — IJ MCP's bulk-dump tools force the agent through JetBrains' caps. We trade that for fine-grained queries + `rtk` proxy compressing mvn/git/etc.
- Power profile: our worst-case is mutating a local in one suspended thread. IJ MCP can refactor your repo. Different risk.

<!-- .slide: class="vs-table" data-background-image="images/bg-content.png" data-background-size="cover" -->

## Why not just use the IntelliJ MCP?

| Concern               | IntelliJ MCP                            | mcp-jdwp-inspector                                                                                                                  |
|-----------------------|-----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **Disambiguation**    | Routes through the *active* IDE window  | Scoped to a JVM **port**                                                                                                            |
| **No IDE / Docker / CI** | Needs IntelliJ running               | Needs only JDWP                                                                                                                     |
| **Run configurations**| Agent **can't create** one ([IJPL-235149](https://youtrack.jetbrains.com/issue/IJPL-235149)) | Agent selects the right tests & profiles, narrowing the suite via `mvn` (`-Dtest`, `-pl`, `-P`)                                     |
| **Log firehose**      | `execute_run_configuration` caps console at 2000 lines — Java logs easily 50k+ tokens | Agent reads only what it asks via `jdwp_get_events`                                                                                 |
| **Power profile**     | Broad write surface (`execute_terminal_command`)    | Debug-only, R-mostly, small blast radius                                                                                            |
| **Token economy**     | Overflow → `tool_response_too_large` → context rot | Built lean — right-sized responses, hints to the next tool; pairs with **[rtk](https://github.com/rtk-ai/rtk)** + whitelisted `mvn` |

<small>Complementary, not competitive. Use the IJ MCP for refactors; use this when you want to *watch the JVM run*.</small>

Note:
- IJ MCP routes through the focused window — multi-session × multi-project = chaos.
- Log firehose — the architectural clash:
  - JetBrains' `execute_run_configuration` captures stdout/stderr and **caps at 2000 lines** (per the official MCP docs)
  - When the response is too big, the MCP client drops it as `tool_response_too_large`; 
  - when it lands, the agent's context is poisoned with noise → "context rot" (loses logical reasoning across turns)
  - `read_file` has the same truncation problem for large files
  - JDWP side: no bulk dump. Agent calls `jdwp_get_events` for the specific event it cares about, or `jdwp_get_locals` for the frame it's inspecting. Surgical reads, not firehose.
- Token economy: tools designed lean — responses carry just-enough context and explicit hints to the next tool, so the agent isn't guessing
- Recommend `rtk gain`

## Why not just use the IntelliJ MCP?

| Concern               | IntelliJ MCP                            | mcp-jdwp-inspector             |
|-----------------------|-----------------------------------------|--------------------------------|
| **Disambiguation**    | Routes through the *active* IDE window  | Scoped to a JVM **port**       |
| **No IDE / Docker / CI** | Needs IntelliJ running               | Needs only JDWP                |
| **Run configurations**| Agent has to create / pick one          | `mvn test` + JDWP is enough    |
| **Log firehose**      | App logs spam the chat → tokens         | We don't surface app logs      |
| **Power profile**     | Broad write surface (refactor, exec)    | Debug-only, R-mostly, small blast radius |
| **Token economy**     | —                                       | Pairs with **rtk** + whitelisted `mvn` |

<small>Complementary, not competitive. Use the IJ MCP for refactors; use this when you want to *watch the JVM run*.</small>

Note:
The disambiguation problem is real: multiple Claude Code sessions × multiple open IntelliJ projects → which agent talks to which project? The JetBrains MCP routes through whichever window has focus. Ambiguous. We attach to a port — unambiguous, scoped, killable.

Run configurations: the JetBrains MCP can't create one on the fly the way agents want to. Agents like to type `mvn test -Dtest=Foo -Dmaven.surefire.debug` and have it Just Work. We support exactly that.

Log firehose: when an IDE MCP surfaces app logs as chat output, every WARN line eats tokens. We don't do that — the only output you see is what the agent explicitly asks for via `jdwp_get_events`.

Token economy: rtk (Rust Token Killer) is a CLI proxy that compresses verbose output before the agent sees it. With `mvn` whitelisted in CLAUDE.md, the agent runs builds without burning context on stack traces it isn't going to read.

Power profile: our worst-case blast is mutating a local variable inside a single suspended thread. The JetBrains MCP can refactor your whole repo. Different risk profile.

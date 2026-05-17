## How is this different from the IntelliJ MCP?

JetBrains ships their own MCP server. We don't replace it — we cover a different shape of problem.

---

### Independence

- Multiple Claude Code sessions × multiple open IDEs → **whose IDE is which agent talking to?**
- The JetBrains MCP routes through the *active* project window. Ambiguous.
- This server attaches to a **JVM port** — unambiguous, scoped, killable.

Note:
This bites in real life. You have two repos open, two CC sessions, and the wrong one ends up driving the wrong project.

---

### Works without an IDE

- Docker container
- CI/CD pipeline (debug a failing test on the runner)
- Headless server
- A colleague's machine over SSH

If the JVM has JDWP open, we attach. No GUI, no JetBrains license, no plugin install.

---

### Practical wins

- **No run-configuration ritual** — agent doesn't need to create / pick an IDE run config; `mvn test` + JDWP is enough.
- **No log-stream firehose** — IDE MCPs surface app logs as token-eating chat output. We don't.
- **Token economy** — paired with **rtk** (Rust Token Killer) and `mvn` whitelisted, the agent runs builds without burning context.

---

### Different power profile

- The JetBrains MCP can refactor, run tools, execute terminal commands — **broad write surface**.
- This server only debugs. Variable mutation is scoped to a paused thread; nothing else.
- Smaller blast radius → safer to leave running in the background.

Note:
Frame this as complementary, not competitive. Use the IJ MCP for refactors and codebase edits; use this when you specifically want to *watch the JVM run*.

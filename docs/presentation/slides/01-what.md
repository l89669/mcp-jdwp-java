## What is it?

An **MCP server** that gives Claude Code a debugger.

- Speaks **JDWP** — the protocol your IDE already uses
- Attaches to *any* JVM with port 5005 (or wherever) open
- **46 tools** + 2 MCP resources: breakpoints, stack, locals, fields, expression eval, watchers, logpoints, **field watchpoints**, **chained breakpoints**, marks…
- Read **and** write: mutate locals, set fields at the breakpoint
- Pure STDIO → no daemon, no extra port, no GUI

Note:
JDWP = Java Debug Wire Protocol. Same wire IntelliJ / Eclipse / VS Code speak when you click "Attach". We translate it for the agent. JDI (the high-level Java API) is what we actually use server-side; JDWP is what travels over the socket.

Why "agent-driven" matters: the agent sees what the *debugger* sees, not what the log printed. Stack traces lie; runtime state doesn't.

Two MCP resources beyond the tools: `jdwp://diagnose` (state-of-the-world snapshot) and `jdwp://jvms` (local-JVM inventory) — attaching either via `@` in the prompt pulls the rendered text in without burning a model turn on a tool call.

Key constraint: this is a Claude Code plugin. Users `git clone` it. That's why the presentation lives on a separate orphan `gh-pages` branch — to keep their clones slim.

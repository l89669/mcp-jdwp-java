<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## What is it?

An **MCP server** that gives Claude Code a debugger.

- Speaks **JDWP** — the protocol your IDE already uses
- Attaches to *any* JVM with port 5005 (or wherever) open
- **46 tools** + 2 MCP resources: breakpoints, stack, locals, fields, expression eval, watchers, logpoints, field watchpoints, chained breakpoints, marks…
- Read **and** write: mutate locals, set fields at the breakpoint
- Pure STDIO → no daemon, no extra port, no GUI

Note:
- JDWP = Java Debug Wire Protocol — what IntelliJ / Eclipse / VS Code speak when you click "Attach"
- We translate JDWP ↔ MCP so the agent sees what a debugger sees
- Stack traces lie; runtime state doesn't
- 2 MCP resources too: `jdwp://diagnose`, `jdwp://jvms` — pull in via `@`, no tool turn
- It's a Claude Code plugin → users `git clone` it (slides live on orphan `gh-pages` to keep clones slim)

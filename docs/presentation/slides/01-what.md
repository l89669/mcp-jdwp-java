<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## What is it?

An **MCP server** that gives Claude Code a debugger.

- Speaks **JDWP** — the protocol your IDE already uses
- Attaches to *any* JVM with port 5005 (or wherever) open
- Read **and** write: mutate locals, set fields at the breakpoint
- Pure STDIO → no daemon, no extra port, no GUI

**46 tools** + 2 MCP resources:

<div class="chips">
  <span class="chip accent">breakpoints</span>
  <span class="chip">stack</span>
  <span class="chip">locals</span>
  <span class="chip">fields</span>
  <span class="chip accent">expression eval</span>
  <span class="chip">watchers</span>
  <span class="chip accent">logpoints</span>
  <span class="chip accent">field watchpoints</span>
  <span class="chip">chained BPs</span>
  <span class="chip">marks</span>
  <span class="chip">stepping</span>
  <span class="chip">exception BPs</span>
</div>

Note:
- JDWP = Java Debug Wire Protocol — what IntelliJ / Eclipse / VS Code speak when you click "Attach"
- We translate JDWP ↔ MCP so the agent sees what a debugger sees
- Stack traces lie; runtime state doesn't
- 2 MCP resources too: `jdwp://diagnose`, `jdwp://jvms` — pull in via `@`, no tool turn
- It's a Claude Code plugin → users `git clone` it (slides live on orphan `gh-pages` to keep clones slim)

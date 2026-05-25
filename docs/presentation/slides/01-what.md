<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## What is it?

An Claude Code plugin and **MCP server** that gives Claude Code a debugger.

- Speaks **Java Debug Wire Protocol** — the protocol your IDE already uses
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
- JDWP — what IntelliJ / Eclipse / VS Code speak when you click "Attach"
- We translate JDWP ↔ MCP so the agent sees what a debugger sees
- Stack traces sometimes lie; runtime state doesn't

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Architecture

![Architecture: Claude Code ↔ MCP Server ↔ Target JVM](images/architecture.png) <!-- .element: class="diagram diagram-compact" -->

- **Spring AI MCP**: `@McpTool` → JSON schema → STDIO
- Only the server ships; JDI lives in `jdk.jdi`
- Session keeps VM, cache, BPs, chains, events alive

Note:
- Why this layer: Claude can't speak JDWP (binary, stateful); server keeps VM ref, object cache, BPs, chains, events alive across tool calls
- JDWPTools — 44 @McpTool methods, thin orchestration
- JDIConnectionService — singleton VM, ObjectReference cache, marks pinned via `disableCollection`
- BreakpointTracker — synthetic IDs, pending/deferred, chain dependencies with cycle detection
- JdiEventListener — daemon consuming the JDI event queue
- EvaluationGuard — per-thread reentrancy guard, prevents deadlock on re-entrant eval
- EventHistory — ring buffer of last 100 events
- Spring Boot SYNC, no HTTP — JSON over STDIO
- Deep dive: `docs/index.md`

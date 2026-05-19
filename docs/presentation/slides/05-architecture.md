<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Architecture

![Architecture: Claude Code ↔ MCP Server ↔ Target JVM](images/architecture.png) <!-- .element: class="diagram" -->

- **Spring AI MCP 2.0** scans `@McpTool` → JSON schema → wires the transport
- Server is the **only** piece we ship — JDI lives in the JDK (`jdk.jdi` module)
- Long-lived session: VM ref, object cache, BP registry, chain deps, event queue persist across tool calls

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

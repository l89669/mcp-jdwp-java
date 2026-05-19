## Architecture

```
   ┌──────────────┐   MCP/STDIO   ┌────────────────────┐   JDI    ┌──────────────┐
   │  Claude Code │ ◄──────────► │  Spring Boot       │ ◄──────► │  Target JVM  │
   │   (agent)    │   JSON-RPC    │  MCP Server (us)   │   JDWP   │   :5005      │
   └──────────────┘               └────────────────────┘          └──────────────┘
                                       │
                                       ├─ JDWPTools          ── 44 @McpTool methods
                                       ├─ JDIConnectionService  ── VM ref, object cache, marks
                                       ├─ BreakpointTracker    ── synthetic IDs, deferred, chains
                                       ├─ JdiEventListener     ── daemon, JDI event queue
                                       ├─ EvaluationGuard      ── recursive eval guard
                                       └─ EventHistory         ── ring buffer of 100
```

- **MCP**: Anthropic's "tools the model can call" protocol — STDIO + JSON-RPC. We advertise MCP `2025-11-25`.
- **Spring AI MCP 2.0** scans `@McpTool` → generates JSON schema → wires the transport.
- Server is the **only** piece we ship. JDI lives in the JDK (`jdk.jdi` module).

Note:
Why does this layer exist at all? Two reasons:
1. Claude can't speak JDWP — it's a binary protocol with a stateful socket. We translate.
2. JDI sessions are long-lived; the server holds the VirtualMachine reference, object cache, breakpoint registry, chain dependencies, and event queue between tool calls. Without the server, every call would re-attach.

MCP primitives we use: **Tools** (44 `@McpTool` methods) and **Resources** (2: `jdwp://diagnose` and `jdwp://jvms`). We don't use **Prompts**. The 46-tool headline number on the README is tools + resources.

Components in more detail:
- **JDWPTools**: thin orchestration, 44 `@McpTool` methods.
- **JDIConnectionService**: singleton VM connection, ObjectReference cache (ConcurrentHashMap<Long, ObjectReference>), smart collection rendering, classpath discovery, **marked instances** (pinned by `disableCollection`).
- **BreakpointTracker**: registry with synthetic IDs; tracks pending/deferred state, conditions, logpoint expressions, exception breakpoints, field breakpoints, **chain dependencies** with cycle detection and trigger-fire memory across pending → active promotion.
- **JdiEventListener**: daemon thread consuming the JDI event queue; routes events, evaluates conditions/logpoints, handles recursive suppression.
- **EvaluationGuard**: per-thread reentrancy guard preventing deadlocks during expression evaluation.
- **EventHistory**: ring buffer of the last 100 JDWP events (including suppressed).

Spring Boot configuration: SYNC mode, `web-application-type=none`. No HTTP. JSON over STDIO.

For Java devs who want the gritty internals: `docs/index.md` is the developer reference — chapters on architecture, lifecycle, threading & safety, the event pipeline, breakpoints, expression evaluation, diagnostics, and testing.

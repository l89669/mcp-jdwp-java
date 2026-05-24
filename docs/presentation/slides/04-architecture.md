<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Architecture

![Architecture: Claude Code ↔ MCP Server ↔ Target JVM](images/architecture.png) <!-- .element: class="diagram" -->

- **Spring AI MCP**: `@McpTool` → JSON schema → STDIO
- Only the server ships; JDI lives in `jdk.jdi`
- Session keeps VM, cache, BPs, chains, events alive

<small class="muted">↓ press Down for MCP in a nutshell</small>

Note:
- Why this layer: Claude can't speak JDWP (binary, stateful); server keeps VM ref, object cache, BPs, chains, events alive across tool calls
- JDWPTools — 46 @McpTool methods, thin orchestration
- JDIConnectionService — singleton VM, ObjectReference cache, marks pinned via `disableCollection`
- BreakpointTracker — synthetic IDs, pending/deferred, chain dependencies with cycle detection
- JdiEventListener — daemon consuming the JDI event queue
- EvaluationGuard — per-thread reentrancy guard, prevents deadlock on re-entrant eval
- EventHistory — ring buffer of last 100 events
- Spring Boot SYNC, no HTTP — JSON over STDIO
- Deep dive: `docs/index.md`

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### MCP in a nutshell

[**MCP**](https://modelcontextprotocol.io) — one open protocol; an agent drives many tool servers over JSON-RPC.

- **Two transports**
  - **STDIO** — local subprocess
  - **HTTP** — streamable / SSE, remote
- **A server exposes**
  - **Tools** — the model invokes them
  - **Resources** — read-only context, addressed by URI
  - **Prompts** — reusable templates
- **This plugin:** STDIO + Tools + Resources
  - no daemon, no port, no auth surface
  - Resources (`jdwp://diagnose`, `jdwp://jvms`) attach via `@`, zero tool turns
  - Prompts skipped — the workflow lives in the bundled skill

<small class="muted">↓ press Down: writing your own in Java</small>

Note:
- MCP = open standard, JSON-RPC; one client ↔ many servers ("USB-C for AI tools")
- Transports: STDIO = local subprocess (what we use); Streamable HTTP/SSE = remote servers
- Three server primitives: Tools (model-invoked), Resources (app-controlled, URI-addressed, read-only), Prompts (user-selected templates)
- We expose Tools + Resources over STDIO; no Prompts — the bundled skill carries the workflow
- Why STDIO: local, no open port, no auth to misconfigure; the JVM target is the only network surface

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Building one is easy — in Java

Annotate methods; the framework generates the JSON schema and wires the transport. Mature options:

- [**MCP Java SDK**](https://github.com/modelcontextprotocol/java-sdk) — official; STDIO / SSE / HTTP built in, no web framework needed
- [**Spring AI**](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) — `@McpTool` annotations on a Spring Boot starter **(used here)**
- [**Quarkus MCP Server**](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html) — build-time schema, native image, fast cold start
- [**Micronaut MCP**](https://micronaut-projects.github.io/micronaut-mcp/snapshot/guide/) — compile-time schema, DI-driven
- [**Armeria**](https://github.com/line/armeria/issues/6179) — streamable-HTTP transport (newer)

Note:
- Barrier is low now: define methods/beans, annotate; the framework emits the JSON schema + handles transport
- Official MCP Java SDK is the foundation (Spring AI co-maintains it) — STDIO/SSE/HTTP, sync + async
- Spring AI: best DX if you already live in Spring Boot — this plugin's choice
- Quarkus: strongest native-image / startup / observability story, with build-time validation
- Micronaut: compile-time JSON schema, DI-driven; newer official module
- Armeria: streamable-HTTP transport, newer/experimental — no dedicated doc page yet (links to the tracking issue)

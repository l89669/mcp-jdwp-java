## What is MCP, briefly

**Model Context Protocol** — Anthropic's open standard for "tools the model can call".

Two transports:

- **STDIO** — child process speaks JSON-RPC over pipes (what we use)
- **HTTP/SSE** — remote server, browser-friendly

We pick STDIO because the JDI session must stay co-resident with the agent.

---

### Three primitives

| Primitive | What it is | We use it? |
|-----------|-----------|------------|
| **Tools**     | Function calls the model can invoke | ✅ ~40 tools |
| **Resources** | Read-only data the model can fetch  | ✅ a few |
| **Prompts**   | Server-side prompt templates        | ❌ |

---

### Implementation = annotations

```java
@McpTool(name = "jdwp_get_stack",
         description = "Return the call stack of a suspended thread.")
public String getStack(
    @McpToolParam(description = "Thread ID", required = false) Long threadId
) {
    // ...
}
```

Spring AI scans for `@McpTool`, generates the JSON schema, wires the
STDIO transport. **No protocol code in our codebase.**

Note:
This is genuinely the whole story — the MCP framework is the lightest layer in the stack. The hard parts are all on the JDI side.

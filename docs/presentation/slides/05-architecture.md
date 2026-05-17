## How it works

```
   ┌──────────────┐   MCP/STDIO   ┌────────────────────┐   JDI    ┌──────────────┐
   │ Claude Code  │ ◄──────────► │  Spring Boot       │ ◄──────► │ Target JVM   │
   │   (agent)    │   JSON-RPC    │  MCP Server (us)   │   JDWP   │  :5005       │
   └──────────────┘               └────────────────────┘          └──────────────┘
                                       │       │
                                       │       └─► JDI Event Listener (daemon)
                                       └─► Breakpoint registry, object cache,
                                           expression compiler, watchers
```

- **Left wire**: STDIO JSON-RPC — the MCP standard.
- **Right wire**: JDWP — Java's native debug protocol.
- **Server**: small Spring Boot app, the only piece we ship.

Note:
Why does this layer exist at all? Two reasons. (1) Claude can't speak JDWP — it's a binary protocol with a stateful socket; we translate. (2) JDI sessions are long-lived; the server holds the VM reference, object cache, breakpoint registry, and event queue between tool calls. Without the server every call would re-attach.

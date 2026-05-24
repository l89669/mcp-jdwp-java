# Troubleshooting the JDWP MCP Server

## Start here: `jdwp_diagnose`

When something isn't working — can't connect, breakpoint won't fire, target JVM seems to be gone — run `jdwp_diagnose` first. It returns a three-block "state of the world" snapshot in one call:

1. **MCP server** — PID, uptime, configured target host/port.
2. **JDWP connection** — connected/disconnected with the last-attempt error; when connected, the breakpoint+events report follows inline.
3. **Local JVMs** — every JVM visible to your user, with its JDWP port (read from `/proc` on Linux, confirmed by handshake) and whether it's listening, suspended on startup, attached to us, or unreachable.

This answers most "what's going on?" questions without `ps`/`lsof`/`jps`. Add `inspectAll=true` only if a JVM shows no port and you suspect JDWP is on — that briefly attaches to read `sun.jdwp.listenerAddress` (visible to the target, hence opt-in).

## Tool returns an unexpected error or server seems stuck

1. Check the server log if your installation writes one — the JDWP MCP server logs JDI operations and errors there.
2. Reconnect the MCP server: run `/mcp` in Claude Code and reconnect `jdwp-inspector`. This spawns a fresh subprocess.

## JDWP port is already in use

Applies to any JDWP port — 5005 for build-system shortcuts, but also non-default ports (8003, 8000, 9009, …) used by already-running services.

Find what holds the port:

```bash
ps -ef | grep "jdwp=transport"     # all JDWP-enabled JVMs
ss -tnlp | grep <port>             # what's listening on a specific port (Linux)
lsof -i :<port>                    # same, macOS / BSD
```

Only kill a process you launched yourself in this session. If the process is unrecognized or was not started by you, **ask the user before killing it** — it may be a long-running service the user *intends* you to attach to, or another developer's debug session. When in doubt, ask "is this the JVM I should attach to?" before doing anything destructive.

## Both processes gone after a crash

The test JVM exits when its debugger detaches; the MCP server may also have crashed. Reconnect the MCP server first (`/mcp`), then relaunch the test JVM from scratch.

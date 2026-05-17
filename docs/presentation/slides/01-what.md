## What is it?

An **MCP server** that gives Claude Code a debugger.

- Attaches to any JVM with **JDWP** open (the protocol your IDE already uses)
- Exposes ~40 tools: breakpoints, stack, locals, fields, expression eval, watchers, logpoints…
- Read **and** write: mutate locals, set fields, call methods at the breakpoint
- Pure STDIO — no extra daemon, no extra port

Note:
JDWP = Java Debug Wire Protocol. Same wire IntelliJ / Eclipse / VS Code speak when you click "Attach". We just speak it for the agent instead.

---

## Live demo

I'm kicking off a test-flight **right now**, in the background.

We'll come back to it once it has output.

Note:
Speaker — before this slide opens a separate terminal: trigger the test-flight session. While it runs, walk through the rest of the deck. Return at the end for actual interactive output. The next slide shows a complete session from a previous flight in case the live one is slow.

## Designed for agents, not humans

A pile of small decisions that matter when the "user" is an LLM, not a person with a mouse.

---

### 1 · Beating the attach race

Classic JDWP trap: agent starts the JVM, JVM races past the breakpoint, agent attaches **too late**, breakpoint never fires.

**Fix**: lean on `suspend=y`. Maven Surefire supports it out of the box:

```bash
mvn test -Dmaven.surefire.debug
# JVM halts at JDWP port 5005, waits for us
```

The agent attaches, **then** arms breakpoints, **then** resumes. Deterministic.

---

### 2 · Output formatted for one round trip

Every tool returns **one** human-readable `String`, pre-summarised.

- `get_stack` includes line numbers, file paths, *and* the per-frame "this" preview.
- `get_locals` expands `ArrayList`, `HashMap`, `HashSet` inline up to a budget.
- `to_string` is offered explicitly so the agent doesn't have to chain calls.

Fewer round trips → fewer tokens → faster sessions.

---

### 3 · Granular tools = cheap calls

Why so many small tools instead of one `inspect` mega-tool?

- Smaller schemas → smaller prompt overhead per tool definition
- Cheaper to invoke (the agent passes only the params it needs)
- Easier for the model to learn the right tool from the name + description

Trade-off: ~40 tools is a lot. But the model handles it; tokens win.

---

### 4 · No infinite waits

`resume_until_event` blocks **until something interesting happens** — breakpoint, exception, VMDeath — with a server-side timeout.

The agent never loops `is-it-hit-yet`. The server's event listener thread is the source of truth; the tool just awaits it.

If nothing fires within the budget, we return a clear "still running" answer so the agent can decide to wait more or move on.

---

### 5 · Auto-rearming on class load

JDWP gotcha: you can't set a breakpoint in `com.foo.Bar` if `Bar` isn't loaded yet — JDI throws "class not prepared".

We register a **ClassPrepareRequest**, queue the breakpoint, and arm it the moment the target JVM loads the class. Symmetric cleanup on `VMDeath`.

The agent just says "set breakpoint here" — timing is our problem.

---

### 6 · Port auto-diagnostic

**`jdwp_diagnose`** — when no port is supplied, the server scans the local machine for JVMs listening on JDWP ports and reports candidates with their main class.

Saves the agent from asking "what port should I use?" and saves the user from remembering.

---

### 7 · Logpoints — debugging without stopping

A line breakpoint that doesn't suspend. Just logs an expression each time the line is hit.

```
set_logpoint  com.foo.Worker:142  "tx=" + tx.id() + " items=" + items.size()
```

Three flavors:
- **Line** logpoint — `set_logpoint`
- **Exception** logpoint — `set_exception_logpoint` (with `$exception`-bound expressions)
- **Field** logpoint — `set_field_logpoint` (logs every read/write)

Better than `System.out.println`: no code change, no rebuild, no redeploy. Killer feature for race conditions where stopping the thread hides the bug.

---

### 8 · Skill recipes

Bundled `jdwp-debugging:java-debug` skill teaches the agent the playbook:

- *When* to attach
- *How* to choose between breakpoint / logpoint / field watchpoint
- *Which* tools to chain for "who wrote this field"
- How to clean up before disconnecting

Without the skill, the model freelances. With it, sessions look like an experienced developer's.

Note:
End on this point: the protocol is JDWP, the wire is MCP, but the **product** is the skill + tool combo that teaches the agent to debug like a pro. Everything else is plumbing.

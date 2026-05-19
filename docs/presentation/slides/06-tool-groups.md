<!-- .slide: class="dense-table" -->

## Tool inventory

**46 tools + 2 resources**, grouped by purpose. Each `@McpTool` method on `JDWPTools`.

| Group                         | # | Purpose                                              |
|-------------------------------|---|------------------------------------------------------|
| **Connection**                | 3 | attach / detach / wait                               |
| **Inspection**                | 8 | threads · stack · locals · fields · `toString` · 1-shot |
| **Execution control**         | 7 | resume · suspend · step · `resume_until_event`       |
| **Breakpoints**               | 7 | line · conditional · logpoint · exception (+log-only) · **field** (+log-only) |
| **Breakpoint chains**         | 3 | trigger-gated BPs, cycle-checked                     |
| **Expression eval + mutation**| 4 | `evaluate` · `assert` · `set_local` · `set_field`    |
| **Events**                    | 2 | event ring buffer (read / clear)                     |
| **Diagnostics**               | 1 | `jdwp_diagnose` — state of the world                 |
| **Watchers**                  | 4 | persistent expressions bound to breakpoints          |
| **Marked instances**          | 3 | label objects as `$label` for use in expressions     |
| **Debug state**               | 2 | unified `overview` / `clear` (read · bulk delete)    |
| **Session**                   | 1 | `reset` — clear all state without disconnecting      |

<small class="muted">↓ press Down for per-group detail</small>

Note:
Walk through groups by scrolling Down. Each group is one screen with the tool table and a one-line "why this matters for an agent". Skip to "Features beyond JDWP" if running short on time.

Big restructuring of the surface: the per-type `list_*` and `clear_all_*` tools collapsed into `jdwp_overview` (read) and `jdwp_clear` (bulk delete) — agent learns one mental model for "show me everything" / "wipe the BPs of kind X".

The 46-tool headline = 44 `@McpTool` methods + 2 MCP resources (`jdwp://diagnose`, `jdwp://jvms`).

--

### Connection (3) + Diagnostics (1)

| Tool                   | Params                          | Notes                                     |
|------------------------|---------------------------------|-------------------------------------------|
| `jdwp_connect`         | —                               | Connect to JDWP on configured host:port   |
| `jdwp_disconnect`      | —                               | Sends JDWP Dispose                        |
| `jdwp_wait_for_attach` | `host?`, `port?`, `timeoutMs?`  | Poll until JVM listens, then attach       |
| `jdwp_diagnose`        | `inspectAll?`                   | 3-block snapshot: server · JDWP · local JVMs |

Plus resources: **`jdwp://diagnose`**, **`jdwp://jvms`** — attach via `@` in prompt, no tool turn.

Note:
`wait_for_attach` is what makes the `suspend=y` workflow seamless — the agent fires it *before* the JVM is up and grabs the connection the moment the port opens.

`jdwp_diagnose` is the "what's wrong" tool. Three blocks: (1) MCP server PID/uptime/configured target; (2) JDWP connection — last-attempt error when disconnected, breakpoints + recent events when connected; (3) local JVMs visible to the user with detected JDWP ports. With `inspectAll=true` it briefly attaches to every same-user JVM to discover its port via `sun.jdwp.listenerAddress`. Also recognises the chain-stuck state where every armed BP is waiting on a trigger that hasn't fired.

The two MCP resources expose the same data as cheap context attaches — handy for "is my target up?" without involving the model.

--

### Inspection (8)

| Tool                          | Params                                       |
|-------------------------------|----------------------------------------------|
| `jdwp_get_version`            | —                                            |
| `jdwp_get_threads`            | `includeSystemThreads?`                      |
| `jdwp_get_stack`              | `threadId`, `maxFrames?`, `includeNoise?`    |
| `jdwp_get_locals`             | `threadId`, `frameIndex`                     |
| `jdwp_get_fields`             | `objectId`                                   |
| `jdwp_to_string`              | `objectId`, `threadId`                       |
| `jdwp_get_breakpoint_context` | `maxFrames?`, `includeThisFields?` ⭐         |
| `jdwp_get_current_thread`     | —                                            |

<small><span class="accent">⭐</span> one-shot dump → thread + top frames + locals + `this` fields in one call (replaces 4 round trips).</small>

Note:
Object IDs are stable across calls — the server caches `ObjectReference`s. The agent walks a graph without re-resolving.

`get_threads` hides JVM internals by default (Reference Handler, Finalizer, surefire workers). Pass `includeSystemThreads=true` to see everything.

`get_stack` collapses junit/surefire/reflection noise frames by default. Pass `includeNoise=true` to see the full stack.

`get_breakpoint_context` is the most agent-shaped tool in the set: at every breakpoint hit you want thread info, top frames, locals at frame 0, and `this` fields. Without this tool that's 4 calls. With it, 1.

--

### Execution control (7)

| Tool                       | Params        |
|----------------------------|---------------|
| `jdwp_resume`              | —             |
| `jdwp_resume_thread`       | `threadId`    |
| `jdwp_suspend_thread`      | `threadId`    |
| `jdwp_resume_until_event`  | `timeoutMs?` ⭐|
| `jdwp_step_over`           | `threadId?`   |
| `jdwp_step_into`           | `threadId?`   |
| `jdwp_step_out`            | `threadId?`   |

<small><span class="accent">⭐</span> blocks server-side until the next event — eliminates the "resume → poll → poll" busy loop.</small>

Note:
`resume_until_event` is the big agent ergonomics win in this group. Without it, the agent does `resume`, then loops calling `get_events` until something interesting fires — wasting tokens on every empty poll. With it, the JDI event listener (daemon thread) is the source of truth; the tool awaits the next event with a timeout.

Step semantics match IntelliJ: F6 / F7 / Shift+F8. `threadId` is **optional** — omitted, the step targets the thread of the last breakpoint hit. Each step is one round trip; for predictable destinations, prefer a breakpoint + `resume_until_event`.

--

### Breakpoints (7) + Chains (3)

| Tool                              | What                                                     |
|-----------------------------------|----------------------------------------------------------|
| `jdwp_set_breakpoint`             | line · condition · `triggerBreakpointId?` · `oneShot?`   |
| `jdwp_set_logpoint`               | non-stopping line BP · evaluates + logs expression       |
| `jdwp_clear_breakpoint`           | by id — routes across line / exception / field          |
| `jdwp_set_exception_breakpoint`   | suspend at throw · caught/uncaught · chainable           |
| `jdwp_set_exception_logpoint`     | non-stopping · `$exception` bound                        |
| `jdwp_set_field_breakpoint`       | access · modification · both · filters · chainable       |
| `jdwp_set_field_logpoint`         | non-stopping · `$oldValue` `$newValue` `$object` `$mode` |
| `jdwp_set_breakpoint_dependency`  | gate BP-B until BP-A fires (sticky / one-shot)           |
| `jdwp_clear_breakpoint_dependency`| detach gate                                              |
| `jdwp_disarm_until_trigger`       | re-disable a dependent after its one-shot fired          |

Note:
This is the biggest group in the surface. Field watchpoints and chained breakpoints are the two tools that have no IntelliJ-equivalent shortcut and earn their slot on the keychain.

Conditional BPs are critical for agents — a BP in a hot loop generates thousands of useless stops. With a condition, the JVM evaluates the expression on every hit, but the thread only suspends when the result is `true`. False hits auto-resume transparently.

Logpoints are the gold standard for non-intrusive tracing — evaluate, log to the event ring buffer, never suspend. Better than `System.out.println`: no code change, no rebuild, no redeploy.

Field watchpoints fire on every read/write of the field — including writes via reflection (`Field.set`) and `Unsafe`. That's how test flight #6 catches the rogue write. Modes: `access` / `modification` / `both`. Synthetic bindings: `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`. **Performance:** field watchpoints fire on *every* read/write — for hot fields they can dominate target-VM CPU. Prefer narrow filters or short-lived sessions. `jdwp_diagnose` surfaces `canWatchFieldAccess` / `canWatchFieldModification` plus a perf warning when connected.

Chained breakpoints: BP-B stays disabled until BP-A fires. Two modes — sticky (B stays armed forever) and one-shot (B self-disarms after firing). Cycles rejected at registration. Pending BPs are first-class participants; trigger fires that happen while a dependent is still pending are remembered.

Log-only exception breakpoints (`set_exception_logpoint`) trace exception flows in long-running services without stopping them.

--

### Expression eval + mutation (4)

| Tool                       | Params                                              |
|----------------------------|-----------------------------------------------------|
| `jdwp_evaluate_expression` | `threadId`, `expression`, `frameIndex?`             |
| `jdwp_assert_expression`   | `expression`, `expected`, `threadId`, `frameIndex?` |
| `jdwp_set_local`           | `threadId`, `frameIndex`, `varName`, `value`        |
| `jdwp_set_field`           | `objectId`, `fieldName`, `value`                    |

```java
jdwp_evaluate_expression(threadId=25, expression=
  "order.getItems().stream().filter(i -> i.price().compareTo(BigDecimal.TEN) > 0).count()")
```

Note:
This is the crown jewel. At a suspended breakpoint the agent runs *arbitrary Java*. Compile a wrapper class with Eclipse JDT in-memory → ship bytecode into the target JVM via `ClassLoader.defineClass` → invoke. Classpath is discovered by walking the target's classloader hierarchy (including Tomcat containers). UUID-named wrapper classes avoid `LinkageError` across reruns.

`assert_expression` returns "OK" or "MISMATCH: actual=..., expected=..." — saves an eval+compare round trip.

`set_local` and `set_field` test hypotheses without restarting. Mutation is scoped to a paused thread. Handles Guice/CGLIB proxies automatically.

--

### Watchers (4) + Marks (3)

| Tool                                | Params                                |
|-------------------------------------|---------------------------------------|
| `jdwp_attach_watcher`               | `breakpointId`, `label`, `expression` |
| `jdwp_detach_watcher`               | `watcherId`                           |
| `jdwp_list_watchers_for_breakpoint` | `breakpointId`                        |
| `jdwp_evaluate_watchers`            | `threadId`, `scope`, `breakpointId?`  |
| `jdwp_mark_instance`                | `label`, `objectId`, `pin?`           |
| `jdwp_unmark_instance`              | `label`                               |
| `jdwp_rename_mark`                  | `oldLabel`, `newLabel`                |

Note:
Watchers are MCP-side bookkeeping — attach one to a breakpoint with a label + expression; on every hit the agent calls `evaluate_watchers` to get all watcher values in one round. Dual-indexed by watcher UUID and breakpoint ID; auto-cleaned when the breakpoint dies.

**Marks**: name a cached object as `$label` and use that label in any expression (conditions, logpoints, watchers, evaluations). Pinned by default — the server calls `disableCollection` on the underlying mirror so GC can't yank it out from under you. `unmark_instance` releases the pin. Lets the agent track "the specific Order under test" across breakpoint hits even when the local variable holding it goes out of scope.

The bulk listing for both lives in `jdwp_overview` (`types="watcher"` or `types="mark"`).

--

### Debug state — `overview` / `clear` (2)

| Tool            | Params               | Notes                                                                   |
|-----------------|----------------------|-------------------------------------------------------------------------|
| `jdwp_overview` | `types?`, `filter?`  | Unified read: BPs · exception BPs · field BPs · logpoints · watchers · marks. Filter by type subset and/or case-insensitive substring (class/label/expression/type). |
| `jdwp_clear`    | `types`, `filter?`   | Bulk delete by type / substring. `types='all'` clears everything. Preview with `jdwp_overview` first. |

Plus events + session:

| Tool                | Notes                                              |
|---------------------|----------------------------------------------------|
| `jdwp_get_events`   | Ring buffer of the last 100 events (includes EXCEPTION_LOG, FIELD_LOGPOINT, BREAKPOINT_SUPPRESSED, CHAIN_BROKEN) |
| `jdwp_clear_events` | Reset the buffer                                   |
| `jdwp_reset`        | Clear all state without disconnecting              |

Note:
`overview` and `clear` are the agent's single mental model for "show me state" / "wipe state". They replace ~7 narrower tools from 1.x.

Event types worth knowing:
- `BREAKPOINT_HIT`, `STEP`, `EXCEPTION`
- `LOGPOINT` — line logpoint evaluated
- `EXCEPTION_LOG` / `EXCEPTION_LOG_ERROR` — exception logpoints
- `FIELD_LOGPOINT` / `FIELD_LOGPOINT_ERROR` — field logpoints
- `BREAKPOINT_SUPPRESSED` / `EXCEPTION_SUPPRESSED` / `STEP_SUPPRESSED` — recursive eval guard
- `CHAIN_BROKEN` — a trigger BP was removed and its dependents re-armed unconditionally

`reset` is the "start clean without losing the connection" tool. Useful between scenarios.

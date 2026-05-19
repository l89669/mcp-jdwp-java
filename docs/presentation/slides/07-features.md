<!-- .slide: class="dense-table" -->

## Features beyond standard JDWP

Raw JDI gives you threads, frames, variables. Not enough for an agent.

| Feature                            | Why it matters for agents                         |
|------------------------------------|---------------------------------------------------|
| **Conditional breakpoints**        | Avoid flood from hot-loop breakpoints             |
| **Logpoints** (line · exception · field) | Non-intrusive tracing → no `System.out.println` |
| **Deferred breakpoints**           | Set BPs on classes not yet loaded                 |
| **Exception breakpoints**          | Catch at throw site, before unwind                |
| **Field watchpoints**              | Catch reads/writes incl. reflection & `Unsafe`    |
| **Chained breakpoints**            | Gate BP-B until BP-A fires (sticky / one-shot)    |
| **Marked instances**               | Reference objects as `$label` in any expression   |
| **Expression evaluation**          | Arbitrary Java at any breakpoint                  |
| **Value mutation**                 | Test hypotheses without restarting                |
| **Watchers**                       | Persistent expressions, auto-evaluated on hit     |
| **Recursive eval protection**      | No deadlock on re-entrant breakpoints             |
| **Smart filtering**                | Hide framework noise by default                   |
| **Blocking `resume_until_event`**  | Kill the resume→poll busy loop                    |
| **One-shot `breakpoint_context`**  | 4 calls → 1                                       |

<small class="muted">↓ press Down for the highlights</small>

Note:
This is the "why use this instead of raw JDI" slide. Every item exists because we hit a real agent ergonomics problem and built around it.

The deck calls out four highlights on the verticals: (1) conditional + logpoints together; (2) field watchpoints + chained BPs — the big 2.0 additions; (3) expression evaluation + recursive guard — the crown jewel; (4) agent ergonomics — small things that compound.

--

### Conditional breakpoints + logpoints

```java
jdwp_set_breakpoint(
  className="com.example.OrderService", lineNumber=42,
  condition="order.getTotal() > 1000"      // only stop when true
)

jdwp_set_logpoint(
  className="com.example.OrderService", lineNumber=42,
  expression="\"order=\" + order.getId() + \" total=\" + order.getTotal()",
  condition="order.getTotal() > 1000"      // optional
)

jdwp_set_exception_logpoint(
  exceptionClass="java.sql.SQLException",
  expression="$exception.getSQLState() + \": \" + $exception.getMessage()"
)
```

- **Conditional BP** — fires every hit, suspends only when condition is `true`.
- **Logpoint** — evaluates and logs, **never** suspends. Result lands in the event ring buffer.
- **Exception logpoint** — `$exception` is bound to the thrown object.

Note:
All three compile via the in-target Java compiler pipeline (Eclipse JDT → bytecode → defineClass). That means conditions and log expressions can reference any visible variable, call any method on the classpath, and use lambdas/streams.

Logpoints are the killer feature for race-condition debugging — stopping a thread to inspect concurrency state often hides the bug. With logpoints you observe without perturbing.

Exception logpoints trace exception flows in long-running services without stopping them. Each hit records an `EXCEPTION_LOG` entry; failures during evaluation surface as `EXCEPTION_LOG_ERROR` so the listener never throws.

--

### Field watchpoints + chained breakpoints

```java
jdwp_set_field_breakpoint(
  className="com.example.OrderState", fieldName="status",
  mode="modification",                     // access · modification · both
  condition="!\"PAID\".equals($newValue)"  // $oldValue, $newValue, $object, $fieldName, $mode
)

jdwp_set_breakpoint(
  className="com.example.OrderProcessor", lineNumber=87,
  triggerBreakpointId=12,                  // depends on BP #12
  oneShot=true                             // self-disarm after one fire
)
```

- **Field watchpoint** — JVM-level field stores, including `Field.set` and `Unsafe` writes
- Synthetic bindings for conditions: `$oldValue` · `$newValue` · `$object` · `$fieldName` · `$mode`
- **Chained BP** — gated until trigger fires; sticky (stays armed) or one-shot
- Cycles in chains rejected at registration; pending BPs are first-class participants

Note:
Field watchpoints land all the writes a line BP would miss. Test flight #6 catches a `Field.setAccessible(true) + Field.set(profile, canonical)` bypass that the public setter never sees. Line BP never fires; field BP fires every store regardless of how it travels.

Perf warning: field watchpoints fire on **every** read/write of the watched field. For hot fields they can dominate target-VM CPU — prefer narrow filters (`threadFilterId`, `objectFilterId`) or short-lived sessions. `jdwp_diagnose` surfaces capability + perf warning when connected.

Chained BPs encode "only break in this path". Mix line and exception BPs freely as triggers/dependents. When the trigger is removed, every dependent is automatically armed and a `CHAIN_BROKEN` event is recorded. `jdwp_diagnose` recognises the "every armed BP is waiting on a non-fired trigger" state and tells you why nothing is firing.

`jdwp_disarm_until_trigger` is the manual re-disable for when a one-shot already fired and you want another round.

--

### Expression evaluation pipeline + recursive guard

```
agent expression
      │
      ▼
JdiExpressionEvaluator   ── wraps in UUID-named class, picks frame context
      │
      ▼
ClasspathDiscoverer      ── walks target VM classloaders (incl. Tomcat)
      │                     uses JdkDiscoveryService to find matching JDK
      ▼
InMemoryJavaCompiler     ── Eclipse JDT (ECJ) → bytecode in memory
      │
      ▼
RemoteCodeExecutor       ── ClassLoader.defineClass on target, invoke
```

- UUID-named wrappers → no `LinkageError` across reruns
- Results are cached
- Handles Guice / CGLIB proxies transparently
- **Recursive eval guard** prevents deadlock when an expression re-enters the breakpointed line
- **Marked instances** (`$label`) survive across hits even when the local goes out of scope

Note:
The recursive eval guard is the cleverest piece in the codebase. Setup: an expression evaluated at a breakpoint re-enters the breakpointed method (e.g., `this.compute(n-1)` evaluated inside `compute`). Without guarding, JDI re-suspends the thread on its own breakpoint and deadlocks.

What we do:
1. Per-thread reentrancy guard (`EvaluationGuard`) wraps every `invokeMethod` chain.
2. Recursive breakpoint / exception / step events are auto-resumed instead of suspending.
3. A `BREAKPOINT_SUPPRESSED` / `EXCEPTION_SUPPRESSED` / `STEP_SUPPRESSED` entry is recorded in event history.
4. The outer breakpoint context is preserved.

Covered invocation sites: `evaluate_expression`, `assert_expression`, `evaluate_watchers`, logpoint evaluation, conditional BP evaluation, field BP condition, `to_string`, classpath discovery, deferred class loading via `Class.forName`.

Marks: pinned via `disableCollection` so the underlying mirror survives GC. Useful for "follow this specific Order" across many breakpoint hits — the local variable holding the order may go out of scope, but `$orderUnderTest` keeps working.

--

### Agent ergonomics — small things that compound

- **`resume_until_event`** — server-side block until next event. Token-cheap.
- **`get_breakpoint_context`** — thread + frames + locals + `this` fields in one call.
- **Smart filtering** — `get_threads` hides JVM internals; `get_stack` collapses surefire/junit/reflection noise. Opt-in `includeSystemThreads` / `includeNoise`.
- **Deferred breakpoints** — `ClassPrepareRequest` auto-promotes the BP when the class loads. "Class not prepared" never reaches the agent. Works for line, exception, **and field** BPs.
- **Object cache + marks** — `ObjectReference`s survive across tool calls. The agent walks graphs by ID without re-resolving. Marks make them addressable.
- **Unified `overview` / `clear`** — single mental model for "show / wipe" across all BP and watcher kinds.

Note:
The README's framing: "Your agent gets the same power as IntelliJ's debugger." More accurate: the agent gets a debugger *tuned for the agent's call pattern*, not a human's. `resume_until_event` and one-shot `breakpoint_context` don't exist in IntelliJ because a human staring at a screen doesn't need them.

**Skill recipes — the meta-tool.** Bundled `java-debug` skill (`skills/java-debug/SKILL.md`) teaches the agent *when* to attach, *how* to choose between line BP / logpoint / field watchpoint, *which* tools to chain. Without the skill, the model freelances. With it, sessions look like an experienced developer's: attach, set targeted BPs, dump context, eval, mutate, verify, clean up, disconnect. Companion references: `prerequisites.md` (build-system-specific JDWP launch details) and `troubleshooting.md`.

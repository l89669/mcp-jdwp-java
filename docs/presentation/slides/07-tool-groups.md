## What the agent can do

~40 tools, grouped by purpose:

| Group | Examples |
|-------|----------|
| **Lifecycle**   | `connect`, `disconnect`, `wait_for_attach`, `diagnose` |
| **Inspection**  | `get_stack`, `get_locals`, `get_fields`, `get_threads`, `to_string` |
| **Breakpoints** | `set_breakpoint`, `set_exception_breakpoint`, `set_field_breakpoint`, `set_breakpoint_dependency` |
| **Logpoints**   | `set_logpoint`, `set_exception_logpoint`, `set_field_logpoint` |
| **Execution**   | `resume`, `step_into`, `step_over`, `step_out`, `resume_until_event` |
| **Mutation**    | `set_local`, `set_field` |
| **Evaluation**  | `evaluate_expression`, `assert_expression` |
| **Tracking**    | `mark_instance`, `rename_mark`, `attach_watcher` |
| **Overview**    | `overview`, `get_events`, `get_breakpoint_context` |

---

### Inspection — what's in scope right now

- **`get_stack`** — frames + line numbers + this/locals summary per frame
- **`get_locals`** — values in the selected frame, expanded for common collections
- **`get_fields`** — instance and static fields of any cached object
- **`to_string`** — calls `toString()` remotely (handy for opaque objects)

Object IDs are stable across tool calls — the server caches `ObjectReference`s,
so the agent can walk graphs without re-resolving.

---

### Breakpoints & stepping

- Line, exception, **field** (read/write watchpoints)
- Conditional: any breakpoint can carry a condition expression
- **Dependent breakpoints**: B fires only after A has hit — encodes "only break in this path"
- Threads stay **suspended on hit** — agent inspects, then explicitly resumes
- `resume_until_event` — auto-resume until something interesting fires (no busy-poll)

---

### Expression evaluation

The big one. At a breakpoint the agent can run **arbitrary Java**:

```java
order.getItems().stream()
    .filter(i -> i.price().compareTo(BigDecimal.TEN) > 0)
    .count()
```

How: we compile a wrapper class with **Eclipse JDT in-memory**, ship
the bytecode into the target JVM via `ClassLoader.defineClass`, and
invoke it. Classpath is discovered by walking the target's classloader
hierarchy (incl. Tomcat).

UUID-named wrapper classes avoid `LinkageError` across reruns.

---

### Watchers & marks

- **Watcher** — an expression bound to a breakpoint, auto-evaluated on hit
- **Mark** — give an object a human-readable name (`"orderUnderTest"`),
  follow it across the heap even after the local variable goes out of scope

Both are MCP-side bookkeeping — no agent inside the target JVM.

Note:
Don't drill into every tool — these slides are reference, not script. The point: there's a tool for every step of a real debugging session, designed for an agent's call pattern, not a human's mouse.

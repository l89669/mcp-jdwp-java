<!-- .slide: class="hub-slide" data-background-image="images/bg-content.png" data-background-size="cover" -->

## What an agent needs

A human in IntelliJ has eyes, a mouse, and patience. An agent has none of those — so the debugger is reshaped around how it actually works.

- **Tools shaped to the task** — built for the job, not raw protocol calls
- **Context & hints in the response** — clear names, and what to do next
- **Token-economical I/O** — just enough output, never a firehose
- **No blind hangs** — it can wait as long as it takes, never frozen
- **Guard rails** — it can't get itself stuck
- **A skill that teaches** — worked examples, not just a tool list

<small class="muted">↓ press Down for the deep dives</small>

Note:
- Hub slide for the back half — each bullet expands into one vertical deep-dive
- Framing throughout: every capability answers "why an agent needs this differently from a human"

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Bootstrap, redesigned for agents

- **The server polls, not the agent** — one `wait_for_attach` call retries the port every 200 ms until the JVM is up, then returns the live session; optional `port`, default 5005
- **No port guessing** — `jdwp://diagnose` / `jdwp://jvms` report which local JVMs are up and on which JDWP port (no `jps` / `lsof`)
- **Deferred breakpoints** — arm BPs on not-yet-loaded classes; they bind via class-prepare when the JVM loads them on its own
- **`reconnect` keeps the session** — re-attach to the last target; BP specs, conditions, logpoints, chains, watchers + their IDs survive (BP #7 stays #7)

Deferred BPs are the unlock under `mvn test`: the agent is too slow to race the classloader, so it arms everything *before* code runs.

Note:
- if you try to debug tests naively, the test finishes before the agent can set BPs
- another problems are scenarios when agent never stops on BP -> timeouts
- reconnect communicates what stays and what is lost

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Expressions — compiled, not interpreted

- **Real Java → real bytecode** — compiled, not interpreted
- **Self-configuring** — finds the classpath and a matching JDK for you
- **Two modes** — a single expression, or a `{ … return X; }` block
- **Full scope** — reaches private / package-private members too (with limitations)
- **Recursion guard** — re-entrant eval won't deadlock the target

<small class="muted">↓ press Down for the pipeline</small>

Note:
- "Finds the classpath":
  - walks the target's classloader hierarchy, including Tomcat / app-server webapp classloaders — `WEB-INF/lib` JARs aren't on `java.class.path`, 
  - so this is what lets eval resolve your app's own classes
- "Matching JDK": 
  - JdkDiscoveryService locates a local JDK of the target's major version (the target's own `java.home` first), passed to JDT as `--system` so `java.*` resolves; 
  - language level matches the target. ECJ is self-contained, so the target can be newer than the server's own runtime JDK as long as a matching JDK is installed
- Block mode works in every eval slot: conditions, logpoints, watchers, assertions

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### The expression pipeline

![Expression evaluation pipeline](images/eval-pipeline.png) <!-- .element: class="diagram" -->

Agent expression → compiled in the **MCP server** → bytecode injected and invoked in the **target JVM**.

Note:
- Only the final `defineClass` + invoke crosses into the target JVM; everything else is MCP-server-side
- Two JDI hops cross the boundary:
  - ClasspathDiscoverer reads the target's classloaders;
  - RemoteCodeExecutor ships the bytecode and invokes it

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Stop guessing — observe the failure

- **`assert_expression`** — checks a hypothesis in-VM, returns `OK` / `MISMATCH` (actual vs expected) in one round trip
- **Watchers** — labelled expressions that auto-evaluate on every hit of a BP; stop re-typing the same probes
- **Conditional BPs** — full Java conditions (incl. `{ block }`) so it stops only on the bad request
- **Chained BPs** — arm BP-B only after BP-A fires; tame hot-loop floods (sticky stays armed · one-shot self-disarms)
- **Marks** — pin an object as `$label` past GC, then write conditions against it across hits

The agent stops reasoning from stack traces and reads the actual runtime state.

Note:
- assert_expression comparison is string-based against the same formatting evaluate_expression uses
- Chains: cycles rejected at registration; diagnose flags the "armed but waiting on a non-fired trigger" stuck state
- IntelliJ has dependent BPs but no log-only chains;

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Logpoints & watchpoints

Observe without a single `println` — and catch writes a setter breakpoint never sees.

- **Field watchpoints** — fire on every JVM-level store (incl. reflection `Field.set`, `Unsafe`); the thread **suspends at the write**, so `jdwp_get_stack` names the writer on the spot
- **Logpoints** — line / exception / field — evaluate + **write the result to the event log**, **never suspend** the thread, **never push** — the hit isn't surfaced live
- **`jdwp_get_events` is where it lands** — the agent pulls the log back on its own turn; replays every hit in order, across threads (`$oldValue` → `$newValue`, last 100)

<small class="warn">Perf: hot fields can dominate target-VM CPU — filter narrowly or keep sessions short.</small>

Note:
- Two primitives:
  - `jdwp_set_field_breakpoint` (watchpoint — suspends at the store) vs
  - `jdwp_set_field_logpoint` / line / exception logpoints (log, never stop)
- Synthetic bindings in the expression: `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`
- Logpoint expressions use the in-target compiler — any classpath method, lambdas, streams

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Token economy

- **`resume_until_event`** — server-side block until the next event; kills the resume → poll → poll loop
- **`get_breakpoint_context`** — thread + frames + locals + `this`-fields in **one** call (vs four)
- **`overview` / `clear`** — one read verb, one delete verb for all BP / watcher / mark state
- **Smart filtering** — junit / surefire / reflection frames collapsed by default
- **Deferred BPs + warning flags** — a BP that can't fire is flagged, not silently dead
- **`reconnect`** — recover a wedged session without re-arming everything

Individually small; together, the difference between a usable session and a burned context window.

Note:
- Smart filtering:
  - a 200-thread Tomcat renders in ~25 lines vs ~1000 verbose
  - stacks collapse junit/maven/reflection unless includeNoise=true
- clear requires an explicit `types` arg so an empty call can't wipe everything by accident
- None of this exists in IntelliJ — a human staring at a debugger UI doesn't pay the round-trip cost

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Skill recipes — the meta-tool

The bundled `java-debug` skill teaches the agent:

- **When** to attach (failing test vs running app)
- **How** to pick — line BP / logpoint / field watchpoint / exception BP
- **Which** tools to chain into a real workflow

And it doesn't stop at the plan — **each tool response is written to navigate**: what just happened, what to expect, which tool to reach for next. The skill is the route; the responses are the turn-by-turn directions along it.

Without it, the model freelances. With it, sessions look like an experienced developer's: attach, set targeted BPs, dump context, eval, mutate, verify, clean up, disconnect.

<small class="muted">Companion docs: <code>prerequisites.md</code>, <code>troubleshooting.md</code>.</small>

Note:
- Skill is what turns the tool pile into a learnable workflow
- Two layers of guidance:
  - the skill is the strategy (the route), each tool response is the tactics (turn-by-turn). 
  - The responses carry the next-step hint so the plan survives turn-to-turn even when the agent drifts

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## What an agent needs

A human in IntelliJ has eyes, a mouse, and patience. An agent has none of those — so the debugger is reshaped around how it actually works.

- **Tools shaped to the task** — not raw JDWP; one call returns the whole picture
- **Context & hints in the response** — named args, "do this next" pointers
- **Token-economical I/O** — right-sized payloads, no resume→poll loops
- **No blind hangs** — soft-waits return health + options; wait indefinitely, never frozen
- **Guard rails** — recursion guards, deferred BPs, stuck-state detection
- **A skill that teaches** — worked recipes turn a tool pile into a workflow

<small class="muted">↓ press Down for the deep dives</small>

Note:
- Hub slide for the back half — each bullet expands into one vertical deep-dive
- Framing throughout: every capability answers "why an agent needs this differently from a human"

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Bootstrap, redesigned for agents

- **One attach verb** — `wait_for_attach` polls every 200 ms and connects in the background; optional `port`, default 5005
- **No port guessing** — `jdwp://diagnose` / `jdwp://jvms` report which local JVMs are up and on which JDWP port (no `jps` / `lsof`)
- **Deferred breakpoints** — arm BPs on not-yet-loaded classes; they bind via class-prepare when the JVM loads them on its own
- **`reconnect` keeps the session** — re-attach to the last target; BP specs, conditions, logpoints, chains, watchers + their IDs survive (BP #7 stays #7)

Deferred BPs are the unlock under `mvn test`: the agent is too slow to race the classloader, so it arms everything *before* code runs.

Note:
- wait_for_attach lands at VM_START suspended and tells the agent "set BPs, then resume_until_event" — eval needs a real BP first
- forceLoad=true binds a BP immediately but runs `<clinit>` (early static init, masked lazy-load) — off by default
- Soft-wait: at the 30s ceiling, wait_for_attach / resume_until_event return `still_waiting` + JDI health + {wait_more, reconnect, abort} — unlimited waiting, never a blind freeze
- reconnect LOSES marks, object cache, last-thread, classpath cache (can't survive vm.dispose); target VM resumes after

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Expressions — compiled, not interpreted

<div class="cols">
<div>

- **Real Java → real bytecode** — Eclipse JDT (ECJ) compiles your expression in the **MCP server's** memory, then `defineClass` + invokes it in the target over JDI
- **Self-configuring** — discovers the target's classpath (Tomcat-aware) and a local JDK matching its Java version to compile against
- **Two modes** — a bare expression (`order.getTotal()`) or a `{ … return X; }` block with try/catch, locals, early return
- **Full scope** — reaches non-public `this` and package-private members
- **Recursion guard** — an eval that re-enters the breakpointed method won't deadlock

</div>
<div>

![Expression evaluation pipeline](images/eval-pipeline.png) <!-- .element: class="diagram" -->

</div>
</div>

Note:
- Only the final defineClass + invoke executes inside the target JVM; compilation and orchestration are MCP-server-side
- JdkDiscoveryService finds a local JDK matching the target's major version (used as `--system` so JDT resolves `java.*`)
- Block mode works in every eval slot: conditions, logpoints, watchers, assertions

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
- IntelliJ has dependent BPs but no log-only chains; marks-as-conditions has no IJ equivalent

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Logpoints & watchpoints

Observe without a single `println` or redeploy.

- **Logpoints** — line / exception / field — evaluate an expression, log it, **never suspend** the thread
- **Field watchpoints** — fire on every JVM-level store, incl. reflection (`Field.set`) and `Unsafe`; a line BP on the setter misses every reflective write (ORMs, DI, JSON mappers)
- Synthetic bindings: `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`
- All share the in-target compiler — any classpath method, lambdas, streams

**Anchor: test flight #6.** `displayName` is mutated via `Field.setAccessible(true) + Field.set(...)`; a line BP never fires, one field watchpoint catches it on the first write.

<small class="warn">Perf: hot fields can dominate target-VM CPU — filter narrowly or keep sessions short.</small>

Note:
- Logpoints replace println; race-condition debugging — stopping perturbs timing, logpoints don't
- Field logpoints are unique here (`$oldValue` / `$newValue`); IntelliJ has line logpoints only
- Field watchpoints CAN suspend or just log — the rogue-writer story is the clearest test-flight payoff

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Token economy

- **`resume_until_event`** — server-side block until the next event; kills the resume→poll→poll loop
- **`get_breakpoint_context`** — thread + frames + locals + `this`-fields in **one** call (vs four)
- **`overview` / `clear`** — one read verb, one delete verb for all BP / watcher / mark state
- **Smart filtering** — junit / surefire / reflection frames collapsed by default
- **Deferred BPs + warning flags** — a BP that can't fire is flagged, not silently dead
- **`reconnect`** — recover a wedged session without re-arming everything

Individually small; together, the difference between a usable session and a burned context window.

Note:
- Smart filtering: a 200-thread Tomcat renders in ~25 lines vs ~1000 verbose; stacks collapse junit/maven/reflection unless includeNoise=true
- clear requires an explicit `types` arg so an empty call can't wipe everything by accident
- None of this exists in IntelliJ — a human staring at a debugger UI doesn't pay the round-trip cost

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Skill recipes — the meta-tool

The bundled `java-debug` skill teaches the agent:

- **When** to attach (failing test vs running app)
- **How** to pick — line BP / logpoint / field watchpoint / exception BP
- **Which** tools to chain into a real workflow

Without it, the model freelances. With it, sessions look like an experienced developer's: attach, set targeted BPs, dump context, eval, mutate, verify, clean up, disconnect.

<small class="muted">Companion docs: <code>prerequisites.md</code>, <code>troubleshooting.md</code>.</small>

Note:
- Skill is what turns the tool pile into a learnable workflow
- Without it the model picks tools at random; with it sessions look professional

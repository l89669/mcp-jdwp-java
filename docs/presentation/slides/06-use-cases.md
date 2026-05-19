<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## What this gives an agent

Stock JDWP is enough for a human in IntelliJ. Agents need different leverage.

- **Stop guessing** — observe runtime state at the failure
- **Trace without stopping** — logpoints, not `println`
- **Catch rogue writers** — field watchpoints see what line BPs miss
- **Narrow to one bad path** — chains + conditions cut noise
- **Test hypotheses live** — eval Java, mutate locals, no restart
- **Save tokens** — `resume_until_event`, one-shot context, smart filtering
- **Teach the workflow** — skill recipes turn tools into a script

<small class="muted">↓ press Down for the deep dives</small>

Note:
- Hub slide for the back half — each bullet is one vertical
- Framing: every capability is paired with "why an agent needs it differently from a human"

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Stop guessing — observe the failure

- Maven launches with `suspend=y`; agent attaches before any code runs
- Stops on the failing assertion; reads locals, fields, walks the graph
- `jdwp_get_breakpoint_context` = thread + frames + locals + `this`-fields in **1 call** (vs 4)
- IntelliJ doesn't need that — a human eye does the merging

**Anchor: test flights #1–#5.** Five deliberately broken classes; each fails with a confusing message. Agent finds root cause via stepping, eval, conditional BP, exception BP, per-thread inspection.

Note:
- Token-economy lever: 1 call instead of 4, multiplied across a session
- IntelliJ's UI merges this visually — humans don't pay round-trip cost

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Trace without stopping

- **Logpoints** — line / exception / field — evaluate, log, never suspend
- All three share the in-target Java compiler (any classpath method, lambdas, streams)
- Field logpoints are unique here — `$oldValue` / `$newValue` synthetic bindings

```java
jdwp_set_logpoint(className="OrderService", lineNumber=42,
  expression="order.getId() + \" total=\" + order.getTotal()",
  condition="order.getTotal() > 1000")
```

Stopping a thread to peek at concurrency state hides the bug. Observe without perturbing timing.

Note:
- Race-condition debugging — stopping perturbs timing, logpoints don't
- Field logpoints unique to us; IntelliJ has line logpoints only

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Catch the rogue writer — field watchpoints

- Watch a field for **access · modification · both**
- Fires on every JVM-level store — incl. reflection (`Field.set`) and `Unsafe`
- Synthetic bindings: `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`
- A line BP on the setter misses every reflective write — ORMs, DI, JSON mappers do this all the time

**Anchor: test flight #6.** `displayName` is mutated via `Field.setAccessible(true) + Field.set(...)`. Line BP never fires; one field watchpoint catches it on the first hit.

<small class="warn">Perf: hot fields can dominate target-VM CPU — filter narrowly or use short sessions.</small>

Note:
- Marquee capability — the one with the clearest test-flight payoff
- Bypass mirrors real ORM / JSON-mapper patterns

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Narrow to one bad path — chains + conditions

- BP-B disabled until BP-A fires (**sticky** stays armed · **one-shot** self-disarms)
- Mix line · exception · field BPs freely as triggers or dependents
- Cycles rejected at registration
- `jdwp_diagnose` recognises the "armed but waiting on a non-fired trigger" stuck state

A BP in a hot loop floods the agent. Chain so it only arms once another BP has fired in the same request.

Note:
- IntelliJ has dependent BPs but no log-only chains
- Diagnose-time stuck detection saves agents from staring at a dead session

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Test a hypothesis — eval + mutate

<div class="cols">
<div>

- Arbitrary Java at any suspended BP
- In-target compile (Eclipse JDT) → `defineClass` → invoke
- Classpath discovered from the target VM (Tomcat-aware)
- `set_local` / `set_field` test "what if?" without a rebuild
- **Recursive eval guard** — eval that re-enters the BPed method won't deadlock

`assert_expression` returns OK / "MISMATCH" — saves a round trip.

</div>
<div>

![Expression evaluation pipeline](images/eval-pipeline.png) <!-- .element: class="diagram" -->

</div>
</div>

Note:
- Mutation is the IJ feature most underused by humans; agents love it
- Recursive guard is the cleverest piece in the codebase — sells engineering depth

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### Save tokens — agent ergonomics

- **`resume_until_event`** — server-side block until next event; kills the resume→poll loop
- **`get_breakpoint_context`** — thread + frames + locals + `this`-fields, one call
- **Smart filtering** — junit / surefire / reflection noise hidden by default
- **Marks** — name an object as `$label`, pin past GC, address across hits
- **Watchers** — persistent labelled expressions auto-evaluated on every hit
- **Unified `overview` / `clear`** — one mental model for "show / wipe"
- **Deferred BPs** — auto-promote when the class loads; "class not prepared" never reaches the agent

None of this exists in IntelliJ. A human staring at a debugger doesn't need it.

Note:
- Individually small, cumulatively the difference between a usable session and a burned context window

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

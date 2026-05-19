## Why bother?

<div class="cols">
<div>

### <span class="accent">Goal #1</span> · failing tests

- `mvn test -Dmaven.surefire.debug`<br/>→ JVM halts on port 5005
- Agent attaches **before** any user code runs
- Sets breakpoint at the failing assertion
- Reads locals, evaluates Java, walks object graphs
- **Sees runtime state, not stack traces.**

</div>
<div>

### <span class="accent">Goal #2</span> · running apps

- `-agentlib:jdwp=…,suspend=n,address=*:5005`
- Attach **while it's running**
- **Logpoints** + field watchpoints trace without stopping traffic
- **Conditional breakpoints** fire only on the bad request
- No restart, no extra logging, no print-statement-and-redeploy loop.

</div>
</div>

Note:
**Primary goal — failing tests** is the killer use case. Today most agents pattern-match on stack traces and hallucinate the cause. With JDWP they observe runtime state directly. The Maven-suspend integration matters because a slow agent process loses the attach race — by the time it connects, ordinary test runs are already over. `suspend=y` (which `-Dmaven.surefire.debug` sets by default) holds the JVM until we say go.

**Secondary goal — running apps** is the time-saver for "why is this only broken in staging" investigations. Logpoints replace println-and-redeploy. Field watchpoints (new) let you trace *who* mutates a field without instrumenting code. Conditional breakpoints mean the agent isn't flooded with thousands of irrelevant hits in a hot loop.

Why this is hard without us: raw JDWP/JDI gives you threads, stack frames, and variables — fine for a human staring at IntelliJ. An agent needs conditions, deferred breakpoints, expression evaluation, value mutation, recursive-evaluation guards — none of which JDI gives you out of the box.

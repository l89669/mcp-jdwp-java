<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Why bother?

<div class="cols">
<div>

### <span class="accent">Goal #1</span> · failing tests

<pre><code>mvn test -Dmaven.surefire.debug</code></pre>

- JVM halts on port 5005
- Agent attaches **before** any user code runs
- Breakpoint at the failing assertion
- Reads locals, evaluates Java, walks object graphs
- **Sees runtime state, not stack traces.**

</div>
<div>

### <span class="accent">Goal #2</span> · running apps

<pre><code>-agentlib:jdwp=…,suspend=n,address=*:5005</code></pre>

- Attach **while it's running**
- **Logpoints** + field watchpoints trace without stopping traffic
- **Conditional breakpoints** fire only on the bad request
- No restart, no extra logging, no print-and-redeploy loop

</div>
</div>

Note:
- Primary win: failing tests — agent stops hallucinating from stack traces, sees actual runtime state
- `suspend=y` (set by `-Dmaven.surefire.debug`) holds the JVM until the agent attaches — no race
- Secondary win: running apps — "broken only in staging" investigations without redeploy
- Logpoints replace `println`; field watchpoints find rogue writers; conditions tame hot-loop floods
- Raw JDI is fine for a human in IntelliJ — agents need more (conditions, eval, mutation, guards)

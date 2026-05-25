<!-- .slide: class="scenarios-slide" data-background-image="images/bg-content.png" data-background-size="cover" -->

## Designed for two scenarios

<div class="cols">
<div>

### <span class="accent">Scenario 1</span> · failing tests

<pre><code class="nohighlight">mvn test -Dmaven.surefire.debug</code></pre>

- JVM halts on port 5005
- Agent attaches **before** any user code runs
- Breakpoint at the failing assertion
- Reads locals, evaluates Java, walks object graphs
- **Sees runtime state, not stack traces.**

</div>
<div>

### <span class="accent">Scenario 2</span> · running apps

<pre><code class="nohighlight">-agentlib:jdwp=…,suspend=n,address=*:5005</code></pre>

- Attach **while it's running**
- No restart, no extra logging, no print-and-redeploy loop
- **Logpoints** + field watchpoints trace without stopping traffic
- **Conditional breakpoints** fire only on the bad request

</div>
</div>

Note:
- Designed with two scenarios in mind:
  - failing tests — agent stops hallucinating from stack traces, sees actual runtime state
    - `suspend=y` (set by `-Dmaven.surefire.debug`) holds the JVM until the agent attaches — no race
  - running apps — "broken only in staging" investigations without redeploy
    - Logpoints replace `println`; 
    - field watchpoints find rogue writers;
    - conditions tame hot-loop floods

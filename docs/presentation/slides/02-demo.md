<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Live demo — test flights

6 broken Java classes in `jdwp-sandbox/`. Each compiles, looks reasonable, **fails its test with a confusing message**.

<div class="cols">
<div>

**1 · Launch the failing JVM**

<pre><code class="nohighlight">cd jdwp-sandbox
mvn test -DskipTests=false \
    -Dtest=OrderProcessorTest \
    -Dmaven.surefire.debug</code></pre>

JVM halts on port **5005** — waits for the agent.

</div>
<div>

**2 · Prompt Claude Code**

> *"Use JDWP to debug `OrderProcessorTest` in the jdwp-sandbox module — the test is failing, find the root cause."*

Agent attaches, sets a BP at the failing assertion, walks state, names the culprit.

</div>
</div>

<small class="muted">↓ press Down for the full flight list</small>

Note:
- Pre-trigger flight #1 in a separate terminal; come back to it at the end
- Launch prompt copied verbatim from the README — keeps story consistent
- #6 is the headliner: `displayName` written via `Field.setAccessible(true)` → line BP misses, field watchpoint catches every store
- Scorecard: 6 = bug terminator. 0–1 = JVM wins.

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### The 6 flights

| # | Flight                       | Star tool                 |
|---|------------------------------|---------------------------|
| 1 | The Vanishing Pennies        | step + eval               |
| 2 | The Phantom Session          | hashCode assertion        |
| 3 | The Swallowed Exception      | exception breakpoint      |
| 4 | The Time Traveler's Config   | per-thread inspection     |
| 5 | The Audit That Lies          | stepwise balance eval     |
| 6 | The Field That Lies          | field watchpoint          |

<small class="muted">Just swap `OrderProcessorTest` for the test class of your chosen flight.</small>

Note:
- Backup story if live demo stalls: evita-db `OffsetIndexTest.generationalProofTest` off-by-one in `countDifference()` — found in minutes
- Each row pairs a bug with the tool that makes it tractable
- Flight #6 is the new field-watchpoint showcase

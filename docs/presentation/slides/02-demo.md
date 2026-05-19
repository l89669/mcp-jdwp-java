<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Live demo — test flights

6 broken Java classes in `jdwp-sandbox/`. Each compiles, looks reasonable, **fails its test with a confusing message**.

<div class="cols">
<div>

**1 · Grab the sandbox (clean slate)**

<pre><code class="nohighlight">wget https://github.com/FgForrest/mcp-jdwp-java/\
releases/latest/download/jdwp-sandbox.zip
unzip jdwp-sandbox.zip && cd jdwp-sandbox</code></pre>

**2 · Per flight, the agent runs**

<pre><code class="nohighlight">mvn test -DskipTests=false \
    -Dtest=&lt;TestClass&gt; \
    -Dmaven.surefire.debug</code></pre>

JVM halts on **:5005** until the agent attaches.

</div>
<div>

**3 · Hand Claude Code the gauntlet**

> *"Play cat-and-mouse with the jdwp-sandbox: 6 deliberately broken Java classes, each failing its own test. For every flight (#1 → #6) attach via JDWP, hunt down the root cause **without peeking at the source** until you've cracked it, then disconnect cleanly. Finish with a **scorecard** — one line per flight: bug name · root cause · the JDWP tool that nailed it."*

</div>
</div>

<small class="muted">↓ press Down for the flight roster</small>

Note:
- Step 1 keeps the audience on a fresh, parentless Maven project — no clone of the whole reactor
- Step 2 shows what CC will run six times — one JVM per flight, suspend=y each time
- Step 3 is the hero — copy-paste verbatim. The "no peeking at source" rule keeps the demo honest
- Pre-trigger the hunt in a separate terminal; come back to the scorecard at the end of the deck
- #6 (Field That Lies) is the headliner — field watchpoint catches a reflective write that line BPs miss

--

<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

### The 6 flights

| # | Flight                       | Test class                  | Star tool                 |
|---|------------------------------|-----------------------------|---------------------------|
| 1 | The Vanishing Pennies        | `OrderProcessorTest`        | step + eval               |
| 2 | The Phantom Session          | `SessionStoreTest`          | hashCode assertion        |
| 3 | The Swallowed Exception      | `EventBusTest`              | exception breakpoint      |
| 4 | The Time Traveler's Config   | `ConfigurationProviderTest` | per-thread inspection     |
| 5 | The Audit That Lies          | `TransferServiceTest`       | stepwise balance eval     |
| 6 | The Field That Lies          | `UserProfileTest`           | field watchpoint          |

<small class="muted">Each test class is named after the Java class containing the bug — standard JUnit convention.</small>

Note:
- Backup story if the live hunt stalls: evita-db `OffsetIndexTest.generationalProofTest` off-by-one in `countDifference()` — found in minutes
- Each row pairs a bug with the tool that makes it tractable
- Flight #6 is the field-watchpoint showcase

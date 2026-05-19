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

<pre><code class="nohighlight">Play cat-and-mouse with the jdwp-sandbox.

For each flight (#1 → #6):
  attach via JDWP, find the root cause
  WITHOUT peeking at source, disconnect.

End with a scorecard — one line per flight:
  bug name · root cause · JDWP tool used.</code></pre>

Copy-paste verbatim. No source-peeking is enforced by `CLAUDE.md` in the sandbox.

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

| # | Flight                       | Root cause to find                          | Star tool             |
|---|------------------------------|---------------------------------------------|-----------------------|
| 1 | The Vanishing Pennies        | logger truncates total via `(int)` cast     | step + eval           |
| 2 | The Phantom Session          | HashMap key mutated after insert            | hashCode assertion    |
| 3 | The Swallowed Exception      | wrapper exception hides root cause          | exception breakpoint  |
| 4 | The Time Traveler's Config   | partial init published before `init()`      | per-thread inspection |
| 5 | The Audit That Lies          | non-atomic transfer, mid-state snapshot     | stepwise balance eval |
| 6 | The Field That Lies          | reflective `Field.set` bypasses setter      | field watchpoint      |

Note:
- Backup story if the live hunt stalls: evita-db `OffsetIndexTest.generationalProofTest` off-by-one in `countDifference()` — found in minutes
- Each row pairs a bug with the tool that makes it tractable
- Flight #6 is the field-watchpoint showcase

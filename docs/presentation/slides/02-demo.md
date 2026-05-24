<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

## Live demo — test flights

9 broken Java classes in `jdwp-sandbox/`. Each compiles, looks reasonable, **fails its test with a confusing message**.

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

For each flight (#1 → #9):
  attach via JDWP, find the root cause
  WITHOUT peeking at source, disconnect.

End with a scorecard — one line per flight:
  bug · root cause · star tool · calls vs par · ⭐.</code></pre>

Copy-paste verbatim. No source-peeking is enforced by `CLAUDE.md` in the sandbox.

</div>
</div>

<small class="muted">↓ press Down for the flight roster</small>

Note:
- Step 1 keeps the audience on a fresh, parentless Maven project — no clone of the whole reactor
- Step 2 shows what CC will run nine times — one JVM per flight, suspend=y each time
- Step 3 is the hero — copy-paste verbatim. The "no peeking at source" rule keeps the demo honest
- Pre-trigger the hunt in a separate terminal; come back to the scorecard at the end of the deck
- #5 (The Field That Lies) is the headliner — a field watchpoint catches a reflective `Field.set` that line BPs miss

--

<!-- .slide: class="dense-table" data-background-image="images/bg-content.png" data-background-size="cover" -->

### The 9 flights

| # | Flight                     | Root cause to find                          | Star tool                 |
|---|----------------------------|---------------------------------------------|---------------------------|
| 1 | The Phantom Session        | HashMap key's hashCode mutated after insert | expression eval           |
| 2 | The Swallowed Exception    | byte-narrowed qty throws on a worker thread | exception BP + trigger    |
| 3 | The Time Traveler's Config | a background reaper thread resets it to 0   | field watchpoint + events |
| 4 | The Audit That Lies        | non-atomic transfer; snapshot taken mid-way | field watchpoint + stack  |
| 5 | The Field That Lies        | reflective `Field.set` bypasses the setter  | field watchpoint          |
| 6 | The Doppelgänger Cart      | `validate()` returns a copy; stages mutate it | marked instances (`$label`) |
| 7 | The Heisenbug Race         | read-modify-write race drops an increment   | logpoints (non-stopping)  |
| 8 | The Magic Patch            | trailing space makes `parseInt` throw       | runtime mutation (`set_local`) |
| 9 | The Polite Standoff        | A→B / B→A lock-ordering deadlock            | multi-thread inspection   |

<small class="muted">Each flight lists a **par** (min tool calls) — a flawless run is 27 ⭐ across the nine.</small>

Note:
- Backup story if the live hunt stalls: evita-db `OffsetIndexTest.generationalProofTest` off-by-one in `countDifference()` — found in minutes
- Each row pairs a bug with the tool group that's the path of least resistance; par is the elegant-solve tool-call count
- Field watchpoints anchor three flights (#3, #4, #5); #5 The Field That Lies is the showcase — a reflective `Field.set` a line BP can never catch
- #6 marks, #7 logpoints, #8 runtime mutation, #9 deadlock-via-thread-inspection-alone round out the tool surface

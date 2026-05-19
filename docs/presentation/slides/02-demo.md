## Live demo — test flights

The repo ships **6 deliberately broken Java classes** in `jdwp-sandbox/`.
Each compiles cleanly, looks reasonable, **fails its test with a confusing message**.

| #  | Bug                       | Difficulty | Star tool                  |
|----|---------------------------|------------|----------------------------|
| 1  | The Vanishing Pennies     | warm-up    | step over + eval           |
| 2  | The Phantom Session       | moderate   | hashCode assertion         |
| 3  | The Swallowed Exception   | moderate   | exception breakpoint       |
| 4  | The Time Traveler's Config| hard       | per-thread inspection      |
| 5  | The Audit That Lies       | hard       | stepwise balance eval      |
| 6  | The Field That Lies       | hard       | field watchpoint           |

<small>Kicking off scenario #1 in the background — we'll come back to it.</small>

Note:
Pre-trigger flight #1 in a separate terminal so it runs while we walk the rest of the deck. Return at the end with live output.

#6 is the headliner: `displayName` is written via `Field.setAccessible(true) + Field.set(...)`, so a line BP on the setter never fires. The field watchpoint catches every JVM-level store — reflection and `Unsafe` included.

Launch a flight: in Claude Code, "Use JDWP to debug `OrderProcessorTest` in jdwp-sandbox — find the root cause."

Backup if the live demo stalls: cite evita-db `OffsetIndexTest.generationalProofTest` — agent found an off-by-one in `countDifference()` (binarySearch insertion-point misused as the `>` loop bound) in minutes.

Scorecard: 6 = bug terminator. 0–1 = JVM wins.

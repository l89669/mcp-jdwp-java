<!-- .slide: data-background-image="images/bg-content.png" data-background-size="cover" -->

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
- Pre-trigger flight #1 in a separate terminal; come back to it at the end
- Launch: "Use JDWP to debug `OrderProcessorTest` in jdwp-sandbox — find the root cause"
- #6 is the headliner: `displayName` written via `Field.setAccessible(true)` → line BP misses, field watchpoint catches every store
- Backup story: evita-db `OffsetIndexTest.generationalProofTest` off-by-one in `countDifference()` — found in minutes
- Scorecard: 6 = bug terminator. 0–1 = JVM wins.

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
| 6  | The Field That Lies       | hard       | **field watchpoint**       |

<small>Kicking off scenario #1 in the background — we'll come back to it.</small>

Note:
Trigger the test-flight session before this slide (separate terminal). While it runs, walk through the rest of the deck. Return at the end with live output.

#6 is the new one (release 2.0): the public setter for `displayName` is unused — the field is written via reflection (`Field.setAccessible(true) + Field.set(...)`). A line BP on the setter never fires; a field watchpoint catches every JVM-level field store (including reflection and `Unsafe`). One JDI watchpoint, exact culprit.

Backup if the live demo stalls — describe a real case study from evita-db. The agent found a flake in `OffsetIndexTest.generationalProofTest`: an iteration with zero ops opened a gap in `historicalVersions = [1, 2, 4, 5]`. Bug in `countDifference()` at line 1794: when binarySearch missed, the insertion point was used as the loop's strict-`>` bound, dropping the contribution of the preceding generation (off by exactly 347 = v4's net adds). Fix: distinguish the "found" vs "not found" cases via `index >= 0 ? index + 1 : -index - 1` with a `>=` loop. Time to root cause: minutes, not hours.

How to launch a test flight: from repo root, in Claude Code, type "Use JDWP to debug `OrderProcessorTest` in the jdwp-sandbox module — the test is failing, find the root cause."

Scorecard: solve 6 = bug terminator. Solve 0-1 = the JVM is winning.

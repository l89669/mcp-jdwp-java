## Real session output

Flaky test in **evita-db** — `OffsetIndexTest.generationalProofTest`

The agent's final report ↓

--

### What it found

> The bug is in `OffsetIndex$VolatileValues.countDifference(catalogVersion)`
> at `OffsetIndex.java:1794`. Reproduces identically on `release_2025-7`
> and `dev`.

**Flaky trigger:** an iteration where `recordCountToTouch == 0`
(random.nextInt(1000) returns 0). Zero ops → `flush(N)` doesn't
materialize entries → version N never enters `historicalVersions`.
**A gap opens in the array.**

--

### What the agent observed at the failing assertion

`seed=33, txId=5, historyTxId=3`

- `historicalVersions = [1, 2, 4, 5]` — version 3 missing
- `volatileValues` keys `{1, 2, 4, 5}` — same gap
- `keyToLocations.size() = 782`
- PastMemory@v4: 347 adds · PastMemory@v5: 283 adds
- expected = 152, actual = 499 → **off by exactly 347** (v4's net adds)

--

### Diagnosed arithmetic bug

```java
int index = Arrays.binarySearch(hv, catalogVersion);          // returns -3
if (index != -1 && hvValues != null) {
    final int startIndex = index >= 0 ? index : -index - 1;   // 2
    for (int ix = hv.length - 1; ix > startIndex && ix >= 0; ix--) {
        diff -= ...;                                          // only ix=3
    }
}
```

- `index >= 0` → strict `>` correctly skips the found version.
- `index < 0` → `startIndex` is the **insertion point** → strict `>`
  wrongly skips v4 (`-347`).

--

### Proposed fix

```java
final int startIndex = index >= 0 ? index + 1 : -index - 1;
for (int ix = hv.length - 1; ix >= startIndex && ix >= 0; ix--) {
    diff -= ...;
}
```

Plus a symmetric fix one loop above (line 1772).

> *"JDWP session disconnected, test-support seed-override reverted.
> Want me to apply the fix?"*

Note:
Pause here. Make the point: the agent ran a real test, hit a real assertion failure, set targeted breakpoints, dumped internal state across multiple object generations, and reasoned about array boundary semantics — all without me leaving the chat. Time-to-root-cause was minutes, not hours.

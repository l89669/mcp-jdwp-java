# Field Breakpoints + Tool Surface Cleanup Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task.
>
> **TEMPORARY FILE** — delete after implementation. Do NOT reference this plan from production code (no `// see docs/plans/...` comments, no README links).

**Goal:** Add JDI field watchpoints (read/write breakpoints) to the MCP server, and harmonise the existing breakpoint tool surface (split exception logpoint, unify clear-by-id) in the same PR.

**Architecture:** Field watchpoints reuse the existing pending → active promotion machinery built for line and exception BPs. A new `FieldBreakpointInfo` holds one synthetic ID that maps to one or two underlying `WatchpointRequest`s (BOTH-mode creates both an `AccessWatchpointRequest` and a `ModificationWatchpointRequest`). The listener gains one new `handleWatchpointEvent` handler mirroring `handleBreakpointEvent`, with new synthetic bindings `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode` exposed to conditions and logpoint expressions. Capability lines (`canWatchFieldAccess`, `canWatchFieldModification`) are surfaced in `jdwp_diagnose`. Cleanup happens first so the new tools slot into a consistent surface.

**Tech Stack:** Java 17+, JDI (`com.sun.jdi`), Spring Boot 4 MCP, JSpecify + NullAway, JUnit 5 (Jupiter).

---

## Per-phase commit gate

Before each `git commit`, invoke the `dev-tools:code-quality-pipeline` skill against every Java class staged for that commit (one invocation per class — skip-on-empty makes unaffected classes cheap). Pass each agent the following scoping preamble alongside the usual target paths and CLAUDE.md content.

**Universal preamble (all 4 pipeline agents):**

> SCOPE — diff-only review
>
> This invocation gates a pending commit, not a full audit. Restrict your analysis to:
> 1. Lines added or modified in the pending commit. Discover them via `git diff --cached -- <target-file>` from the repo root.
> 2. Immediate blast-radius of those lines: direct callers and direct callees of changed methods; classes whose contract changed.
>
> Do NOT flag findings in pre-existing code this commit did not touch, even if visible. The question is "does this commit leave new problems behind?", not "is the whole file good?". If nothing falls in scope, return `Status: NO_WORK_NEEDED`.

**Extra clause for `bug-hunter-tdd`:**

> Bugs in pre-existing code are explicitly out of scope. Report only bugs introduced or unmasked by this commit. A latent bug that was already there before this commit and is not exercised by new code paths is NOT a finding here — it will be triaged separately.

Do NOT commit if the pipeline reports failures — fix and re-run. Markdown-only commits (Phase 3, Phase 5 cleanup) skip the pipeline; commits that touch sandbox Java code DO run it.

## End-state tool delta

- **Removed (3):** `jdwp_clear_breakpoint(class,line)`, `jdwp_clear_breakpoint_by_id`, `jdwp_clear_exception_breakpoint`
- **Renamed (1):** `jdwp_clear_breakpoint_by_id` → `jdwp_clear_breakpoint(id)` — now kind-agnostic
- **Added (4):** `jdwp_set_exception_logpoint`, `jdwp_set_field_breakpoint`, `jdwp_set_field_logpoint`, `jdwp_list_field_breakpoints`
- **Net:** 44 → 45 tools

---

# Phase 1 — Tool surface cleanup

Breaking API change; ship before Phase 2 so the new field tools land in the clean surface.

## Task 1.1: Split exception logpoint into its own tool

**Files:**
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java:2000-2096` (`jdwp_set_exception_breakpoint`)
- Test: `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetExceptionBreakpointTest.java` (existing — strip logOnly/expression cases)
- Test: `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetExceptionLogpointTest.java` (new)

**Step 1:** Strip the existing test cases that pass `logOnly`/`expression` to `jdwp_set_exception_breakpoint`. Move them into a new test class targeting `jdwp_set_exception_logpoint`. The expected output strings change minimally (header now says "logpoint" instead of "log-only mode").

**Step 2:** Run the moved tests — they should fail with "method not found" / compile error.

**Step 3:** Implement `jdwp_set_exception_logpoint` as a new `@McpTool` method in `JDWPTools.java`. Signature:

```java
@McpTool(description = "Set a non-stopping breakpoint that records an EXCEPTION_LOG event for each throw of the given exception type. The expression is evaluated against the throwing frame with $exception bound to the thrown object; the result is attached to the event. Use this for tracing exception flows in long-running services without halting traffic. If the exception class is not yet loaded, the logpoint is deferred and activates automatically on class load.")
public String jdwp_set_exception_logpoint(
    @McpToolParam(description = "Exception class name (e.g. 'java.sql.SQLException')") String exceptionClass,
    @McpToolParam(description = "Java expression evaluated on each hit; $exception bound to the thrown object") String expression,
    @McpToolParam(required = false, description = "Optional condition — only log when this evaluates to true") @Nullable String condition,
    @McpToolParam(required = false, description = "Log caught exceptions (default: true)") @Nullable Boolean caught,
    @McpToolParam(required = false, description = "Log uncaught exceptions (default: true)") @Nullable Boolean uncaught,
    @McpToolParam(required = false, description = "Optional ID of a trigger breakpoint — this logpoint stays disarmed until the trigger fires. Sticky by default.") @Nullable Integer triggerBreakpointId,
    @McpToolParam(required = false, description = "If true, re-disarm after each hit so the next trigger fire re-arms it. Default: false (sticky).") @Nullable Boolean oneShot)
```

Internal: identical to current `jdwp_set_exception_breakpoint` logOnly-true branch but with `ExceptionBreakpointSpec.logOnly(exceptionClass, caught, uncaught, expression)`. Pass `condition` through — currently exception BPs have no condition; persist via `breakpointTracker.setCondition(id, condition)` (the existing per-ID metadata map already supports it; verify `JdiEventListener.handleExceptionEvent` honours the condition for the new log path — add the check if not).

**Step 4:** Strip `logOnly` and `expression` params from `jdwp_set_exception_breakpoint`. The implementation now always uses `ExceptionBreakpointSpec.suspending(...)`. Update description to remove the log-only language.

**Step 5:** Run all exception-BP tests; verify the renamed/moved cases pass and unchanged suspending-mode tests still pass.

```bash
./mvnw -pl jdwp-mcp-server test -Dtest='JDWPToolsSetException*'
```
Expected: PASS

**Step 6:** Run `/code-quality-pipeline` against:
- `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java`
- `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetExceptionBreakpointTest.java`
- `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetExceptionLogpointTest.java`

**Step 7:** Commit.

```bash
git add jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetExceptionBreakpointTest.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetExceptionLogpointTest.java
git commit -m "refactor: split jdwp_set_exception_logpoint out of jdwp_set_exception_breakpoint"
```

## Task 1.2: Unify clear-by-id across BP kinds; delete clear-by-location

**Files:**
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java:1615-1700` (delete `jdwp_clear_breakpoint(class,line)`)
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java:1785-1824` (rename `jdwp_clear_breakpoint_by_id` → `jdwp_clear_breakpoint`, route by kind)
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java:2098-2111` (delete `jdwp_clear_exception_breakpoint`)
- Test: `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsBugCaptureTest.java`, `JDWPToolsChainToolsTest.java`, `JDWPToolsNullParamDefaultsTest.java` (rename call sites)

**Step 1:** Grep call sites in tests:
```bash
grep -rn "jdwp_clear_breakpoint_by_id\|jdwp_clear_exception_breakpoint\|jdwp_clear_breakpoint(" jdwp-mcp-server/src/test/
```

**Step 2:** Write a new test case in `JDWPToolsBugCaptureTest` (or wherever fits) covering: `jdwp_clear_breakpoint(id)` accepts an exception BP id and removes it (cascades chain breaks, cleans pending state). This should fail before the rename.

**Step 3:** Inside `JDWPTools.java`:
1. Delete the location-keyed `jdwp_clear_breakpoint(className, lineNumber)` method entirely.
2. Rename `jdwp_clear_breakpoint_by_id` → `jdwp_clear_breakpoint`. Extend its body to route:
   - If `breakpointTracker.getBreakpoint(id) != null || getPendingBreakpoint(id) != null` → existing line BP path (cascade chain, `removeBreakpoint`, watchers).
   - Else if exception BP / pending exception BP exists → call `cascadeChainBreak(id) + removeExceptionBreakpoint(id)`. Inline what was inside `jdwp_clear_exception_breakpoint`.
   - Else if field BP / pending field BP exists (will be wired up in Phase 2) → call `cascadeChainBreak(id) + removeFieldBreakpoint(id)`. Add stub branch now that returns "Field BPs not yet implemented" — Phase 2.3 fills it in.
   - Else → `"Breakpoint %d not found"`.
3. Delete `jdwp_clear_exception_breakpoint`.

**Step 4:** Delete the helper `exceptionBreakpointHint(...)` if it has no remaining callers (grep).

**Step 5:** Update affected test call sites — replace every `jdwp_clear_breakpoint_by_id(...)` and `jdwp_clear_exception_breakpoint(...)` with `jdwp_clear_breakpoint(...)`. Drop any test that exercised the location-keyed form (their behavior is now covered by the by-id form).

**Step 6:** Run the affected test classes:
```bash
./mvnw -pl jdwp-mcp-server test -Dtest='JDWPToolsBugCaptureTest,JDWPToolsChainToolsTest,JDWPToolsNullParamDefaultsTest,JDWPToolsSetBreakpointTest,JDWPToolsSetExceptionBreakpointTest,JDWPToolsSetExceptionLogpointTest'
```
Expected: PASS.

**Step 7:** Run full test suite to catch unexpected callers:
```bash
./mvnw -pl jdwp-mcp-server test
```
Expected: PASS.

**Step 8:** Run `/code-quality-pipeline` against the modified files.

**Step 9:** Commit.

```bash
git add jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsBugCaptureTest.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsChainToolsTest.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsNullParamDefaultsTest.java
git commit -m "refactor: unify jdwp_clear_breakpoint(id) across line, exception, and field BPs"
```

---

# Phase 2 — Field breakpoints

## Task 2.1: BreakpointTracker — field BP state & API

**Files:**
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/BreakpointTracker.java`
- Test (new): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/BreakpointTrackerFieldBreakpointTest.java`

**Step 1:** Write the failing test class `BreakpointTrackerFieldBreakpointTest` covering:

```java
@Test
void registersAccessOnlyFieldBreakpoint() {
    final FieldBreakpointSpec spec = FieldBreakpointSpec.suspending(
        "com.x.Foo", "bar", FieldWatchMode.ACCESS, null, null, null);
    final AccessWatchpointRequest accessReq = mockAccessReq();
    final int id = tracker.registerFieldBreakpoint(spec, accessReq, null);
    assertThat(tracker.findFieldIdByRequest(accessReq)).isEqualTo(id);
    assertThat(tracker.getAllFieldBreakpoints()).containsKey(id);
}

@Test
void registersBothModeWithTwoRequestsSharingOneId() {
    final FieldBreakpointSpec spec = FieldBreakpointSpec.suspending(
        "com.x.Foo", "bar", FieldWatchMode.BOTH, null, null, null);
    final AccessWatchpointRequest accessReq = mockAccessReq();
    final ModificationWatchpointRequest modReq = mockModReq();
    final int id = tracker.registerFieldBreakpoint(spec, accessReq, modReq);
    assertThat(tracker.findFieldIdByRequest(accessReq)).isEqualTo(id);
    assertThat(tracker.findFieldIdByRequest(modReq)).isEqualTo(id);
}

@Test
void pendingFieldBreakpointPromotesOnClassLoad() {
    final FieldBreakpointSpec spec = FieldBreakpointSpec.suspending(
        "com.x.Foo", "bar", FieldWatchMode.MODIFICATION, null, null, null);
    final int id = tracker.registerPendingFieldBreakpoint(spec);
    assertThat(tracker.getAllPendingFieldBreakpoints()).containsKey(id);
    tracker.promotePendingFieldToActive(id, null, mockModReq());
    assertThat(tracker.getAllPendingFieldBreakpoints()).doesNotContainKey(id);
    assertThat(tracker.getAllFieldBreakpoints()).containsKey(id);
}

@Test
void removeFieldBreakpointDeletesBothRequestsForBothMode() { ... }

@Test
void resetClearsFieldBreakpointMaps() { ... }

@Test
void cleanupClassPrepareConsidersPendingFieldBreakpoints() { ... }
```

**Step 2:** Run the failing tests — expect compile errors for missing types.

**Step 3:** Add the new types to `BreakpointTracker.java`:

```java
public enum FieldWatchMode { ACCESS, MODIFICATION, BOTH }

public record FieldBreakpointSpec(
    String className,
    String fieldName,
    FieldWatchMode mode,
    @Nullable String condition,
    boolean logOnly,
    @Nullable String expression,
    @Nullable Long threadFilterId,
    @Nullable Long objectFilterId
) {
    public static FieldBreakpointSpec suspending(String className, String fieldName,
            FieldWatchMode mode, @Nullable Long threadFilterId, @Nullable Long objectFilterId,
            @Nullable String condition) {
        return new FieldBreakpointSpec(className, fieldName, mode, condition, false, null,
            threadFilterId, objectFilterId);
    }
    public static FieldBreakpointSpec logOnly(String className, String fieldName,
            FieldWatchMode mode, String expression, @Nullable Long threadFilterId,
            @Nullable Long objectFilterId, @Nullable String condition) {
        return new FieldBreakpointSpec(className, fieldName, mode, condition, true, expression,
            threadFilterId, objectFilterId);
    }
}

public static class FieldBreakpointInfo {
    private final FieldBreakpointSpec spec;
    @Nullable final AccessWatchpointRequest accessRequest;
    @Nullable final ModificationWatchpointRequest modificationRequest;
    public FieldBreakpointInfo(FieldBreakpointSpec spec,
            @Nullable AccessWatchpointRequest accessRequest,
            @Nullable ModificationWatchpointRequest modificationRequest) {
        this.spec = spec;
        this.accessRequest = accessRequest;
        this.modificationRequest = modificationRequest;
    }
    public FieldBreakpointSpec getSpec() { return spec; }
    @Nullable public AccessWatchpointRequest getAccessRequest() { return accessRequest; }
    @Nullable public ModificationWatchpointRequest getModificationRequest() { return modificationRequest; }
}

public static class PendingFieldBreakpoint {
    private final FieldBreakpointSpec spec;
    @Nullable private volatile String failureReason;
    public PendingFieldBreakpoint(FieldBreakpointSpec spec) { this.spec = spec; }
    public FieldBreakpointSpec getSpec() { return spec; }
    @Nullable public String getFailureReason() { return failureReason; }
    public void setFailureReason(@Nullable String reason) { this.failureReason = reason; }
}
```

**Step 4:** Add the maps and methods to the tracker:

```java
private final ConcurrentHashMap<Integer, FieldBreakpointInfo> fieldBreakpointsById = new ConcurrentHashMap<>();
private final ConcurrentHashMap<WatchpointRequest, FieldBreakpointInfo> fieldInfoByRequest = new ConcurrentHashMap<>();
private final ConcurrentHashMap<WatchpointRequest, Integer> fieldIdsByRequest = new ConcurrentHashMap<>();
private final ConcurrentHashMap<Integer, PendingFieldBreakpoint> pendingFieldBreakpointsById = new ConcurrentHashMap<>();

public int registerFieldBreakpoint(FieldBreakpointSpec spec,
        @Nullable AccessWatchpointRequest accessReq,
        @Nullable ModificationWatchpointRequest modReq) {
    if (accessReq == null && modReq == null) {
        throw new IllegalArgumentException("At least one of accessReq/modReq must be non-null");
    }
    final int id = idCounter.getAndIncrement();
    final FieldBreakpointInfo info = new FieldBreakpointInfo(spec, accessReq, modReq);
    fieldBreakpointsById.put(id, info);
    if (accessReq != null) { fieldInfoByRequest.put(accessReq, info); fieldIdsByRequest.put(accessReq, id); }
    if (modReq != null) { fieldInfoByRequest.put(modReq, info); fieldIdsByRequest.put(modReq, id); }
    return id;
}

@Nullable public FieldBreakpointInfo findFieldInfoByRequest(WatchpointRequest req) { ... }
@Nullable public Integer findFieldIdByRequest(WatchpointRequest req) { ... }
public Map<Integer, FieldBreakpointInfo> getAllFieldBreakpoints() { ... }

public synchronized int registerPendingFieldBreakpoint(FieldBreakpointSpec spec) { ... }
public List<Map.Entry<Integer, PendingFieldBreakpoint>> getPendingFieldBreakpointsForClass(String className) { ... }
public Map<Integer, PendingFieldBreakpoint> getAllPendingFieldBreakpoints() { ... }
public void promotePendingFieldToActive(int id,
        @Nullable AccessWatchpointRequest accessReq,
        @Nullable ModificationWatchpointRequest modReq) { ... }
public void markPendingFieldFailed(int id, @Nullable String reason) { ... }

public synchronized boolean removeFieldBreakpoint(int id) {
    final FieldBreakpointInfo info = fieldBreakpointsById.remove(id);
    if (info != null) {
        if (info.accessRequest != null) {
            fieldInfoByRequest.remove(info.accessRequest);
            fieldIdsByRequest.remove(info.accessRequest);
            deleteQuietlyOnRequest(info.accessRequest);
        }
        if (info.modificationRequest != null) {
            fieldInfoByRequest.remove(info.modificationRequest);
            fieldIdsByRequest.remove(info.modificationRequest);
            deleteQuietlyOnRequest(info.modificationRequest);
        }
        clearDependency(id);
        triggersFiredAtLeastOnce.remove(id);
        return true;
    }
    final PendingFieldBreakpoint pending = pendingFieldBreakpointsById.remove(id);
    if (pending != null) {
        cleanupClassPrepareRequestIfNeeded(pending.getSpec().className());
        clearDependency(id);
        triggersFiredAtLeastOnce.remove(id);
        return true;
    }
    return false;
}
```

Extend in lockstep — `cleanupClassPrepareRequestIfNeeded` (third class to check), `tryPromotePending` (third loop — see Step 5), `getEventRequestById` (return accessRequest if present, else modificationRequest), `isKnownBreakpointId`, `clearAllInMemoryStateLocked`, `clearAll(EventRequestManager)` (best-effort delete both requests), `reset()`.

**Step 5:** Extend `tryPromotePending` with a third loop mirroring the exception-BP one. Resolution: `refType.fieldByName(fieldName)` first; if null, walk `refType.allFields()` and disambiguate by class match. On ambiguity (>1 fields with that name reachable), `markPendingFieldFailed` with the ambiguity message. On success, create the JDI request(s) per mode, apply filters, call `disarmIfChained`, then `promotePendingFieldToActive`.

**Step 6:** Run the new tests — should pass.

```bash
./mvnw -pl jdwp-mcp-server test -Dtest=BreakpointTrackerFieldBreakpointTest
```

**Step 7:** Run all tracker tests — confirm no regression:

```bash
./mvnw -pl jdwp-mcp-server test -Dtest='BreakpointTracker*'
```

**Step 8:** Run `/code-quality-pipeline` on `BreakpointTracker.java` and the new test file.

**Step 9:** Commit.

```bash
git add jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/BreakpointTracker.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/BreakpointTrackerFieldBreakpointTest.java
git commit -m "feat: track field watchpoints in BreakpointTracker (no listener wiring yet)"
```

## Task 2.2: JdiEventListener — handle WatchpointEvent

**Files:**
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JdiEventListener.java`
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/EventHistory.java` (just doc string)
- Test (new): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JdiEventListenerFieldWatchpointTest.java`

**Step 1:** Write the failing listener test. Mirror `JdiEventListenerExceptionLogTest`. Cover:
- access event → suspending → `FIELD_ACCESS` recorded, lastBreakpointThread set, latch fired
- modification event → suspending → `FIELD_MODIFICATION` recorded
- logpoint mode → auto-resumes, `FIELD_LOGPOINT` recorded with expression result
- condition false → auto-resumes, no chain trigger fires
- reentrancy guard active → `FIELD_BREAKPOINT_SUPPRESSED`, auto-resume
- BOTH-mode: an access event AND a modification event on the same field both route to the same synthetic ID

**Step 2:** Run the failing tests — expect compile error or no-op.

**Step 3:** Extend `JdiEventListener.listen()` event dispatch — add a `WatchpointEvent` branch BEFORE the `BreakpointEvent` branch. Note: `WatchpointEvent` is its own type, not a subclass of `BreakpointEvent`, so ordering vs. the BP branch is moot, but keep it adjacent to its conceptual neighbours:

```java
} else if (event instanceof WatchpointEvent wpEvent) {
    if (handleWatchpointEvent(wpEvent)) {
        shouldSuspend = true;
    }
}
```

**Step 4:** Implement `handleWatchpointEvent`:

```java
/**
 * Mirrors {@link #handleBreakpointEvent} for field-access / field-modification watchpoints.
 * Synthetic bindings exposed to conditions and logpoint expressions:
 *   $oldValue   — value before the event (for modification, the value about to be overwritten;
 *                 for access, the value being read)
 *   $newValue   — value about to be written (ModificationWatchpointEvent only — referencing it
 *                 in an access-only expression is a compile error)
 *   $object     — instance the field belongs to (null for static fields)
 *   $fieldName  — String mirror of the field name
 *   $mode       — String mirror "access" or "modification"
 */
private boolean handleWatchpointEvent(WatchpointEvent event) {
    if (evaluationGuard.isEvaluating(event.thread())) {
        eventHistory.record(new EventHistory.DebugEvent("FIELD_BREAKPOINT_SUPPRESSED",
            String.format("Recursive field event on %s.%s suppressed (thread '%s' inside MCP evaluation)",
                event.field().declaringType().name(), event.field().name(), event.thread().name()),
            Map.of("class", event.field().declaringType().name(),
                "field", event.field().name(),
                "thread", event.thread().name())));
        return false;
    }

    final WatchpointRequest request = event.request() instanceof WatchpointRequest wr ? wr : null;
    final Integer bpId = request != null ? breakpointTracker.findFieldIdByRequest(request) : null;
    final BreakpointTracker.FieldBreakpointInfo info = request != null
        ? breakpointTracker.findFieldInfoByRequest(request) : null;
    if (bpId == null || info == null) {
        log.warn("[JDI] Untracked field watchpoint hit on {}.{}",
            event.field().declaringType().name(), event.field().name());
        return true;
    }

    final boolean isModification = event instanceof ModificationWatchpointEvent;
    final String modeStr = isModification ? "modification" : "access";
    final String className = event.field().declaringType().name();
    final String fieldName = event.field().name();
    final String threadName = event.thread().name();

    breakpointTracker.setLastBreakpointThread(event.thread(), bpId);

    final Map<String, Value> bindings;
    try {
        final VirtualMachine vm = event.virtualMachine();
        final Map<String, Value> b = new HashMap<>();
        if (event.valueCurrent() != null) {
            b.put("$oldValue", event.valueCurrent());
        }
        if (isModification) {
            b.put("$newValue", ((ModificationWatchpointEvent) event).valueToBe());
        }
        if (event.object() != null) {
            b.put("$object", event.object());
        }
        b.put("$fieldName", vm.mirrorOf(fieldName));
        b.put("$mode", vm.mirrorOf(modeStr));
        bindings = Map.copyOf(b);
    } catch (Exception bindEx) {
        log.warn("[JDI] Could not build bindings for field event: {}", bindEx.getMessage());
        return true;
    }

    final String condition = breakpointTracker.getCondition(bpId);
    if (condition != null && !evaluateConditionWithBindings(event.thread(), condition, bindings)) {
        return false;
    }

    final BreakpointTracker.FieldBreakpointSpec spec = info.getSpec();
    if (spec.logOnly()) {
        evaluateFieldLogpoint(event, bpId, spec, modeStr, className, fieldName, threadName, bindings);
        applyChainEffectsAfterHit(bpId, request);
        return false;
    }

    eventHistory.record(new EventHistory.DebugEvent(
        isModification ? "FIELD_MODIFICATION" : "FIELD_ACCESS",
        String.format("Field %s.%s %s on thread %s", className, fieldName, modeStr, threadName),
        Map.of("breakpointId", String.valueOf(bpId),
            "class", className, "field", fieldName,
            "mode", modeStr, "thread", threadName,
            "oldValue", String.valueOf(event.valueCurrent()))));

    log.info("[JDI] Field {} {} on {} (BP #{})", fieldName, modeStr, threadName, bpId);
    breakpointTracker.fireNextEvent();
    applyChainEffectsAfterHit(bpId, request);
    return true;
}

private void evaluateFieldLogpoint(WatchpointEvent event, int bpId,
        BreakpointTracker.FieldBreakpointSpec spec, String modeStr,
        String className, String fieldName, String threadName,
        Map<String, Value> bindings) {
    final String expression = spec.expression();
    if (expression == null) {
        eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT",
            String.format("Field %s.%s %s on thread %s [log-only]",
                className, fieldName, modeStr, threadName),
            Map.of("breakpointId", String.valueOf(bpId),
                "class", className, "field", fieldName,
                "mode", modeStr, "thread", threadName)));
        return;
    }
    try {
        final String resultStr = evaluateAndFormat(event.thread(), expression, bindings);
        eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT",
            String.format("Field %s.%s %s on thread %s [%s = %s]",
                className, fieldName, modeStr, threadName, expression, resultStr),
            Map.of("breakpointId", String.valueOf(bpId),
                "class", className, "field", fieldName,
                "mode", modeStr, "thread", threadName,
                "expression", expression, "result", resultStr)));
    } catch (Exception e) {
        eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT_ERROR",
            String.format("Field %s.%s %s on thread %s — error evaluating '%s': %s",
                className, fieldName, modeStr, threadName, expression, e.getMessage()),
            Map.of("breakpointId", String.valueOf(bpId),
                "class", className, "field", fieldName,
                "expression", expression,
                "error", String.valueOf(e.getMessage()))));
    }
}

private boolean evaluateConditionWithBindings(ThreadReference thread, String condition,
        Map<String, Value> bindings) {
    try {
        expressionEvaluator.configureCompilerClasspath(thread);
        final StackFrame frame = thread.frame(0);
        final Value result = expressionEvaluator.evaluate(frame, condition, bindings);
        if (result instanceof BooleanValue bv) return bv.value();
        // Boxed Boolean fallback (same as evaluateCondition)
        if (result instanceof ObjectReference o
            && "java.lang.Boolean".equals(o.referenceType().name())) {
            final Field vf = o.referenceType().fieldByName("value");
            if (vf != null && o.getValue(vf) instanceof BooleanValue bv) return bv.value();
        }
        log.warn("[JDI] Field condition '{}' returned non-boolean: {}. Suspending.", condition, result);
        return true;
    } catch (Exception e) {
        log.warn("[JDI] Error evaluating field condition '{}': {}. Suspending.", condition, e.getMessage());
        return true;
    }
}
```

**Step 5:** Extend `handleClassPrepareEvent` with a third promotion loop for `pendingFieldBreakpointsById`. Use the same field-resolution helper added to `BreakpointTracker.tryPromotePending` (extract into a static utility if duplicated).

**Step 6:** Update `EventHistory.java` doc string — add `FIELD_ACCESS`, `FIELD_MODIFICATION`, `FIELD_LOGPOINT`, `FIELD_LOGPOINT_ERROR`, `FIELD_BREAKPOINT_SUPPRESSED` to the documented type list.

**Step 7:** Run the new listener tests:

```bash
./mvnw -pl jdwp-mcp-server test -Dtest=JdiEventListenerFieldWatchpointTest
```

**Step 8:** Run full listener test suite for regression:

```bash
./mvnw -pl jdwp-mcp-server test -Dtest='JdiEventListener*'
```

**Step 9:** Run `/code-quality-pipeline` on `JdiEventListener.java`, `EventHistory.java`, new test.

**Step 10:** Commit.

```bash
git add jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JdiEventListener.java \
        jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/EventHistory.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JdiEventListenerFieldWatchpointTest.java
git commit -m "feat: handle WatchpointEvent in JdiEventListener with synthetic field bindings"
```

## Task 2.3: MCP tools — set, list, route clear

**Files:**
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java`
- Test (new): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetFieldBreakpointTest.java`
- Test (new): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetFieldLogpointTest.java`
- Test (new): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsListFieldBreakpointsTest.java`
- Test (modify): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsChainToolsTest.java` — add field-BP-as-trigger and field-BP-as-dependent cases

**Step 1:** Write failing tests for `jdwp_set_field_breakpoint`. One test per error path (each must produce a specific message):

```java
@Test void returnsErrorWhenModeUnknown() { ... assertThat(result).contains("Invalid mode 'sideways'. Use 'access', 'modification', or 'both'"); }
@Test void returnsErrorWhenObjectIdSuppliedForStaticField() { ... }
@Test void returnsErrorWhenObjectIdNotInCache() { ... }
@Test void returnsErrorWhenThreadIdNotFound() { ... }
@Test void returnsErrorWhenFieldDoesNotExist() { ... }
@Test void returnsErrorWhenFieldIsAmbiguous() { ... }
@Test void returnsErrorWhenVmDoesNotSupportAccessWatchpoint() { ... }
@Test void returnsErrorWhenVmDoesNotSupportModificationWatchpoint() { ... }
@Test void setsAccessOnlyFieldBreakpoint() { ... }
@Test void setsBothModeCreatesTwoRequestsUnderOneId() { ... }
@Test void deferredWhenClassNotLoaded() { ... }
@Test void chainedFieldBpIsDisarmedOnRegistration() { ... }
```

**Step 2:** Implement `jdwp_set_field_breakpoint` in `JDWPTools.java`. Skeleton:

```java
@McpTool(description = "Set a field watchpoint that fires on read (access), write (modification), or both. " +
    "By default suspends the thread for inspection. Performance warning: each watchpoint instruments " +
    "every read/write of the field across all instances, even with an objectId filter — avoid on hot fields. " +
    "Optional condition has $oldValue, $newValue (modification only), $object (null for static), $fieldName, " +
    "$mode bound. If the class is not yet loaded, the watchpoint is deferred.")
public String jdwp_set_field_breakpoint(
    @McpToolParam(description = "Fully qualified class name") String className,
    @McpToolParam(description = "Field name (must be declared on the class — inherited fields require fully-qualified declaring class)") String fieldName,
    @McpToolParam(description = "'access' | 'modification' | 'both'") String mode,
    @McpToolParam(required = false, description = "Suspend policy: 'all' (default), 'thread', 'none'") @Nullable String suspendPolicy,
    @McpToolParam(required = false, description = "Optional condition with $oldValue/$newValue/$object/$fieldName/$mode bindings") @Nullable String condition,
    @McpToolParam(required = false, description = "Optional thread filter — only fire on this thread") @Nullable Long threadId,
    @McpToolParam(required = false, description = "Optional instance filter — only fire on this cached object (errors for static fields)") @Nullable Long objectId,
    @McpToolParam(required = false, description = "Optional trigger BP — dependent stays disarmed until trigger fires") @Nullable Integer triggerBreakpointId,
    @McpToolParam(required = false, description = "If true, re-disarm after each hit") @Nullable Boolean oneShot) {
    // 1. parse + validate mode
    // 2. check vm.canWatchFieldAccess() / canWatchFieldModification() per mode → hard error with relaunch hint
    // 3. validate triggerBreakpointId if supplied
    // 4. parse suspendPolicy (default "all", same code as set_breakpoint)
    // 5. eager class load via jdiService.findOrForceLoadClass(className)
    //    - if null → register pending (with chain + condition); also register ClassPrepareRequest
    //    - else → resolve field via fieldByName + ambiguity check
    //      - if static and objectId != null → ERROR
    //      - if objectId != null → look up in cache, ERROR if missing
    //      - if threadId != null → look up live thread, ERROR if missing
    //      - create AccessWatchpointRequest and/or ModificationWatchpointRequest per mode
    //      - apply addInstanceFilter / addThreadFilter
    //      - setSuspendPolicy
    //      - registerFieldBreakpoint → register chain edge → setEnabled(true) on each request (only if no chain)
    // 6. return formatted summary
}
```

Mode parsing helper:
```java
private static @Nullable FieldWatchMode parseMode(@Nullable String mode) {
    if (mode == null) return null;
    return switch (mode.toLowerCase(Locale.ROOT)) {
        case "access" -> FieldWatchMode.ACCESS;
        case "modification" -> FieldWatchMode.MODIFICATION;
        case "both" -> FieldWatchMode.BOTH;
        default -> null;
    };
}
```

Field-resolution helper (with ambiguity check) — extract to `BreakpointTracker` or `JDIConnectionService`:

```java
sealed interface FieldResolutionResult {
    record Found(Field field) implements FieldResolutionResult {}
    record NotFound() implements FieldResolutionResult {}
    record Ambiguous(List<Field> candidates) implements FieldResolutionResult {}
}

static FieldResolutionResult resolveField(ReferenceType refType, String fieldName) {
    final List<Field> matches = refType.allFields().stream()
        .filter(f -> f.name().equals(fieldName))
        .toList();
    return switch (matches.size()) {
        case 0 -> new NotFound();
        case 1 -> new Found(matches.get(0));
        default -> new Ambiguous(matches);
    };
}
```

**Step 3:** Implement `jdwp_set_field_logpoint` — same skeleton with `expression` required, `logOnly=true`, no `suspendPolicy` (always SUSPEND_EVENT_THREAD for invokeMethod). Reuse parsing helpers.

**Step 4:** Implement `jdwp_list_field_breakpoints` — mirrors `jdwp_list_exception_breakpoints`. Render: id, class.field, mode, suspending|log-only, expression, condition, chain status, threadFilter, objectFilter.

**Step 5:** Wire up the field branch in `jdwp_clear_breakpoint(id)` (stub added in Task 1.2). Routes to `cascadeChainBreak(id) + breakpointTracker.removeFieldBreakpoint(id)`.

**Step 6:** Update `JDWPToolsChainToolsTest` — add tests for field BP as chain trigger and as chain dependent. The existing chain machinery should "just work" since `getEventRequestById` now resolves field BPs.

**Step 7:** Run the new and modified tests:

```bash
./mvnw -pl jdwp-mcp-server test -Dtest='JDWPToolsSetFieldBreakpointTest,JDWPToolsSetFieldLogpointTest,JDWPToolsListFieldBreakpointsTest,JDWPToolsChainToolsTest'
```

**Step 8:** Full suite:

```bash
./mvnw -pl jdwp-mcp-server test
```

**Step 9:** `/code-quality-pipeline` on `JDWPTools.java` + new test files.

**Step 10:** Commit.

```bash
git add jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetFieldBreakpointTest.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsSetFieldLogpointTest.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsListFieldBreakpointsTest.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsChainToolsTest.java
git commit -m "feat: add jdwp_set_field_breakpoint, jdwp_set_field_logpoint, jdwp_list_field_breakpoints"
```

## Task 2.4: Diagnose — capability lines + field BP perf flag

**Files:**
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/discovery/DiagnoseReportRenderer.java`
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDIConnectionService.java` (extend `ConnectionStatus`)
- Modify: `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java` (`buildFullDiagnosticReport`, `buildDiagnosticReport`)
- Test (modify): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/discovery/DiagnoseReportRendererTest.java`
- Test (modify): `jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsDiagnoseTest.java`

**Step 1:** Write the failing renderer test:

```java
@Test
void renderConnectionBlockEmitsCapabilityLines() {
    final String out = DiagnoseReportRenderer.renderConnectionBlock(
        new JDIConnectionStatusView(true, "localhost", 5005, Instant.now(), null, true, false),
        5005);
    assertThat(out).contains("Field access watchpoints:        supported");
    assertThat(out).contains("Field modification watchpoints:  unsupported");
    assertThat(out).contains("relaunch agent with canWatchFieldModification=y");
}
```

**Step 2:** Extend `JDIConnectionStatusView` with two booleans `canWatchFieldAccess`, `canWatchFieldModification`. Wire them through `JDIConnectionService.getConnectionStatus()` — when connected, call `vm.canWatchFieldAccess()` / `canWatchFieldModification()`; when disconnected, default false.

**Step 3:** Update `renderConnectionBlock` — when `connected=true`, append the Capabilities sub-block:

```
  Capabilities:
    Field access watchpoints:        supported
    Field modification watchpoints:  unsupported (relaunch agent with canWatchFieldModification=y)
```

**Step 4:** Extend the breakpoint summary inside `buildDiagnosticReport` with a "Field breakpoints: N active, M pending" line. When N ≥ 3 append `   ⚠ perf cost — each instruments every field access`.

**Step 5:** Run renderer and diagnose tests.

**Step 6:** `/code-quality-pipeline`.

**Step 7:** Commit.

```bash
git add jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/discovery/DiagnoseReportRenderer.java \
        jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDIConnectionService.java \
        jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/JDWPTools.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/discovery/DiagnoseReportRendererTest.java \
        jdwp-mcp-server/src/test/java/one/edee/mcp/jdwp/JDWPToolsDiagnoseTest.java
git commit -m "feat(diagnose): surface canWatchField* capabilities and field BP perf flag"
```

---

# Phase 3 — Documentation

## Task 3.1: README

**Files:**
- Modify: `README.md`

**Step 1:** Update "Why this exists" bullet list — add "Field watchpoints — break or trace on read/write of a specific field, with `$oldValue`/`$newValue` bindings".

**Step 2:** Update tool-count claim from 44 → 45 (search all occurrences: intro paragraph, section header, architecture table — at least 3 places).

**Step 3:** Add a new "Field breakpoints" section under "Features beyond standard JDWP", right after "Chained breakpoints":

```markdown
### Field breakpoints

```
jdwp_set_field_breakpoint(
  className="com.example.Order",
  fieldName="total",
  mode="modification",
  condition="$newValue != null && (Double)$newValue < (Double)$oldValue"
)
```

Stop on read (`access`), write (`modification`), or both (`both`) for any instance or class field. Inside conditions and logpoint expressions you have `$oldValue` (always), `$newValue` (modification events only), `$object` (the instance — null for static fields), `$fieldName`, and `$mode` ("access" or "modification") bound automatically. Useful for "who keeps mutating this field?" investigations where line breakpoints would require peppering every write site.

**Filters**: pass `objectId` to scope to one cached instance, `threadId` to scope to one thread. Static fields reject `objectId` with a hard error.

**Performance warning**: every field watchpoint instruments **every read/write of that field across all instances**, even with an `objectId` filter — the JVM still pays the bytecode interception cost; the filter just suppresses the event. `jdwp_diagnose` flags this when 3+ field watchpoints are active.

**Capability check**: `jdwp_diagnose` displays whether the connected JVM supports access and modification watchpoints. Some embedded JVMs disable them; HotSpot supports both by default.

Log-only variant `jdwp_set_field_logpoint(className, fieldName, mode, expression, …)` evaluates and records without suspending.
```

**Step 4:** Update tool reference table — add field-BP rows; rename/remove cleanup-affected rows. The Breakpoints section grows from 9 to 11 (added: `set_field_breakpoint`, `set_field_logpoint`, `set_exception_logpoint`, `list_field_breakpoints`; removed: `clear_breakpoint`-by-location, `clear_breakpoint_by_id`, `clear_exception_breakpoint`).

**Step 5:** Commit doc step now (lower risk to ship docs separately).

```bash
git add README.md
git commit -m "docs(readme): document field breakpoints and the tool-surface cleanup"
```

## Task 3.2: java-debug skill

**Files:**
- Modify: `skills/java-debug/SKILL.md`

**Step 1:** Frontmatter `description` — append `, field-mutation tracking` after `non-intrusive line/exception logpoints`.

**Step 2:** Frontmatter `when_to_use` — add triggers: `"who is mutating this field"`, `"why does this value keep changing"`, `"trace reads/writes of"`.

**Step 3:** Add two new recipes in the "Debugging Recipes" section:

**"Who keeps mutating this field?"**
```
A field has the wrong value but the code path to the assignment is unclear (reflection, setter
overloads, framework-driven proxies).

1. `jdwp_set_field_breakpoint("com.x.Order", "total", mode="modification")` — fires on every write.
2. Optionally narrow with a condition: `condition="$newValue != null && (Double)$newValue == 0.0"`
   stops only when the new value matches the bug shape.
3. `jdwp_resume_until_event` → at the hit, `jdwp_get_stack` reveals the mutator.
4. If many writes are legitimate and the bad one is rare: use the logpoint form first to find the
   call signature, then re-set a conditional BP for that exact shape.
```

**"Trace every read of a config flag"**
```
Used to debug why a feature flag appears active when it shouldn't be — find every read site.

1. `jdwp_set_field_logpoint("com.x.Config", "cacheEnabled", mode="access",
       expression="\"caller=\" + Thread.currentThread().getName()")` — auto-resumes.
2. Run the suspect workflow.
3. `jdwp_get_events(50)` → FIELD_LOGPOINT entries show every reader with thread context.
```

**Step 4:** Update existing exception-tracing recipe ("Trace exceptions without stopping the app") to call `jdwp_set_exception_logpoint` (Phase 1 rename).

**Step 5:** Update existing "Bug only at large input" recipe — add "Approach D: field watchpoint with `$newValue` condition" when the suspicious value lives on an object.

**Step 6:** Add to "Critical Gotchas":
- "**Field watchpoints carry a global perf cost.** Each instruments every read/write of that field across all instances, even with an `objectId` filter. Avoid on hot fields like `size` counters. `jdwp_diagnose` flags when 3+ are active."

**Step 7:** Commit.

```bash
git add skills/java-debug/SKILL.md
git commit -m "docs(skill): add field-watchpoint recipes and update for cleanup renames"
```

## Task 3.3: Expression evaluation doc

**Files:**
- Modify: `docs/EXPRESSION_EVALUATION.md`

**Step 1:** In "Logpoint and Conditional Breakpoint Evaluation" section, add a "Synthetic bindings" subsection listing all bindings made available by event kind:

```markdown
### Synthetic bindings

The evaluator injects these names into the wrapper class when the relevant event kind fires:

| Binding      | Available at                                       | Type    | Note                                     |
|--------------|----------------------------------------------------|---------|------------------------------------------|
| `$exception` | exception breakpoints / exception logpoints        | Object  | the thrown object                        |
| `$oldValue`  | field watchpoints (access + modification)          | Object  | value before the event                   |
| `$newValue`  | field watchpoints (modification only)              | Object  | value about to be written; compile error if referenced from an access-only expression |
| `$object`    | field watchpoints                                  | Object  | the instance — null for static fields    |
| `$fieldName` | field watchpoints                                  | String  | name of the field that fired             |
| `$mode`      | field watchpoints                                  | String  | "access" or "modification"               |
```

**Step 2:** In "Logpoint" subsection, mention `jdwp_set_exception_logpoint` next to `jdwp_set_logpoint`.

**Step 3:** Commit.

```bash
git add docs/EXPRESSION_EVALUATION.md
git commit -m "docs: document synthetic bindings for field watchpoints"
```

---

# Phase 4 — Sandbox flight #6

## Task 4.1: "The Field That Lies"

**Files:**
- Create: `jdwp-sandbox/src/main/java/one/edee/jdwp/sandbox/audit/Order.java`
- Create: `jdwp-sandbox/src/main/java/one/edee/jdwp/sandbox/audit/AuditLogger.java`
- Create: `jdwp-sandbox/src/main/java/one/edee/jdwp/sandbox/audit/OrderService.java`
- Create: `jdwp-sandbox/src/test/java/one/edee/jdwp/sandbox/audit/OrderServiceTest.java`
- Modify: `README.md` (add scorecard entry #6)

**Step 1:** Design the bug. `OrderService.placeOrder()` returns an `Order` with `audited=false` flag. The test asserts `audited == false` immediately after placing. Between placeOrder and the assertion, an audit logger background thread (or hook called by the logging framework) flips `audited=true` via reflection or direct field write — but the test assertion checks the wrong path expecting it to remain false. Bug is subtle because the field appears `private` and "obviously" untouchable.

The flavor should be **solvable in ~3 minutes with `jdwp_set_field_breakpoint("Order", "audited", mode="modification")`** and the stack trace reveals the surprising mutator.

**Step 2:** Implement the sandbox classes — keep them small (≤80 LOC total), compileable, deliberately bug-shaped.

**Step 3:** Write `OrderServiceTest` that fails with a misleading message like `expected audited=false but was true — order was unexpectedly flagged for audit`.

**Step 4:** Run the test, confirm it fails as expected.

```bash
./mvnw -pl jdwp-sandbox test -Dtest=OrderServiceTest -DskipTests=false
```

**Step 5:** Verify the bug is solvable by setting the field watchpoint manually (mentally walk through — actual JDWP run not required at this step).

**Step 6:** Add scorecard entry #6 to `README.md`:

```markdown
### #6 The Field That Lies

**Difficulty:** Moderate | **Test:** `OrderServiceTest` | **Package:** `audit`

**Symptom:** `expected audited=false but was true` — a freshly placed order is somehow marked as
audited before the test can even check.

**Hint:** Nothing in `OrderService.placeOrder()` writes to `audited`. Yet between return and assert,
it changes.

<details>
<summary><strong>Reveal root cause</strong></summary>

The audit logger is registered as a JUL log handler at static init. When the order is logged for
metrics, the handler reflectively flips `audited=true` to mark the order as having been through
audit. The mutation happens during a `logger.info(...)` call that looks like a no-op read.

**Debug path:** `jdwp_set_field_breakpoint("Order", "audited", mode="modification")`. The stack
trace at the hit reveals the JUL handler as the unexpected mutator.

</details>
```

Bump scorecard rows accordingly (5→6 total in the "Solved" table).

**Step 7:** `/code-quality-pipeline` on the new files + README.

**Step 8:** Commit.

```bash
git add jdwp-sandbox/src/main/java/one/edee/jdwp/sandbox/audit/ \
        jdwp-sandbox/src/test/java/one/edee/jdwp/sandbox/audit/ \
        README.md
git commit -m "feat(sandbox): add flight #6 — The Field That Lies (solvable with field watchpoint)"
```

---

# Phase 5 — Final verification

## Task 5.1: End-to-end checks

**Step 1:** Run the full test suite once more from a clean state:

```bash
./mvnw clean test
```
Expected: PASS.

**Step 2:** Build the JAR end-to-end:

```bash
./mvnw clean package -DskipTests
```
Expected: `jdwp-mcp-server/target/mcp-jdwp-java.jar` exists.

**Step 3:** Manual smoke test against a sandbox flight (optional but recommended):
1. Launch `OrderServiceTest` with `-Dmaven.surefire.debug`.
2. Connect and set the field BP from the recipe.
3. Verify the hit lands at the audit handler.

**Step 4:** Search for any stray references to removed tools in any file:

```bash
grep -rn "jdwp_clear_breakpoint_by_id\|jdwp_clear_exception_breakpoint" \
    --include="*.java" --include="*.md" .
```
Expected: zero hits.

**Step 5:** Delete this plan file (per the no-reference-from-code requirement):

```bash
rm docs/plans/field-breakpoints-and-cleanup.md
```

**Step 6:** Commit the deletion:

```bash
git add docs/plans/
git commit -m "chore: remove field-breakpoints implementation plan now that PR is complete"
```

**Step 7:** Open the PR — Johnny will request his preferred PR description shape at that point.

---

# Open issues / risks discovered during planning

- **`FakeJdwpServer`** likely does not model `WatchpointRequest` or `vm.canWatchFieldAccess/Modification`. Expect 30–60 min of test-harness work before Task 2.2 tests can run. If the harness uses Mockito for VM, simply stub the new methods.
- **Increment semantics in BOTH mode**: `field++` fires both an access and a modification event on the same bytecode. The synthetic ID is shared; `jdwp_get_events` will show two entries per increment. Document in the SKILL recipe text.
- **`$newValue` referenced in an access-only expression** — fails at expression compile time with the existing `"X cannot be resolved"` message. Already user-actionable; no special handling needed.
- **Tool count drift**: README mentions "44 tools" in at least three places (intro paragraph, section header, architecture component table). Grep before final commit.
- **`/code-quality-pipeline` command** — not registered in this repo's `.claude/commands/`. Confirm with Johnny what to run before the first commit gate.

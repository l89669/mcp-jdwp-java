package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.request.*;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central registry for breakpoints owned by the MCP server and a parking spot for the last thread
 * that hit a suspending event. JDI's `BreakpointRequest` has no user-facing identifier, so this
 * service mints synthetic monotonic integer IDs and exposes them to the MCP client.
 * <p>
 * Maintains four parallel state maps:
 * - `breakpointsById` — line breakpoints already bound to a JDI `BreakpointRequest`.
 * - `pendingBreakpointsById` — line breakpoints whose target class is not yet loaded.
 * - `exceptionBreakpointsById` — exception breakpoints bound to a JDI `ExceptionRequest`.
 * - `pendingExceptionBreakpointsById` — exception breakpoints whose exception class is not yet loaded.
 * <p>
 * Pending entries are promoted to active either by `JdiEventListener.handleClassPrepareEvent` (the
 * normal path) or by the {@link #tryPromotePending} safety net (called from {@link JDIConnectionService#getVM()}
 * before every tool call) for classes loaded before any debugger event was delivered.
 * <p>
 * Thread-safety: all public mutators are `synchronized`; the read-mostly state maps are
 * `ConcurrentHashMap` so listeners can iterate without contending with mutators. The `lastBreakpoint*`
 * fields are `volatile` for cross-thread visibility from the JDI listener thread to MCP worker threads.
 */
@Service
public class BreakpointTracker {
    private static final Logger log = LoggerFactory.getLogger(BreakpointTracker.class);

    /**
     * Monotonic synthetic ID source shared by every register-* call; reset by {@link #clearAll} / {@link #reset}.
     */
    private final AtomicInteger idCounter = new AtomicInteger(1);
    /**
     * Active line breakpoints keyed by synthetic ID; populated by {@link #registerBreakpoint}.
     */
    private final ConcurrentHashMap<Integer, BreakpointRequest> breakpointsById = new ConcurrentHashMap<>();
    /**
     * Reverse index for {@link #breakpointsById} keyed by JDI request identity (not equals — the JDI
     * implementation does not promise structural equality across requests). Populated and torn down
     * in lockstep with {@link #breakpointsById} so {@link #findIdByRequest} and
     * {@link #unregisterByRequest} are O(1) instead of scanning the values map. The JDI listener
     * thread calls {@code findIdByRequest} on every BP hit, so a linear scan here would be a hot
     * spot under high BP volume.
     */
    private final ConcurrentHashMap<BreakpointRequest, Integer> breakpointIdsByRequest = new ConcurrentHashMap<>();
    /**
     * Line breakpoints awaiting class load; populated by {@link #registerPendingBreakpoint} via `jdwp_set_breakpoint`.
     */
    private final ConcurrentHashMap<Integer, PendingBreakpoint> pendingBreakpointsById = new ConcurrentHashMap<>();
    /**
     * ClassPrepare requests keyed by class name; one per class with at least one pending BP referencing it.
     */
    private final ConcurrentHashMap<String, ClassPrepareRequest> classPrepareRequests = new ConcurrentHashMap<>();
    /**
     * Optional condition / logpoint expression metadata indexed by breakpoint ID.
     */
    private final ConcurrentHashMap<Integer, BreakpointMetadata> breakpointMetadata = new ConcurrentHashMap<>();
    /**
     * Active exception breakpoints keyed by synthetic ID; populated by {@link #registerExceptionBreakpoint}.
     */
    private final ConcurrentHashMap<Integer, ExceptionBreakpointInfo> exceptionBreakpointsById = new ConcurrentHashMap<>();
    /**
     * Reverse index for {@link #exceptionBreakpointsById} keyed by the JDI {@link ExceptionRequest}
     * identity. Hot path: {@link JdiEventListener#handleExceptionEvent} calls
     * {@link #findExceptionInfoByRequest} on every exception event, and log-only BPs matching broad
     * supertypes (e.g. {@code Throwable}) can fire frequently. Maintained in lockstep with the
     * primary map.
     */
    private final ConcurrentHashMap<ExceptionRequest, ExceptionBreakpointInfo> exceptionInfoByRequest = new ConcurrentHashMap<>();
    /**
     * Reverse index from JDI {@link ExceptionRequest} to its synthetic ID. Mirrors
     * {@link #breakpointIdsByRequest} so chain handlers can recover the user-facing ID from the
     * firing request without scanning {@link #exceptionBreakpointsById}.
     */
    private final ConcurrentHashMap<ExceptionRequest, Integer> exceptionIdsByRequest = new ConcurrentHashMap<>();
    /**
     * Exception breakpoints awaiting class load; promoted via {@link #promotePendingExceptionToActive}.
     */
    private final ConcurrentHashMap<Integer, PendingExceptionBreakpoint> pendingExceptionBreakpointsById = new ConcurrentHashMap<>();

    /**
     * Atomic snapshot of the last suspending JDI event: the firing {@link ThreadReference} paired
     * with the synthetic breakpoint ID (or {@code -1} sentinel for non-breakpoint events such as
     * exceptions). Stored in a single volatile field so readers cannot observe a torn pair from
     * two different writes. {@code null} until the first event lands or after a reset.
     */
    @Nullable
    private volatile LastBreakpoint lastBreakpoint;

    /**
     * Single-shot latch backing {@link JDWPTools#jdwp_resume_until_event(Integer)}.
     * Lifecycle: armed by {@link #armNextEventLatch()} immediately before `vm.resume()`,
     * counted down by {@link #fireNextEvent()} from the JDI listener thread when the next
     * suspending event (BP / step / exception) lands, and released-then-cleared by
     * {@link #clearAll(EventRequestManager)} / {@link #reset()} so a `jdwp_reset` or
     * `jdwp_disconnect` from another caller does not leave a waiter hanging.
     * <p>
     * `volatile` for cross-thread visibility; mutating methods that touch the field are
     * `synchronized` so the arm-then-fire ordering is atomic.
     */
    @Nullable
    private volatile CountDownLatch nextEventLatch;

    /**
     * Dependent → trigger relationship for chained breakpoints. A dependent BP is registered with
     * {@code setEnabled(false)} and stays disarmed until its trigger fires. With {@code oneShot=false}
     * (the default, "sticky" mode) the dependent stays armed forever after the first trigger fire;
     * with {@code oneShot=true} the dependent disarms again after each hit so the next trigger fire
     * re-arms it (IntelliJ-style). Re-engaging the chain after a sticky fire is done via
     * {@code jdwp_disarm_until_trigger} which simply disables the dependent again.
     */
    private final ConcurrentHashMap<Integer, TriggerLink> dependencyByDependent = new ConcurrentHashMap<>();
    /**
     * Reverse index for {@link #dependencyByDependent}: trigger ID → set of dependents waiting on
     * it. Hot path: the JDI listener queries this on every BP/exception event to decide who to arm.
     * Maintained in lockstep with the primary map; a {@link ConcurrentHashMap#newKeySet()} backed
     * set provides safe concurrent reads without contending with mutators.
     */
    private final ConcurrentHashMap<Integer, Set<Integer>> dependentsByTrigger = new ConcurrentHashMap<>();
    /**
     * BPs that have fired at least once since attach. Populated by {@link #markTriggerFired} from
     * the JDI listener after every BP/exception hit and queried by the pending → active promotion
     * path so a chained dependent whose trigger ALREADY fired comes up armed instead of disarmed.
     * Without this memory, a dependent that was registered while still pending would silently miss
     * its trigger's earlier fires. Cleared by {@link #reset} / {@link #clearAll} so a session-level
     * wipe resets the chain memory; per-BP cleanup is handled inline (see {@link #removeBreakpoint}).
     */
    private final Set<Integer> triggersFiredAtLeastOnce = ConcurrentHashMap.newKeySet();

    // ── Active breakpoint operations ──

    /**
     * Best-effort delete of a JDI event request — swallows any exception (e.g., VM already disconnected).
     */
    private static void deleteQuietly(EventRequestManager erm, EventRequest req) {
        try {
            erm.deleteEventRequest(req);
        } catch (Exception e) {
            // VM may already be disconnected
        }
    }

    /**
     * Register a breakpoint and return a synthetic integer ID.
     */
    public int registerBreakpoint(BreakpointRequest bp) {
        final int id = idCounter.getAndIncrement();
        breakpointsById.put(id, bp);
        breakpointIdsByRequest.put(bp, id);
        return id;
    }

    /**
     * Lookup a breakpoint by its synthetic ID.
     */
    @Nullable
    public BreakpointRequest getBreakpoint(int id) {
        return breakpointsById.get(id);
    }

    /**
     * Remove a breakpoint by ID — checks active first, then pending. Also clears any condition or
     * logpoint metadata associated with the synthetic ID, the chain edge that references the
     * removed BP as a dependent, and the "trigger has fired" flag if the removed BP was itself a
     * trigger — when the trigger goes away, its fire history must not influence future dependents
     * that happen to reuse the same synthetic ID.
     *
     * @return true if found and removed
     */
    public synchronized boolean removeBreakpoint(int id) {
        // Try active breakpoints first
        final BreakpointRequest bp = breakpointsById.remove(id);
        if (bp != null) {
            breakpointIdsByRequest.remove(bp);
            try {
                bp.virtualMachine().eventRequestManager().deleteEventRequest(bp);
            } catch (Exception e) {
                // VM may already be disconnected
            }
            breakpointMetadata.remove(id);
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }

        // Try pending breakpoints
        final PendingBreakpoint pending = pendingBreakpointsById.remove(id);
        if (pending != null) {
            cleanupClassPrepareRequestIfNeeded(pending.getClassName());
            breakpointMetadata.remove(id);
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }

        return false;
    }

    /**
     * Removes the in-memory tracking entry for the given JDI request via identity comparison and
     * clears any condition / logpoint metadata associated with its synthetic ID, so a freshly
     * minted BP that reuses the deleted ID cannot inherit the previous BP's condition or logpoint
     * expression.
     * <p>
     * Does NOT call `EventRequestManager.deleteEventRequest`. Callers (currently
     * {@link JDWPTools#jdwp_clear_breakpoint}) are responsible for deleting the underlying JDI request
     * before invoking this method, otherwise the request stays alive in the target VM.
     */
    public void unregisterByRequest(BreakpointRequest bp) {
        final Integer id = breakpointIdsByRequest.remove(bp);
        if (id != null) {
            breakpointsById.remove(id);
            breakpointMetadata.remove(id);
            clearDependency(id);
        }
    }

    /**
     * Find the synthetic ID for a given JDI BreakpointRequest. O(1) via the reverse index.
     *
     * @return the ID, or null if not tracked
     */
    @Nullable
    public Integer findIdByRequest(BreakpointRequest bp) {
        return breakpointIdsByRequest.get(bp);
    }

    /**
     * Return all tracked active breakpoints (unmodifiable view).
     */
    public Map<Integer, BreakpointRequest> getAllBreakpoints() {
        return Collections.unmodifiableMap(breakpointsById);
    }

    /**
     * Build a location map: "className:lineNumber" -> breakpoint ID (active only).
     */
    public Map<String, Integer> getBreakpointLocationMap() {
        final Map<String, Integer> map = new HashMap<>();
        for (Map.Entry<Integer, BreakpointRequest> entry : breakpointsById.entrySet()) {
            final Location loc = entry.getValue().location();
            final String key = loc.declaringType().name() + ':' + loc.lineNumber();
            map.put(key, entry.getKey());
        }
        return map;
    }

    /**
     * Clean shutdown variant: clears every state map (active line BPs, pending line BPs, exception BPs,
     * pending exception BPs, breakpoint metadata, ClassPrepare requests) and deletes the underlying
     * JDI event requests via `erm`. Also releases any pending `resume_until_event` waiter so a
     * `jdwp_reset` or `jdwp_disconnect` from another caller does not leave it hanging until timeout.
     * <p>
     * Counterpart of {@link #reset()}, which performs the same in-memory cleanup but skips the JDI
     * calls — used when the target VM is already gone.
     */
    public synchronized void clearAll(EventRequestManager erm) {
        // Release any awaiter BEFORE we touch state — see fireNextEvent() for why this matters.
        fireNextEvent();
        for (BreakpointRequest bp : breakpointsById.values()) {
            deleteQuietly(erm, bp);
        }
        for (ClassPrepareRequest cpr : classPrepareRequests.values()) {
            deleteQuietly(erm, cpr);
        }
        for (ExceptionBreakpointInfo info : exceptionBreakpointsById.values()) {
            deleteQuietly(erm, info.request);
        }
        clearAllInMemoryStateLocked();
    }

    /**
     * Wipes every in-memory map and resets the ID counter. Shared between {@link #clearAll} and
     * {@link #reset}; the only difference between the two is whether the underlying JDI requests
     * are deleted before the wipe. Caller MUST hold the tracker's intrinsic lock — both call sites
     * are {@code synchronized} methods so the helper does not re-acquire it.
     */
    private void clearAllInMemoryStateLocked() {
        breakpointsById.clear();
        breakpointIdsByRequest.clear();
        pendingBreakpointsById.clear();
        breakpointMetadata.clear();
        classPrepareRequests.clear();
        exceptionBreakpointsById.clear();
        exceptionInfoByRequest.clear();
        exceptionIdsByRequest.clear();
        pendingExceptionBreakpointsById.clear();
        dependencyByDependent.clear();
        dependentsByTrigger.clear();
        triggersFiredAtLeastOnce.clear();
        lastBreakpoint = null;
        idCounter.set(1);
    }

    // ── Pending breakpoint operations ──

    /**
     * Register a pending (deferred) breakpoint for a class not yet loaded.
     *
     * @param className          fully qualified class name to monitor for loading
     * @param lineNumber         source line number where the breakpoint should be set
     * @param suspendPolicy      JDI suspend policy constant (e.g. {@link EventRequest#SUSPEND_ALL})
     * @param suspendPolicyLabel human-readable label for the suspend policy (e.g. "all", "thread")
     */
    public synchronized int registerPendingBreakpoint(
        String className, int lineNumber, int suspendPolicy, String suspendPolicyLabel) {
        final int id = idCounter.getAndIncrement();
        pendingBreakpointsById.put(id, new PendingBreakpoint(className, lineNumber, suspendPolicy, suspendPolicyLabel));
        return id;
    }

    /**
     * Looks up a pending breakpoint by its synthetic ID.
     */
    @Nullable
    public PendingBreakpoint getPendingBreakpoint(int id) {
        return pendingBreakpointsById.get(id);
    }

    /**
     * Removes a pending breakpoint by ID and cleans up its ClassPrepareRequest if no longer needed.
     * Also drops any chain edge that referenced this BP as a dependent and any condition / logpoint
     * metadata recorded against its synthetic ID, so a direct removal of a pending BP leaves no
     * ghost state behind. Mirrors the cleanup performed by {@link #removeBreakpoint} for active
     * line BPs.
     *
     * @return true if found and removed
     */
    public synchronized boolean removePendingBreakpoint(int id) {
        final PendingBreakpoint removed = pendingBreakpointsById.remove(id);
        if (removed != null) {
            cleanupClassPrepareRequestIfNeeded(removed.getClassName());
            breakpointMetadata.remove(id);
            clearDependency(id);
            return true;
        }
        return false;
    }

    /**
     * Get all pending breakpoints for a given class name.
     */
    public List<Map.Entry<Integer, PendingBreakpoint>> getPendingBreakpointsForClass(String className) {
        return pendingBreakpointsById.entrySet().stream()
            .filter(e -> e.getValue().getClassName().equals(className))
            .toList();
    }

    /**
     * Returns all pending breakpoints (unmodifiable view).
     */
    public Map<Integer, PendingBreakpoint> getAllPendingBreakpoints() {
        return Collections.unmodifiableMap(pendingBreakpointsById);
    }

    /**
     * Promote a pending breakpoint to active: remove from pending, add to active with the same ID.
     */
    public void promotePendingToActive(int id, BreakpointRequest bp) {
        pendingBreakpointsById.remove(id);
        breakpointsById.put(id, bp);
        breakpointIdsByRequest.put(bp, id);
    }

    /**
     * Mark a pending breakpoint as failed (e.g., no executable code at line).
     */
    public void markPendingFailed(int id, @Nullable String reason) {
        final PendingBreakpoint pending = pendingBreakpointsById.get(id);
        if (pending != null) {
            pending.setFailureReason(reason);
        }
    }

    // ── ClassPrepareRequest tracking ──

    /**
     * Registers a ClassPrepareRequest so deferred breakpoints can be activated when the class loads.
     */
    public void registerClassPrepareRequest(String className, ClassPrepareRequest cpr) {
        classPrepareRequests.put(className, cpr);
    }

    /**
     * Checks whether a ClassPrepareRequest is already registered for the given class.
     */
    public boolean hasClassPrepareRequest(String className) {
        return classPrepareRequests.containsKey(className);
    }

    /**
     * Removes and returns the ClassPrepareRequest for the given class, or null if none exists.
     */
    @Nullable
    public ClassPrepareRequest removeClassPrepareRequest(String className) {
        return classPrepareRequests.remove(className);
    }

    /**
     * If no more pending breakpoints reference this class, delete the ClassPrepareRequest.
     */
    private void cleanupClassPrepareRequestIfNeeded(String className) {
        final boolean hasOthers = pendingBreakpointsById.values().stream()
            .anyMatch(p -> p.getClassName().equals(className))
            || pendingExceptionBreakpointsById.values().stream()
            .anyMatch(p -> p.getExceptionClass().equals(className));
        if (!hasOthers) {
            final ClassPrepareRequest cpr = classPrepareRequests.remove(className);
            if (cpr != null) {
                try {
                    cpr.virtualMachine().eventRequestManager().deleteEventRequest(cpr);
                } catch (Exception e) {
                    // VM may already be disconnected
                }
            }
        }
    }

    // ── Breakpoint metadata (conditions, logpoints) ──

    /**
     * Records a condition expression for the given breakpoint. Blank/null conditions are silently
     * ignored — no metadata row is created. Conditions are evaluated against frame 0 by
     * {@link JdiEventListener#evaluateCondition} on every BP hit; if the expression evaluates to
     * false the listener auto-resumes without notifying the user.
     */
    public void setCondition(int breakpointId, @Nullable String condition) {
        if (condition != null && !condition.isBlank()) {
            getOrCreateMetadata(breakpointId).condition = condition;
        }
    }

    /**
     * Returns the condition expression, or {@code null} if none is set.
     */
    @Nullable
    public String getCondition(int breakpointId) {
        final BreakpointMetadata meta = breakpointMetadata.get(breakpointId);
        return meta != null ? meta.condition : null;
    }

    /**
     * Marks the breakpoint as a logpoint by attaching an expression to evaluate on each hit.
     * Blank/null expressions are silently ignored — no metadata row is created. A non-null logpoint
     * expression flips the breakpoint's behaviour: {@link JdiEventListener#handleBreakpointEvent}
     * evaluates the expression via {@link JdiExpressionEvaluator}, records a
     * `LOGPOINT` (or `LOGPOINT_ERROR`) entry in {@link EventHistory}, and auto-resumes the thread.
     */
    public void setLogpointExpression(int breakpointId, @Nullable String expression) {
        if (expression != null && !expression.isBlank()) {
            getOrCreateMetadata(breakpointId).logpointExpression = expression;
        }
    }

    /**
     * Returns the logpoint expression, or {@code null} if not a logpoint.
     */
    @Nullable
    public String getLogpointExpression(int breakpointId) {
        final BreakpointMetadata meta = breakpointMetadata.get(breakpointId);
        return meta != null ? meta.logpointExpression : null;
    }

    /**
     * Returns {@code true} if the breakpoint has a logpoint expression.
     */
    public boolean isLogpoint(int breakpointId) {
        return getLogpointExpression(breakpointId) != null;
    }

    private BreakpointMetadata getOrCreateMetadata(int breakpointId) {
        return breakpointMetadata.computeIfAbsent(breakpointId, k -> new BreakpointMetadata());
    }

    // ── Exception breakpoint operations ──

    /**
     * Registers an active exception breakpoint and returns its synthetic ID. Twin of
     * {@link #registerBreakpoint} for the exception side of the state machine.
     *
     * @param req  the JDI exception request already created and enabled
     * @param spec the user-facing options bundle (class, caught/uncaught flags, logOnly, expression)
     */
    public int registerExceptionBreakpoint(ExceptionRequest req, ExceptionBreakpointSpec spec) {
        final int id = idCounter.getAndIncrement();
        final ExceptionBreakpointInfo info = new ExceptionBreakpointInfo(spec, req);
        exceptionBreakpointsById.put(id, info);
        exceptionInfoByRequest.put(req, info);
        exceptionIdsByRequest.put(req, id);
        return id;
    }

    /**
     * Looks up the {@link ExceptionBreakpointInfo} for a given JDI {@link ExceptionRequest}, or
     * {@code null} if the request is not tracked. O(1) via the reverse index. Used by
     * {@link JdiEventListener#handleExceptionEvent} to read the {@code logOnly} flag and the
     * optional log expression from the firing request on every exception event.
     */
    @Nullable
    public ExceptionBreakpointInfo findExceptionInfoByRequest(ExceptionRequest req) {
        return exceptionInfoByRequest.get(req);
    }

    /**
     * Returns the synthetic ID for a given JDI {@link ExceptionRequest}. O(1) via the reverse
     * index — used by chain handlers in {@link JdiEventListener} to recover the user-facing ID
     * from the firing request without scanning {@link #exceptionBreakpointsById}.
     */
    @Nullable
    public Integer findExceptionIdByRequest(ExceptionRequest req) {
        return exceptionIdsByRequest.get(req);
    }

    /**
     * Removes an exception breakpoint by ID — checks active first, then pending. Active removals
     * also delete the underlying JDI `ExceptionRequest`; pending removals additionally clean up the
     * `ClassPrepareRequest` if no other pending item still references the same exception class.
     * Also forgets the "trigger has fired" flag for the removed ID so a future BP that reuses the
     * synthetic ID does not inherit the previous BP's fire history.
     *
     * @return `true` if found and removed, `false` if no entry exists for the given ID
     */
    public synchronized boolean removeExceptionBreakpoint(int id) {
        final ExceptionBreakpointInfo info = exceptionBreakpointsById.remove(id);
        if (info != null) {
            exceptionInfoByRequest.remove(info.request);
            exceptionIdsByRequest.remove(info.request);
            try {
                info.request.virtualMachine().eventRequestManager().deleteEventRequest(info.request);
            } catch (Exception e) {
                // VM may already be disconnected
            }
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }
        final PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.remove(id);
        if (pending != null) {
            cleanupClassPrepareRequestIfNeeded(pending.getExceptionClass());
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }
        return false;
    }

    /**
     * Returns an unmodifiable snapshot of all currently-active exception breakpoints.
     */
    public Map<Integer, ExceptionBreakpointInfo> getAllExceptionBreakpoints() {
        return Collections.unmodifiableMap(exceptionBreakpointsById);
    }

    /**
     * Registers a pending exception breakpoint for a class not yet loaded. Twin of
     * {@link #registerPendingBreakpoint} for the exception side of the state machine. The caller
     * is expected to also register a `ClassPrepareRequest` so {@link JdiEventListener#handleClassPrepareEvent}
     * can promote it via {@link #promotePendingExceptionToActive}.
     */
    public synchronized int registerPendingExceptionBreakpoint(ExceptionBreakpointSpec spec) {
        final int id = idCounter.getAndIncrement();
        pendingExceptionBreakpointsById.put(id, new PendingExceptionBreakpoint(spec));
        return id;
    }

    /**
     * Returns every pending exception breakpoint that targets the given class name. Used by the
     * class-prepare handler to know which entries to promote when the class loads.
     */
    public List<Map.Entry<Integer, PendingExceptionBreakpoint>> getPendingExceptionBreakpointsForClass(String exceptionClass) {
        return pendingExceptionBreakpointsById.entrySet().stream()
            .filter(e -> e.getValue().getExceptionClass().equals(exceptionClass))
            .toList();
    }

    /**
     * Returns an unmodifiable snapshot of all currently-pending exception breakpoints.
     */
    public Map<Integer, PendingExceptionBreakpoint> getAllPendingExceptionBreakpoints() {
        return Collections.unmodifiableMap(pendingExceptionBreakpointsById);
    }

    /**
     * Promotes a pending exception breakpoint to active by removing it from the pending map and
     * inserting an {@link ExceptionBreakpointInfo} under the same synthetic ID. No-op if the ID is
     * unknown (e.g., the user removed it between class-prepare and promotion).
     */
    public void promotePendingExceptionToActive(int id, ExceptionRequest req) {
        final PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.remove(id);
        if (pending != null) {
            final ExceptionBreakpointInfo info = new ExceptionBreakpointInfo(pending.getSpec(), req);
            exceptionBreakpointsById.put(id, info);
            exceptionInfoByRequest.put(req, info);
            exceptionIdsByRequest.put(req, id);
        }
    }

    /**
     * Records why a pending exception breakpoint could not be activated (e.g., the exception class
     * exists but cannot be force-loaded). The pending entry stays in the map so the failure reason
     * is visible to `jdwp_list_exception_breakpoints`.
     */
    public void markPendingExceptionFailed(int id, @Nullable String reason) {
        final PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.get(id);
        if (pending != null) {
            pending.setFailureReason(reason);
        }
    }

    // ── Opportunistic promotion ──

    /**
     * Re-attempts to promote every pending breakpoint and pending exception breakpoint by
     * re-querying `vm.classesByName(...)`. This is the safety net for cases where
     * {@link ClassPrepareRequest} does not fire — most notably bootstrap classes loaded by the JVM
     * before any debugger event is delivered.
     * <p>
     * Called from {@link JDIConnectionService#getVM()} (every MCP tool call) and
     * from {@link JdiEventListener} (after every JDI event), so any user interaction
     * gives pending items another chance to bind. Best-effort; transient failures are logged at
     * debug and the item stays pending for the next retry.
     *
     * @return number of items promoted in this call
     */
    public synchronized int tryPromotePending(
        @Nullable JDIConnectionService jdiService,
        @Nullable ThreadReference preferredThread
    ) {
        if (jdiService == null) {
            return 0;
        }

        final VirtualMachine vm;
        final EventRequestManager erm;
        try {
            vm = jdiService.getRawVM();
            if (vm == null) {
                return 0;
            }
            erm = vm.eventRequestManager();
        } catch (Exception e) {
            return 0;
        }

        int promoted = 0;

        // Promote pending line breakpoints
        for (Map.Entry<Integer, PendingBreakpoint> entry :
            new ArrayList<>(pendingBreakpointsById.entrySet())) {
            final int id = entry.getKey();
            final PendingBreakpoint pending = entry.getValue();
            if (pending.getFailureReason() != null) {
                continue;
            }

            try {
                final ReferenceType refType = jdiService.findOrForceLoadClass(pending.getClassName(), preferredThread);
                if (refType == null) {
                    continue;
                }

                final List<Location> locations = refType.locationsOfLine(pending.getLineNumber());
                if (locations.isEmpty()) {
                    continue;
                }

                final BreakpointRequest bp = erm.createBreakpointRequest(locations.get(0));
                bp.setSuspendPolicy(pending.getSuspendPolicy());
                bp.enable();
                disarmIfChained(id, bp);
                promotePendingToActive(id, bp);
                promoted++;
                log.info("[Tracker] Opportunistically promoted pending breakpoint {} for {}:{}",
                    id, pending.getClassName(), pending.getLineNumber());
            } catch (AbsentInformationException e) {
                // Class loaded but no debug info — try again later in case a different version arrives
            } catch (Exception e) {
                log.debug("[Tracker] Failed to promote pending breakpoint {}: {}", id, e.getMessage());
            }
        }

        // Promote pending exception breakpoints
        for (Map.Entry<Integer, PendingExceptionBreakpoint> entry :
            new ArrayList<>(pendingExceptionBreakpointsById.entrySet())) {
            final int id = entry.getKey();
            final PendingExceptionBreakpoint pending = entry.getValue();
            if (pending.getFailureReason() != null) {
                continue;
            }

            try {
                final ReferenceType refType = jdiService.findOrForceLoadClass(pending.getExceptionClass(), preferredThread);
                if (refType == null) {
                    continue;
                }

                final ExceptionRequest exReq = erm.createExceptionRequest(
                    refType, pending.isCaught(), pending.isUncaught());
                // Always SUSPEND_EVENT_THREAD: even logOnly BPs need a brief suspend so the
                // listener can read the exception object / evaluate the optional expression.
                // Auto-resume happens in JdiEventListener.handleExceptionEvent.
                exReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                exReq.enable();
                disarmIfChained(id, exReq);
                promotePendingExceptionToActive(id, exReq);
                promoted++;
                log.info("[Tracker] Opportunistically promoted pending exception breakpoint {} for {}",
                    id, pending.getExceptionClass());
            } catch (Exception e) {
                log.debug("[Tracker] Failed to promote pending exception breakpoint {}: {}", id, e.getMessage());
            }
        }

        return promoted;
    }

    // ── Chain (dependency) operations ──

    /**
     * Registers a chain edge: {@code dependentId} is only armed after {@code triggerId} fires.
     * Stores the relationship in both the primary map and the reverse index. Does NOT touch the
     * underlying JDI request — callers are responsible for {@code setEnabled(false)} on the
     * dependent's request right after registering, so the dependent is disarmed from the very
     * first event the JVM might deliver.
     * <p>
     * Idempotent for the {@code dependent → trigger} edge — re-registering the same pair simply
     * replaces the {@link TriggerLink} (e.g., to toggle {@code oneShot}). If a previous trigger
     * existed for this dependent, it is silently overwritten and the old reverse-index entry is
     * cleaned up.
     * <p>
     * Validates that the trigger is still a known BP at registration time (closing a TOCTOU window
     * where the boundary-level existence check at {@link JDWPTools} could be invalidated by a
     * concurrent removal before the edge is written) and that adding the edge does not introduce a
     * cycle. Multi-hop cycles like {@code 2 → 1 → 2} are rejected by walking the existing graph
     * from {@code triggerId} up to a chain root; if {@code dependentId} is encountered along the
     * way the registration is refused.
     *
     * @throws ChainRegistrationException when the trigger no longer exists or the edge would form
     *                                    a cycle
     */
    public synchronized void registerDependency(int dependentId, int triggerId, boolean oneShot) {
        if (!isKnownBreakpointId(triggerId)) {
            throw ChainRegistrationException.triggerMissing(triggerId);
        }
        final List<Integer> cyclePath = findCyclePath(dependentId, triggerId);
        if (cyclePath != null) {
            throw ChainRegistrationException.cycle(cyclePath);
        }
        final TriggerLink previous = dependencyByDependent.put(dependentId, new TriggerLink(triggerId, oneShot));
        if (previous != null && previous.triggerId() != triggerId) {
            removeReverseIndexEntry(previous.triggerId(), dependentId);
        }
        dependentsByTrigger.computeIfAbsent(triggerId, k -> ConcurrentHashMap.newKeySet()).add(dependentId);
    }

    /**
     * Walks the existing {@code dependency → trigger} chain starting at {@code triggerId} and
     * follows {@link #dependencyByDependent} edges until a chain root (no further dependency) is
     * reached. If {@code dependentId} is encountered along the way, adding the new edge
     * {@code dependentId → triggerId} would close a cycle. Returns the chain that would form the
     * cycle (starting at {@code dependentId}, ending back at {@code dependentId}) when a cycle is
     * detected, or {@code null} when the new edge is safe to add. The walk is bounded by the size
     * of {@link #dependencyByDependent} so it terminates even in the (currently impossible)
     * presence of a pre-existing cycle.
     */
    @Nullable
    private List<Integer> findCyclePath(int dependentId, int triggerId) {
        final int maxSteps = dependencyByDependent.size() + 1;
        final List<Integer> path = new ArrayList<>();
        path.add(dependentId);
        path.add(triggerId);
        int cursor = triggerId;
        for (int step = 0; step < maxSteps; step++) {
            if (cursor == dependentId) {
                return path;
            }
            final TriggerLink upstream = dependencyByDependent.get(cursor);
            if (upstream == null) {
                return null;
            }
            cursor = upstream.triggerId();
            path.add(cursor);
        }
        return null;
    }

    /**
     * Removes the chain edge for {@code dependentId}. Returns the previous {@link TriggerLink} so
     * callers can decide whether to log a {@code CHAIN_BROKEN} event. No-op when the dependent has
     * no chain configured.
     */
    @Nullable
    public synchronized TriggerLink clearDependency(int dependentId) {
        final TriggerLink previous = dependencyByDependent.remove(dependentId);
        if (previous != null) {
            removeReverseIndexEntry(previous.triggerId(), dependentId);
        }
        return previous;
    }

    /**
     * Removes {@code dependentId} from the reverse-index set keyed by {@code triggerId} and drops
     * the now-empty entry. Callers must already hold the tracker's intrinsic lock — the helper
     * relies on the surrounding {@code synchronized} method to keep the get/remove sequence atomic
     * against concurrent {@link #registerDependency} / {@link #clearDependency} calls.
     */
    private void removeReverseIndexEntry(int triggerId, int dependentId) {
        final Set<Integer> deps = dependentsByTrigger.get(triggerId);
        if (deps != null) {
            deps.remove(dependentId);
            if (deps.isEmpty()) {
                dependentsByTrigger.remove(triggerId);
            }
        }
    }

    /**
     * Returns the trigger relationship for {@code dependentId}, or {@code null} if the BP is not
     * chained. O(1) lookup used by the listener after every BP/exception fire to decide whether the
     * firing BP should re-disarm itself (one-shot mode).
     */
    @Nullable
    public TriggerLink getDependencyOfDependent(int dependentId) {
        return dependencyByDependent.get(dependentId);
    }

    /**
     * Returns an unmodifiable snapshot of the dependent IDs waiting on {@code triggerId} (empty if
     * none). Used by the JDI listener on every BP/exception event to find who to arm. The returned
     * set is a defensive copy — NOT a live view — so the caller can iterate without worrying about
     * concurrent mutations from another {@link #registerDependency} / {@link #clearDependency} call.
     */
    public Set<Integer> getDependentsOfTrigger(int triggerId) {
        final Set<Integer> deps = dependentsByTrigger.get(triggerId);
        return deps == null ? Set.of() : Set.copyOf(deps);
    }

    /**
     * Drops every chain edge whose trigger is {@code triggerId} and returns the dependent IDs that
     * were detached. Called from the trigger-removal code path in {@link JDWPTools} so the caller
     * can arm each dependent and emit a {@code CHAIN_BROKEN} event per detached edge.
     */
    public synchronized Set<Integer> clearDependentsOfTrigger(int triggerId) {
        final Set<Integer> deps = dependentsByTrigger.remove(triggerId);
        if (deps == null) {
            return Set.of();
        }
        for (Integer depId : deps) {
            dependencyByDependent.remove(depId);
        }
        return Set.copyOf(deps);
    }

    /**
     * Returns the JDI {@link EventRequest} for the given synthetic ID — checks active line BPs and
     * active exception BPs (in that order). Returns {@code null} when the ID is pending, unknown,
     * or already removed. Used by chain-handling code that needs to call {@code setEnabled(...)}
     * without caring whether the underlying request is a line BP or an exception BP.
     */
    @Nullable
    public EventRequest getEventRequestById(int id) {
        final BreakpointRequest bp = breakpointsById.get(id);
        if (bp != null) {
            return bp;
        }
        final ExceptionBreakpointInfo info = exceptionBreakpointsById.get(id);
        return info != null ? info.getRequest() : null;
    }

    /**
     * Returns {@code true} when {@code id} matches any tracked breakpoint — active line, active
     * exception, pending line, or pending exception. Used by {@link JDWPTools} to validate trigger
     * IDs supplied to chain-aware tools without forcing the caller to query four separate maps.
     */
    public boolean isKnownBreakpointId(int id) {
        return getEventRequestById(id) != null
            || pendingBreakpointsById.containsKey(id)
            || pendingExceptionBreakpointsById.containsKey(id);
    }

    /**
     * If {@code id} is registered as a chain dependent, disable the supplied JDI request so the
     * dependent stays disarmed until its trigger fires. Used during pending-to-active promotion —
     * a dependent that was chained while still pending must come up disabled, otherwise the very
     * first event could fire before the trigger ever has.
     * <p>
     * Honours the "trigger already fired" memory: if the dependent's trigger has fired at least
     * once before the promotion happens, the dependent comes up armed instead of disarmed. This
     * catches the case where the user installs a chained dependent for a class that gets loaded
     * only AFTER the trigger has already executed once — without this check, the dependent would
     * silently miss subsequent firings until the trigger fires again.
     * <p>
     * Any exception from {@link EventRequest#setEnabled} is allowed to propagate so the caller's
     * surrounding try/catch can mark the promotion failed and emit the appropriate log entry; this
     * is the historical behaviour of both call sites.
     *
     * @return {@code true} when the request was disabled (the BP was chained and its trigger has
     *         not fired yet), {@code false} otherwise
     */
    boolean disarmIfChained(int id, EventRequest request) {
        final TriggerLink link = dependencyByDependent.get(id);
        if (link == null) {
            return false;
        }
        if (triggersFiredAtLeastOnce.contains(link.triggerId())) {
            // Trigger has already fired at least once — leave the dependent enabled so it catches
            // the next hit at its location. The sticky/one-shot distinction still applies on the
            // listener side, but at the promotion boundary the dependent is treated as armed.
            return false;
        }
        request.setEnabled(false);
        return true;
    }

    /**
     * Records that {@code triggerId} has fired at least once. Called by the JDI listener after
     * every BP/exception hit, regardless of whether the BP currently has any dependents — a
     * future {@link #registerDependency} call may add one, and the dependent should still benefit
     * from the historical fire.
     * <p>
     * Note: {@link #registerDependency} deliberately does NOT auto-arm a freshly-registered
     * dependent even if {@code hasTriggerFired(triggerId)} is true. Arming-on-registration would
     * violate the user's explicit intent to "wait for the next trigger fire" — the persistence is
     * a safety net for pending → active promotion, not a shortcut for the chain CRUD path.
     */
    public void markTriggerFired(int triggerId) {
        triggersFiredAtLeastOnce.add(triggerId);
    }

    /**
     * Returns {@code true} when {@code triggerId} has fired at least once since attach (or since
     * the last {@link #reset} / {@link #clearAll}). Used by the pending → active promotion path
     * to decide whether a chained dependent should come up armed.
     */
    public boolean hasTriggerFired(int triggerId) {
        return triggersFiredAtLeastOnce.contains(triggerId);
    }

    // ── Thread tracking ──

    /**
     * Records which thread last fired a suspending event so {@link #getLastBreakpointThread} and
     * `jdwp_get_current_thread` can resolve a default thread when none is supplied. Pass `-1` for
     * `breakpointId` for non-breakpoint events (currently used by exception events).
     * <p>
     * Publishes the {@code (thread, id)} pair atomically via a single volatile reference so
     * concurrent readers cannot observe a crossed pair (thread from event N with id from event N+1).
     *
     * @param thread       the thread that hit the suspending event
     * @param breakpointId synthetic breakpoint ID, or {@code -1} for non-breakpoint events
     */
    public void setLastBreakpointThread(ThreadReference thread, int breakpointId) {
        this.lastBreakpoint = new LastBreakpoint(thread, breakpointId);
    }

    /**
     * Returns the last thread that hit a suspending event, or {@code null} if none.
     */
    @Nullable
    public ThreadReference getLastBreakpointThread() {
        final LastBreakpoint snapshot = lastBreakpoint;
        return snapshot != null ? snapshot.thread() : null;
    }

    /**
     * Returns the synthetic ID of the last breakpoint hit, or {@code null} if none.
     */
    @Nullable
    public Integer getLastBreakpointId() {
        final LastBreakpoint snapshot = lastBreakpoint;
        return snapshot != null ? snapshot.id() : null;
    }

    /**
     * Atomic snapshot of the last suspending JDI event. Callers that need a consistent
     * {@code (thread, id)} pair (e.g. {@code jdwp_get_current_thread}) must use this method
     * rather than the individual getters, which can otherwise observe values from two different
     * writes when called consecutively.
     *
     * @return the most recent {@link LastBreakpoint} snapshot, or {@code null} if no event has
     * fired since the last reset
     */
    @Nullable
    public LastBreakpoint getLastBreakpoint() {
        return lastBreakpoint;
    }

    /**
     * Arms a fresh single-shot latch that will be released the next time {@link #fireNextEvent()}
     * is called. Returns the latch — callers should arm BEFORE resuming the VM and then await on
     * the returned latch to avoid the race where the event fires between resume and arm.
     * <p>
     * Used by {@link JDWPTools#jdwp_resume_until_event(Integer)} to implement synchronous
     * "resume and wait for next stop". Replaces any previously-armed latch.
     */
    public synchronized CountDownLatch armNextEventLatch() {
        final CountDownLatch latch = new CountDownLatch(1);
        this.nextEventLatch = latch;
        return latch;
    }

    /**
     * Releases the currently-armed latch (if any) and clears it. Called by {@link JdiEventListener}
     * after every BP/step/exception event so that `jdwp_resume_until_event` can return.
     * <p>
     * `synchronized` so an arm + fire pair is atomic — without this lock, the JDI listener thread
     * could read a stale `nextEventLatch`, count down the wrong latch, and leave a fresh awaiter
     * hanging.
     */
    public synchronized void fireNextEvent() {
        final CountDownLatch latch = this.nextEventLatch;
        if (latch != null) {
            latch.countDown();
            this.nextEventLatch = null;
        }
    }

    /**
     * Best-effort, in-memory-only state wipe. Counterpart to {@link #clearAll(EventRequestManager)}
     * for the "VM is dead" path: skips the JDI `deleteEventRequest` calls because they would fail
     * anyway when the target VM is unreachable. Called from {@link JDIConnectionService#cleanupSessionState}
     * during disconnect cleanup.
     */
    public synchronized void reset() {
        // Release any awaiter BEFORE we touch state — see fireNextEvent() for why this matters.
        fireNextEvent();
        clearAllInMemoryStateLocked();
    }

    /**
     * Immutable {@code (thread, id)} pair published atomically in {@link #lastBreakpoint}. Using a
     * record means readers either see a complete pair from one write or no pair at all — never a
     * crossed pair from two different writes.
     */
    public record LastBreakpoint(ThreadReference thread, Integer id) {
    }

    /**
     * Signals that {@link #registerDependency} refused a chain edge. Two reasons are distinguished:
     * the trigger no longer exists at registration time (atomic validation closing the TOCTOU
     * window with the boundary check) or adding the edge would close a cycle (multi-hop cycle
     * detection). Carries enough context for {@link JDWPTools} to translate the rejection into a
     * user-facing error string without re-walking the graph.
     */
    public static class ChainRegistrationException extends RuntimeException {
        public enum Reason {MISSING_TRIGGER, CYCLE}

        private final Reason reason;
        private final int triggerId;
        @Nullable
        private final List<Integer> cyclePath;

        private ChainRegistrationException(Reason reason, int triggerId, @Nullable List<Integer> cyclePath,
                                           String message) {
            super(message);
            this.reason = reason;
            this.triggerId = triggerId;
            this.cyclePath = cyclePath;
        }

        static ChainRegistrationException triggerMissing(int triggerId) {
            return new ChainRegistrationException(Reason.MISSING_TRIGGER, triggerId, null,
                String.format("Trigger breakpoint #%d no longer exists", triggerId));
        }

        static ChainRegistrationException cycle(List<Integer> path) {
            final StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) {
                    sb.append(" → ");
                }
                sb.append('#').append(path.get(i));
            }
            return new ChainRegistrationException(Reason.CYCLE, path.get(path.size() - 1),
                List.copyOf(path),
                String.format("Adding this dependency would create a cycle: %s", sb));
        }

        public Reason reason() {
            return reason;
        }

        public int triggerId() {
            return triggerId;
        }

        @Nullable
        public List<Integer> cyclePath() {
            return cyclePath;
        }
    }

    /**
     * Chain edge metadata: which trigger BP must fire before the dependent is armed, plus the
     * {@code oneShot} flag controlling whether the dependent re-disarms itself after each hit.
     * <ul>
     *   <li>{@code oneShot=false} (default, "sticky"): the dependent stays armed forever after the
     *       trigger first fires. To re-engage the chain, call {@code jdwp_disarm_until_trigger}.</li>
     *   <li>{@code oneShot=true} (IntelliJ-style): after each dependent fire, the listener disarms
     *       the dependent again, so the next trigger fire re-arms it.</li>
     * </ul>
     */
    public record TriggerLink(int triggerId, boolean oneShot) {
    }

    // ── Inner class ──

    /**
     * A breakpoint registered for a class that is not yet loaded by the JVM.
     * Will be promoted to an active breakpoint when the class loads.
     */
    public static class PendingBreakpoint {
        private final String className;
        private final int lineNumber;
        private final int suspendPolicy;
        private final String suspendPolicyLabel;
        @Nullable
        private volatile String failureReason;

        public PendingBreakpoint(String className, int lineNumber, int suspendPolicy, String suspendPolicyLabel) {
            this.className = className;
            this.lineNumber = lineNumber;
            this.suspendPolicy = suspendPolicy;
            this.suspendPolicyLabel = suspendPolicyLabel;
        }

        public String getClassName() {
            return className;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public int getSuspendPolicy() {
            return suspendPolicy;
        }

        public String getSuspendPolicyLabel() {
            return suspendPolicyLabel;
        }

        @Nullable
        public String getFailureReason() {
            return failureReason;
        }

        /**
         * Records why this pending breakpoint could not be activated (e.g., no executable code at line).
         */
        public void setFailureReason(@Nullable String failureReason) {
            this.failureReason = failureReason;
        }
    }

    /**
     * Metadata for a breakpoint: optional condition expression and/or logpoint expression.
     */
    public static class BreakpointMetadata {
        /**
         * Boolean expression evaluated against frame 0 by {@link JdiEventListener#evaluateCondition}; `null` until set.
         */
        @Nullable
        volatile String condition;
        /**
         * Logpoint expression — when non-null, the breakpoint auto-resumes and records the result to {@link EventHistory}.
         */
        @Nullable
        volatile String logpointExpression;
    }

    /**
     * Tracks an exception breakpoint created via JDI ExceptionRequest. When {@code spec.logOnly()}
     * is true, {@link JdiEventListener#handleExceptionEvent} records an {@code EXCEPTION_LOG} entry
     * and auto-resumes the firing thread instead of leaving it suspended; the optional
     * {@code spec.expression()} is evaluated against the throwing frame with {@code $exception}
     * bound to the thrown object and the result is attached to the log entry (or recorded as
     * {@code EXCEPTION_LOG_ERROR} on evaluation failure).
     */
    public static class ExceptionBreakpointInfo {
        final ExceptionRequest request;
        private final ExceptionBreakpointSpec spec;

        public ExceptionBreakpointInfo(ExceptionBreakpointSpec spec, ExceptionRequest request) {
            this.spec = spec;
            this.request = request;
        }

        public ExceptionBreakpointSpec getSpec() {
            return spec;
        }

        public String getExceptionClass() {
            return spec.exceptionClass();
        }

        public boolean isCaught() {
            return spec.caught();
        }

        public boolean isUncaught() {
            return spec.uncaught();
        }

        public boolean isLogOnly() {
            return spec.logOnly();
        }

        @Nullable
        public String getExpression() {
            return spec.expression();
        }

        public ExceptionRequest getRequest() {
            return request;
        }
    }

    /**
     * An exception breakpoint registered for a class that is not yet loaded by the JVM.
     * Will be promoted to an active exception breakpoint when the class loads.
     */
    public static class PendingExceptionBreakpoint {
        private final ExceptionBreakpointSpec spec;
        @Nullable
        private volatile String failureReason;

        public PendingExceptionBreakpoint(ExceptionBreakpointSpec spec) {
            this.spec = spec;
        }

        public ExceptionBreakpointSpec getSpec() {
            return spec;
        }

        public String getExceptionClass() {
            return spec.exceptionClass();
        }

        public boolean isCaught() {
            return spec.caught();
        }

        public boolean isUncaught() {
            return spec.uncaught();
        }

        public boolean isLogOnly() {
            return spec.logOnly();
        }

        @Nullable
        public String getExpression() {
            return spec.expression();
        }

        @Nullable
        public String getFailureReason() {
            return failureReason;
        }

        /**
         * Records why this pending exception breakpoint could not be activated (mirrors {@link PendingBreakpoint#setFailureReason}).
         */
        public void setFailureReason(@Nullable String failureReason) {
            this.failureReason = failureReason;
        }
    }

    /**
     * User-facing options bundle for an exception breakpoint. Captures the same flags accepted by
     * {@code jdwp_set_exception_breakpoint}, in a shape that travels through the active and pending
     * tracker records and across pending → active promotion without callers having to repeat
     * positional arguments. Construct via {@link #suspending} or {@link #logOnly} for the common
     * cases.
     */
    public record ExceptionBreakpointSpec(
        String exceptionClass,
        boolean caught,
        boolean uncaught,
        boolean logOnly,
        @Nullable String expression
    ) {
        /**
         * Default suspending behaviour: the throwing thread is parked at the throw site for
         * inspection and no expression is evaluated.
         */
        public static ExceptionBreakpointSpec suspending(String exceptionClass, boolean caught, boolean uncaught) {
            return new ExceptionBreakpointSpec(exceptionClass, caught, uncaught, false, null);
        }

        /**
         * Log-only behaviour: the listener records an {@code EXCEPTION_LOG} event and auto-resumes
         * the throwing thread. {@code expression} (optional, may be {@code null}) is evaluated
         * against the throwing frame with {@code $exception} bound to the thrown object — pass
         * {@code null} for pure log-only, or e.g. {@code "$exception.getMessage()"} for an enriched
         * trace.
         */
        public static ExceptionBreakpointSpec logOnly(String exceptionClass, boolean caught, boolean uncaught,
                                                      @Nullable String expression) {
            return new ExceptionBreakpointSpec(exceptionClass, caught, uncaught, true, expression);
        }
    }
}

# Changelog

All notable changes to the `jdwp-debugging` plugin are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/), and this
project adheres to [Semantic Versioning](https://semver.org/).

## [2.4.0] — 2026-05-24

### New — block-mode multi-statement expressions

Every place that takes a Java expression — `jdwp_evaluate_expression`,
`jdwp_assert_expression`, breakpoint conditions, logpoint expressions and
conditions, exception logpoints, field-watchpoint conditions/expressions, and
watchers — now also accepts a brace-wrapped statement body. Previously each
input had to be a single expression, so anything that needed an intermediate
local, a `try/catch`, or an early return could not be evaluated at a
breakpoint. Resolves #8.

- **`{ …; return X; }` block syntax** — when the trimmed input starts with `{`
  and ends with the matching `}`, it is spliced into the wrapper method body
  verbatim instead of being wrapped as a single expression. You write your own
  `return X;` to yield a value; a trailing `return null;` fallthrough guard
  keeps the wrapper type-correct if the block doesn't return on every path.
  Intermediate locals, `try/catch`, early returns, and loops are all available.
- **Literal-aware mode detection** — the brace match is tokenizer-aware and
  ignores braces inside string, char, and text-block literals, so `"{x}".length()`
  still routes through expression mode while
  `{ try { return foo(); } catch (Exception e) { return null; } }` routes
  through block mode.
- **Discoverable from any eval param** — all eight eval-bearing tool
  methods/params carry a "supports `{ …; return X; }` block syntax" suffix in
  their descriptions, so the feature surfaces wherever an eval-shaped parameter
  appears.
- **Auto-rewrite interaction** — the bare-field `this.field` auto-rewrite is
  skipped in block mode (an identifier-level rewriter cannot tell a field
  reference from a local declaration); block-mode users write explicit
  `this.field` / `_this.field`, which the keyword rewrite still handles.

## [2.3.0] — 2026-05-24

### New — deferred breakpoint/logpoint install diagnostics

When a breakpoint or logpoint targets a class that has not loaded yet, or a
line that compiles to more than one bytecode location, the install used to
succeed silently and leave you guessing why nothing fired. Two
no-behaviour-change diagnostics now make those cases visible. Resolves #9.

- **`CLASS_PREPARE` recorded in event history** — `jdwp_get_events` previously
  showed only `[VM_START, VM_DEATH]` for a session parked on a deferred
  breakpoint, so an agent could not tell "the class never loaded" from "the
  class loaded but the breakpoint never fired." The class-prepare event is now
  captured, making the distinction observable.
- **Multi-location warning** — when `locationsOfLine` returns more than one
  `Location` (typical for lambdas: one in the enclosing method, one in the
  synthetic `lambda$…$N`), the server emits a `BP_MULTI_LOCATION` event, a
  WARN log, and a `WARNING` suffix on the `jdwp_set_breakpoint` /
  `jdwp_set_logpoint` response. The bind logic is unchanged — it still binds
  the first location — so this is diagnostics only; a proper multi-location
  bind is tracked separately.

### Fixed — expression evaluation reaches a non-public `this` and its package-private members

Expression evaluation always emitted its wrapper class into a fixed
`mcp.jdi.evaluation` package, so a non-public `this` type and its
package-private fields were unreachable — the `this.field` auto-rewrite
short-circuited on the common case (e.g. a package-private
`BitmapSlicer.translator`). Resolves #7.

- **Wrapper emitted into `this`'s own package** when `this` is non-public and
  the package is addressable (not `java.*` / `javax.*` / `sun.*` / `jdk.*`,
  not a local/anonymous class). Same-package reachability then lets the
  wrapper dereference package-private types and members directly; the
  field-rewrite filter widens to public/protected/package-private (private
  still needs reflection).
- **Define-time fallback to the default package** — if a sealed package,
  module strong-encapsulation, or a restrictive classloader rejects
  `defineClass` for the application package, evaluation retries once in
  `mcp.jdi.evaluation`. The user expression never ran (the failure is in the
  define phase, before invoke), so the retry is safe and preserves the
  pre-target-package behaviour for public-only expressions.
- **Non-public interface types resolve to a reachable supertype** instead of
  dead-ending the declared-type walk.
- **Pipeline-exception subtype preserved** — a define-vs-invoke failure keeps
  its original `JdiEvaluationException` subtype and message instead of being
  flattened into a generic wrapper.

### Docs

- **Release workflow skill** — added a `release` skill capturing the
  plugin-release procedure (marketplace.json + CHANGELOG bump, annotated tag,
  cherry-pick/back-fill recovery), plus a fix to its predecessor-tag selection
  so release notes diff against the immediately-lower `v*` tag.

## [2.2.0] — 2026-05-23

### New — agent-driven JDI wedge recovery

When the target JVM stops responding to JDI commands (paused in a native
section, GC-stalled, frozen by another debugger client, or wedged behind a
deferred-class-load deadlock), the MCP server used to hang every subsequent
tool call indefinitely. Cancellation from the client was a no-op because the
worker was parked in a JDI native wait. The agent had no signal to
distinguish "the user hasn't hit my breakpoint yet" from "the JDI connection
is dead." Resolves #4.

Four cooperating pieces:

- **`JdiHealthMonitor`** — a daemon-thread service that observes JDWP
  traffic passively (every event drained by `JdiEventListener` brushes
  against `notifyTraffic`) and escalates to a single `vm.allThreads()`
  probe wrapped in `Future.get(5s)` after 30 s of silence. The snapshot
  (`RESPONSIVE` / `UNRESPONSIVE` / `DISCONNECTED` with last-traffic and
  last-probe timestamps) is read-only state for downstream renderers — the
  monitor never auto-recovers.
- **Soft wait protocol** — `jdwp_resume_until_event` and
  `jdwp_wait_for_attach` now lead their timeout responses with a structured
  `still_waiting` envelope citing the current JDI Health classification
  plus the `wait_more` / `reconnect` / `abort` options. No hard server-side
  backstop: the agent re-calls to wait more.
- **`jdwp_reconnect`** — `vm.dispose()`s the current connection and
  re-attaches to the last `host:port`, preserving the synthetic
  breakpoint-ID space, line / exception / field BP specs, conditions,
  logpoint expressions, chain edges, watchers, and event history. Marked
  instances, the object cache, last-suspended-thread context, and the
  classpath cache cannot survive `vm.dispose` and are dropped (the response
  enumerates what was preserved vs lost). A different target host:port
  needs `jdwp_connect`.
- **`jdwp_diagnose` extension** — a new JDI Health block renders status,
  last-traffic age, last-probe outcome, and the recommended action when
  the connection is wedged.

### Fixed — passive breakpoint registration (no more debugger-induced side effects)

Until this release, every `jdwp_set_*` tool (line, exception, field BPs and
logpoints) invoked `Class.forName(name)` inside the target VM when the
class was not yet visible to JDI. That changes target-application
behaviour the way a debugger never should: it triggers `<clinit>` early,
cascade-loads dependencies, can fire user breakpoints, and masks the
lazy-load diagnostics users attach a debugger to investigate. Resolves #3.

- **Default behaviour is now passive** — `JDIConnectionService.findLoadedClass`
  does `classesByName` + `allClasses` scan only (no `invokeMethod`).
  Unresolved classes are deferred via `ClassPrepareRequest`, matching
  IntelliJ / Eclipse / `jdb` semantics.
- **`forceLoad` opt-in** — all six set-breakpoint MCP tools accept a
  Boolean `forceLoad`; default `false`. Set `true` only when you need the
  bootstrap-class case for exception BPs (the primary motivator).
- **`BreakpointTracker.tryPromotePending`** now uses `findLoadedClass`
  exclusively; the unused `preferredThread` parameter is dropped.

### Fixed — deferred class-load deadlock in `BreakpointTracker.tryPromotePending`

`tryPromotePending` was `synchronized` and called `findOrForceLoadClass`
(which issues a target-VM `invokeMethod`) while holding the
`BreakpointTracker` monitor. The JDI event listener takes that same
monitor in `promotePendingFieldsForClass`, so a worker parked in
`invokeMethod` blocked the listener that had to deliver the reply,
permanently wedging the MCP server. Any subsequent MCP tool call queued
behind the worker on `JDIConnectionService`'s monitor, so the whole server
became unresponsive. Resolves #1.

- **`tryPromotePending` refactored** to snapshot the three pending maps
  under a brief synchronized block, drop the monitor before any JDI
  round-trip, and re-acquire it per entry for an atomic
  recheck-and-mutate.
- **`JDIConnectionService.findOrForceLoadClass`** split into three phases
  (lookup under monitor → `invokeMethod` outside monitor → post-invoke
  lookup on captured `vmRef`) so the same hazard cannot recur on the
  connection-service monitor.
- **`getVM()` restructured** to release its monitor before calling
  `tryPromotePending`, closing the reentrant-hold variant of the same
  cycle.
- **Two double-promotion races closed** — `promotePendingToActive` and
  `promotePendingExceptionToActive` are now `synchronized` and return
  `boolean`; `false` means "already promoted, caller must
  `erm.deleteEventRequest` the surplus request." All call sites updated.

### Fixed — review polish on the wedge-recovery feature

A round of code-review follow-ups before the release cut:

- **`jdwp_reconnect` leaves a recoverable tracker on attach failure** —
  active-map entries pointing at dead JDI request handles used to survive
  a failed reattach and trip `VMDisconnectedException` on the next
  `jdwp_reconnect`. The tracker is now restored to pure-pending state
  BEFORE the attach attempt.
- **`JdiHealthMonitor` probe / stop race closed** — the three snapshot
  publishes in `runActiveProbe` and the in-threshold publish in
  `probeTick` are wrapped in `synchronized(this)` blocks with a
  `vmRef.get() == vm` re-check, so a concurrent `stop()` cannot be
  overwritten back to `RESPONSIVE` / `UNRESPONSIVE`. `notifyTraffic()` is
  now `synchronized` and bails when `vmRef` is null, so a late
  event-listener drain after disconnect cannot revive a stopped monitor.
  `cancelProbeTask` switched from `cancel(false)` to `cancel(true)` to
  interrupt an in-flight probe promptly.
- **`vmDeathHook` detach window narrowed** during
  `reconnectPreservingSpecs` — the hook is now detached only around the
  listener `stop()` call (and re-attached in an inner `finally`) so any
  real `VMDeath` / `VMDisconnect` of the fresh VM during dispose, attach,
  listener start, or pending-BP promotion still invokes `notifyVmDied()`.
  The original intent — preventing `notifyVmDied → watcherManager.clearAll()`
  from firing during the intentional stop — is preserved.
- **Watchers preserved across `jdwp_reconnect`** — `JdiEventListener.stop()`
  invokes the VM-death hook; the hook now detaches around stop so the
  contract's "watchers survive" promise is honoured. Pinned by a new
  reconnect-watcher-preservation test.

## [2.1.2] — 2026-05-19

### Fixed — JDK discovery on SDKMAN / honors JAVA_HOME

Expression evaluation silently failed on machines where the local JDK was
installed via SDKMAN (typical macOS / Linux developer setup): the discovery
service only probed `/usr/lib/jvm`, `/opt/jdk-*`, and a few Windows paths,
so `~/.sdkman/candidates/java/17.0.18-tem/` never matched. The MCP server
logged `[ERROR] JDK path not discovered, cannot configure compiler` and
every `evaluate_expression` then returned `Compiler is not configured`.

- **SDKMAN paths now enumerated dynamically** —
  `~/.sdkman/candidates/java/<major>.*` entries are listed at discovery
  time and ordered with the newest patch first by numeric semver compare
  (so `17.0.18-tem` outranks `17.0.5-tem`, which a naive lexicographic
  sort would invert).
- **`JAVA_HOME` now used as a fast shortcut** — when set, the env var is
  honored ahead of the directory scan, but only after reading
  `<jdkHome>/release` and confirming the `JAVA_VERSION` major matches the
  target VM. A mismatched JAVA_HOME is skipped with a debug log instead of
  feeding the JDT compiler a wrong-version `--system` and producing
  cryptic class-file-version errors downstream.
- **Error message updated** to list `$JAVA_HOME`, `/opt/jdk-*`, and the
  SDKMAN path alongside the Windows / `/usr/lib/jvm` locations.

### Added — forensic elapsed-time logging on the hot path

So the next incident leaves enough breadcrumbs to localize the cost:

- `JDIConnectionService.discoverClasspath` logs `… in {}ms` on every
  exit branch (success, empty result, `JdkNotFoundException`, generic
  failure).
- `JdiExpressionEvaluator.evaluate` logs
  `Expression evaluated in {}ms (cache hit/miss)` on success and
  `Expression evaluation failed after {}ms: …` on exception. The
  cache-hit/miss tag separates compile-bound runs from invoke-bound ones.

## [2.1.1] — 2026-05-18

### Fixed — log file no longer dirties the user's working directory

The `mcp-jdwp-inspector.log` file used to land in whatever directory Claude
Code was launched from (typically the user's project root), because the
logback `FileAppender` resolved a relative path against CWD.

- **`.mcp.json` now passes `-DLOG_PATH=${CLAUDE_PLUGIN_ROOT}/logs/mcp-jdwp-inspector.log`**
  so plugin-launched runs write the log next to the plugin install. Logback
  creates the `logs/` subdirectory automatically.
- **`logback-spring.xml` fallback changed from CWD to `${java.io.tmpdir}`** —
  bare `java -jar` runs (tests, dev, debugging the server itself) now land
  the log in the OS temp dir instead of polluting CWD when `LOG_PATH` is
  unset.

`application.properties` documents the wiring so future readers know where
the log lives and why.

## [2.1.0] — 2026-05-18

### Fixed — output clarity and error envelopes (audit follow-ups)

A re-audit caught two leaks in the prior audit fix bundle plus a handful of
medium-severity polish items.

- **`jdwp_get_breakpoint_context` now uses the kind-aware event tag** —
  previously it still formatted `breakpoint=null` after a STEP or
  EXCEPTION snapshot (the F-RA2 sweep missed this third renderer).
- **`jdwp_evaluate_watchers` after STEP** — the STEP snapshot now
  inherits the BP id it stepped FROM, so watchers attached to that BP
  resolve at the step landing instead of silently emitting
  `No breakpoint ID available`.
- **Transport-loss exceptions now land on `[VM_DEATH]` / `[VM_GONE]`** —
  `SIGKILL` of the target JVM used to surface as
  `Error evaluating expression: Connection refused` (raw `IOException`).
  A new `isVmGone(Throwable)` predicate walks the cause chain matching
  `VMDisconnectedException`, `SocketException`, `EOFException`, plus
  observed message fragments (`Connection refused`, `Connection reset`,
  `Connection closed by peer`, `Broken pipe`, `Pipe closed`,
  `closed by the remote host`, `handshake failed`,
  `Bad file descriptor`). Walk is depth-bounded to defeat cause-chain
  cycles. `vmGoneEnvelope` walks to the deepest matching cause so the
  rendered reason names the actual transport failure instead of an outer
  wrapper's generic message.
- **STEP / EXCEPTION tag instead of stale `breakpoint=N`** — three
  renderers (`jdwp_resume_until_event`, `jdwp_get_current_thread`,
  `jdwp_get_breakpoint_context`) now show `via=step` / `via=exception`
  for STEP and EXCEPTION snapshots. BP snapshots keep `breakpoint=N`.

### Fixed — earlier audit bundle (already shipped in code; documenting here)

The fix bundle landed in code via the prior audit pass. Summary for the
release notes:

- Re-fetch `StackFrame` per watcher iteration so two watchers on one BP
  no longer crash the second with `InvalidStackFrameException`.
- `evaluate_expression` / `assert_expression` now thread marked-instance
  bindings so `$mark` identifiers resolve.
- Field watchpoint events bind `$oldValue` / `$newValue` / `$object`
  unconditionally — null-valued fields are observable.
- `BP_PROMOTION_FAILED` event recorded + surfaced inside `[VM_DEATH]`
  so deferred BPs on comment / blank lines report the failure to the
  agent instead of going dark.
- `resume_until_event` short-circuits via a `pendingFire` flag so a
  `step → otherTool → resume_until_event` sequence does not lose the
  STEP signal or overshoot the suspended state.
- `jdwp_attach_watcher` rejects unknown BP ids with an `[ERROR]`
  envelope instead of silently registering an inert watcher.
- `jdwp_diagnose` suppresses the disconnected `[DIAGNOSTIC]` block and
  filters the MCP server's own PID out of the JVM list.

### Output token savings

Output formats tightened so common turns spend fewer tokens:

- `jdwp_get_threads` defaults to a compact one-line-per-thread table
  (~25 lines for a 200-thread Tomcat vs ~1000 lines verbose); pass
  `verbose=true` for the legacy block format.
- `jdwp_overview` hides empty category headers by default; pass
  `showEmpty=true` to restore them.
- `jdwp_attach_watcher`, `jdwp_get_version`, empty `jdwp_get_events`,
  and empty `jdwp_reset` collapsed to one-line responses.
- `jdwp_diagnose` filters disconnected JVMs from the local-JVM list
  when a connection is live.

### Output gaps closed

- `jdwp_resume_until_event` includes `at Class:line` in the "Event
  fired" line — saves one round-trip per hit.
- `jdwp_step_*` responses include the start location so the agent can
  see where the step originated.
- `jdwp_evaluate_expression` echoes the expression
  (`Result of <expr>: <value>`) so several batched evals can be
  attributed.
- `jdwp_to_string` flags the default `Object.toString` form
  (`ClassName@hexhash`) so the agent knows to use `jdwp_get_fields`
  instead of trusting the string.
- `jdwp_wait_for_attach` success points at `jdwp_resume_until_event` as
  the next call.
- `jdwp_mark_instance` lists the tools that accept `$label` identifiers
  in its success footer.

### New — marked instances

Agent-chosen labels that pin a JDI `ObjectReference` in the target heap and
expose it to expression evaluation as a synthetic `$label` binding. Mirrors
IntelliJ IDEA's "Mark Object" feature; the agent can now reference a specific
instance by name inside conditional breakpoints, logpoint expressions,
watchers, and exception logpoints — across method boundaries where the local
variable name differs or is absent.

- `jdwp_mark_instance(label, objectId, pin=true)` — register a label.
  `disableCollection()` is called by default so the mark survives across
  events. Rejects collisions, reserved binding names (`exception`,
  `oldValue`, `newValue`, `object`, `fieldName`, `mode`, `_this`), Java
  keywords, and non-identifier labels with descriptive errors.
- `jdwp_unmark_instance(label)` — releases the pin and frees the slot.
- `jdwp_rename_mark(oldLabel, newLabel)` — keeps the underlying object and
  pin.
- Marks are auto-cleared on VMDeath, disconnect, and `jdwp_reset` (matching
  the object cache lifecycle).
- `jdwp_get_locals` and `jdwp_get_breakpoint_context` now append a
  "Marked instances visible to expressions" footer listing every live mark
  so the agent does not need a separate call to remember them.

### New — unified overview and clear

- `jdwp_overview(types?, filter?)` — single read-only listing of
  breakpoints, exception breakpoints, field breakpoints, logpoints,
  watchers, AND marked instances. Filter by type (comma-separated subset of
  `breakpoint, exception_breakpoint, field_breakpoint, logpoint, watcher,
  mark` or `all`) and/or by case-insensitive substring across class /
  label / expression / type. Chain status (`chain=trigger=#N`) is rendered
  inline for any BP that is part of a chain.
- `jdwp_clear(types, filter?)` — bulk-clear by type and/or filter. `types`
  is **required** to prevent an unguarded blanket wipe (use `'all'` to
  clear every kind). To preview a clear safely, call `jdwp_overview` with
  the same `types`/`filter` first — the matching rows are exactly what
  `jdwp_clear` would remove.

### Breaking — narrow list/clear tools removed

Replaced by the unified `jdwp_overview` / `jdwp_clear` pair. Agents or
scripts that referenced these will need to switch to the unified surface
(no functional gap — chain status, condition, and expression info all
render under `jdwp_overview`):

- **`jdwp_list_breakpoints`** — use `jdwp_overview(types="breakpoint")`.
- **`jdwp_list_exception_breakpoints`** — use `jdwp_overview(types="exception_breakpoint")`.
- **`jdwp_list_field_breakpoints`** — use `jdwp_overview(types="field_breakpoint")`.
- **`jdwp_list_all_watchers`** — use `jdwp_overview(types="watcher")`.
- **`jdwp_clear_all_breakpoints`** — use `jdwp_clear(types="breakpoint,exception_breakpoint,field_breakpoint")`
  or `jdwp_clear(types="all")` to also clear watchers/marks in one call.
- **`jdwp_clear_all_watchers`** — use `jdwp_clear(types="watcher")`.

Per-ID delete tools are unchanged: `jdwp_clear_breakpoint(id)` and
`jdwp_detach_watcher(id)` remain the canonical "delete this one thing" path.
`jdwp_list_watchers_for_breakpoint(breakpointId)` is also retained — it is
the only per-BP watcher query (the substring filter on `jdwp_overview` is
not breakpoint-id-aware).

## [2.0.1] — 2026-05-17

### Fixed — stdio handshake with MCP clients on protocol `2025-11-25`

Claude Code 2.1.143 (and any client requesting an MCP protocol newer than
`2024-11-05`) could not use the plugin: `/mcp` reported
`Failed to reconnect to jdwp-inspector: -32000` and every tool call was
silently dropped after `initialize`.

Root cause was upstream: `mcp-core`'s `StdioServerTransportProvider.protocolVersions()`
hardcodes `List.of("2024-11-05")` in every published version through
`2.0.0-M2` (the latest shipped with Spring AI `2.0.0-M6`). The server
downgraded the session in its `initialize` response, then `McpAsyncServer`
stopped responding to all subsequent requests.

The plugin now ships a local `MultiVersionStdioServerTransportProvider` that
advertises all four protocol versions known to the bundled SDK
(`2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`) and registers it as
`stdioServerTransport`, displacing Spring AI's `@ConditionalOnMissingBean`
auto-config bean. Existing clients on `2024-11-05` continue to negotiate
successfully.

## [2.0.0] — 2026-05-17

This release renames and removes several breakpoint-clear tools to unify them
around a single ID-keyed surface. Agents or scripts that referenced the old
tool names will need to update — the new surface is smaller and more
consistent. The headline new feature is **field watchpoints**.

### Breaking changes

- **`jdwp_clear_breakpoint(className, lineNumber)` — removed.** Clear by ID
  using the unified `jdwp_clear_breakpoint(id)` instead.
- **`jdwp_clear_breakpoint_by_id(id)` — renamed** to `jdwp_clear_breakpoint(id)`.
- **`jdwp_clear_exception_breakpoint(id)` — removed.** Exception breakpoints
  now clear through the unified `jdwp_clear_breakpoint(id)`.
- **`jdwp_set_exception_breakpoint` — `logOnly` and `expression` parameters
  removed.** The tool is now strictly suspending. For non-stopping exception
  tracing use the new dedicated `jdwp_set_exception_logpoint`.

After upgrade, the single rule is: **every breakpoint kind (line, exception,
field) is cleared by ID via `jdwp_clear_breakpoint(id)`**.

### New — field watchpoints

JDI watchpoints that suspend or log on every read / write of a specific
field — including reflective writes via `Field.set` and `Unsafe`, which line
breakpoints on a setter would miss entirely.

- `jdwp_set_field_breakpoint(className, fieldName, mode, ...)` — `mode` is
  `access`, `modification`, or `both` (IntelliJ-style, both directions bind
  to one synthetic ID).
- `jdwp_set_field_logpoint(...)` — non-stopping variant that records to
  event history.
- `jdwp_list_field_breakpoints()` — active + pending field BPs. *(Removed
  in Unreleased; replaced by `jdwp_overview(types="field_breakpoint")`.)*

Conditions and logpoint expressions get five synthetic bindings:
`$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`. Pending field
BPs promote **synchronously** on `ClassPrepareEvent`, so writes inside
`<clinit>` are captured.

### New — other tools

- **`jdwp_set_exception_logpoint(exceptionClass, expression, condition?, ...)`** —
  non-stopping exception trace with `$exception` bound to the thrown object.
- **`jdwp_diagnose()`** — single-call snapshot of attach state, active
  breakpoints, recent events, plus local JVM discovery and a VM-capability
  probe (reports whether the target supports field access / modification
  watchpoints, with a perf warning for full-class-attribute access
  watchpoints).

### Features

- **Chained breakpoints** with one-shot / sticky modes — trigger BPs arm
  dependent BPs, with cascade-on-clear semantics. New event types in history:
  `CHAIN_ARMED`, `CHAIN_DISARMED`, `CHAIN_BROKEN`.
- **Diagnostic timeout response** — when `jdwp_resume_until_event` times out,
  the response now includes a structured diagnostic block (active BPs,
  pending BPs, recent events) instead of a bare timeout string.
- **Test flight #6 — "The Field That Lies"** — a new sandbox scenario that
  exercises field-modification watchpoints by mutating a private field via
  reflection. A line BP on the public setter never fires; the field
  watchpoint does.

### Event history

New event types: `FIELD_ACCESS`, `FIELD_MODIFICATION`, `FIELD_LOGPOINT`,
`FIELD_LOGPOINT_ERROR`, `FIELD_BREAKPOINT_SUPPRESSED`, `CHAIN_ARMED`,
`CHAIN_DISARMED`, `CHAIN_BROKEN`.

### Docs

- README: new "Field breakpoints (watchpoints)" section, refreshed tool
  reference (46 tools), flight #6 added.
- `skills/java-debug/SKILL.md`: new recipes — "Who is overwriting this
  field?" and `<clinit>` capture — plus field-BP perf gotcha.
- `docs/EXPRESSION_EVALUATION.md`: synthetic-bindings table covering
  `$exception`, `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`.

## [1.0.3] and earlier

Prior baseline. See git history for details.

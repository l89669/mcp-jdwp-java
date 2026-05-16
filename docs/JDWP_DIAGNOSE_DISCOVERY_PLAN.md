# Implementation Plan — `jdwp_diagnose` Discovery Extension

Status: **planned, not implemented**. Pick up by reading this file top-to-bottom; each phase has its own acceptance criteria so progress is resumable.

## 1. Goal

Make `jdwp_diagnose` useful **before** a JDWP connection is established. Today the tool only emits a useful report after attach. The new behaviour turns it into the "what's the state of the world?" tool a user runs first:

1. MCP server status (PID, uptime, configured target, last connection error).
2. JDWP connection status (connected vs. not, with the existing post-connect report appended when connected).
3. Local JVM inventory with JDWP port detection — answering "did my target JVM actually come up, and on which port?" without `ps`/`lsof`/`jps`.

## 2. Decisions captured (from review with Johnny)

| # | Question | Decision |
|---|---|---|
| 1 | Always-on discovery on every `jdwp_diagnose`? | **Yes**, provided the check is fast (<200 ms on a typical box). |
| 2 | Attach to each JVM by default to read its JDWP port? | **No.** Use cmdline parsing on Linux; non-Linux JVMs without a parseable cmdline report "JDWP status unknown — pass `inspectAll=true` to attach-inspect" (the `inspectAll` parameter is added in Phase 6). |
| 3 | Surface the local-JVM list on `jdwp_wait_for_attach` timeout? | **Yes** — same `JvmDiscoveryService`, called once on timeout. |
| 4 | Hide MCP server's own process from the list? | **No, flag it.** Add a `(THIS PROCESS)` marker so the user understands the noise. |

## 3. Non-goals

- Remote JVM discovery. Only same-host JVMs.
- Listing JVMs owned by other users that the current user cannot already see via `ps` or `jps`. No privilege escalation.
- Acting on discovered JVMs (auto-attach to the first one found, etc.). Pure read-only inventory.
- Replacing `jdwp_wait_for_attach`. Discovery enriches its timeout message; it does not change attach behaviour.

## 4. Architecture

```
                    ┌──────────────────────────────────────┐
                    │           JDWPTools                  │
                    │  ┌──────────────────────────────┐    │
                    │  │  jdwp_diagnose               │    │
                    │  │  ┌────────────────────────┐  │    │
                    │  │  │ McpServerStatusBlock   │  │    │
                    │  │  │ JdwpConnectionBlock    │  │    │
                    │  │  │ JvmDiscoveryBlock      │◄─┼──┐ │
                    │  │  └────────────────────────┘  │  │ │
                    │  └──────────────────────────────┘  │ │
                    │                                    │ │
                    │  jdwp_wait_for_attach ─── on timeout┼─┘
                    └────────────────────────────────────┘
                                                  │
                                                  ▼
                    ┌──────────────────────────────────────┐
                    │         JvmDiscoveryService          │
                    │  ┌────────────────────────────────┐  │
                    │  │ discover()                     │  │
                    │  │  1. attachApiStrategy()        │  │  jdk.attach
                    │  │  2. procFsStrategy() (Linux)   │  │  /proc/*/cmdline
                    │  │  3. confirmJdwpHandshake(port) │  │  Socket + magic 14B
                    │  │  4. dedupe + mask credentials  │  │
                    │  └────────────────────────────────┘  │
                    └──────────────────────────────────────┘
```

Two new data records (`JvmDescriptor`, `JdwpEndpoint`) carry normalised results between the service and the formatter; everything else is a method on the new `JvmDiscoveryService`.

## 5. Module / build changes

The compile-time and runtime module set has to grow by exactly one module: `jdk.attach`.

| File | Change |
|---|---|
| `jdwp-mcp-server/pom.xml` | Add `--add-modules jdk.attach` to `maven-compiler-plugin` `<arg>` block (right next to the existing `--add-modules jdk.jdi`). |
| `.mcp.json` | Append `"--add-modules", "jdk.attach"` to the `args` array (or extend the existing `--add-modules` value to `jdk.jdi,jdk.attach`). |
| `hooks/hooks.json` | Same change to the auto-rebuild script's launch command. |
| `README.md` "Alternative: manual MCP registration" block | Update the `claude mcp add` command and the `.mcp.json` sample. |

`jdk.attach` ships with every JDK ≥ 9; no new dependency JARs.

## 6. New types

```java
// New package: one.edee.mcp.jdwp.discovery

public record JvmDescriptor(
    long pid,
    @Nullable String mainClass,          // from attach getDisplayName() or parsed cmdline
    @Nullable String javaHome,           // from /proc/.../exe symlink or attach properties
    @Nullable String maskedCmdline,      // full cmdline with credentials masked; null if unavailable
    @Nullable JdwpEndpoint jdwp,         // null = no JDWP detected
    boolean isThisProcess,               // true if pid == ProcessHandle.current().pid()
    Source source                        // which strategy found this descriptor
) {
    public enum Source { ATTACH_API, PROC_FS, BOTH }
}

public record JdwpEndpoint(
    String host,                         // typically "*" or "127.0.0.1"
    int port,
    String transport,                    // "dt_socket" / "dt_shmem"
    boolean serverMode,                  // server=y means it's listening
    boolean suspendOnStart,              // suspend=y means waiting for first attach
    State state                          // see below
) {
    public enum State {
        LISTENING,        // server=y AND TCP port accepts connections
        ATTACHABLE,       // LISTENING AND JDWP handshake succeeded
        SUSPENDED,        // LISTENING AND suspend=y (initial attach required)
        CONNECTED_TO_US,  // this is the JVM we're attached to right now
        UNREACHABLE,      // cmdline says JDWP, but port not accepting
        UNKNOWN           // detected via attach API only; port not probed
    }
}
```

## 7. Implementation phases

Each phase ends with a green `mvn -pl jdwp-mcp-server test` and matches a single commit-worthy unit. Phases are intentionally independent so each one can land as a separate PR/commit if desired.

### Phase 1 — Module wiring

**Goal**: build still passes after `jdk.attach` is added to the compile + runtime module set; no behaviour change.

Files touched:
- `jdwp-mcp-server/pom.xml`
- `.mcp.json`
- `hooks/hooks.json`

Acceptance:
- `mvn -pl jdwp-mcp-server clean compile` succeeds.
- A throwaway test that imports `com.sun.tools.attach.VirtualMachine` compiles without `--add-exports` complaints.
- The bundled JAR launches without `module not found` when started via the updated `.mcp.json` args.

### Phase 2 — `JvmDescriptor` / `JdwpEndpoint` records + skeleton service

**Goal**: data shape locked in. Service exists, returns empty list.

Files touched:
- `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/discovery/JvmDescriptor.java`
- `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/discovery/JdwpEndpoint.java`
- `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/discovery/JvmDiscoveryService.java`

Acceptance:
- Both records are `@NullMarked`-compliant; nullable slots carry jspecify `@Nullable`.
- `JvmDiscoveryService.discover()` returns `List<JvmDescriptor>` (initially empty); marked `@Service` so Spring picks it up.
- Unit test: empty discovery returns empty list, no exceptions.

### Phase 3 — Attach-API strategy

**Goal**: list local JVMs visible via `com.sun.tools.attach.VirtualMachine.list()`. PID + main class + `isThisProcess` flag only — no JDWP port info yet.

Files touched:
- `JvmDiscoveryService` — add `viaAttachApi()` returning `List<JvmDescriptor>`.
- Test: `JvmDiscoveryServiceAttachApiTest` — uses a `MockedStatic<VirtualMachine>` (Mockito Inline) to fake `VirtualMachine.list()`.

Acceptance:
- Calling `discover()` returns one descriptor per JVM in `VirtualMachine.list()`, populated with `pid`, `mainClass` (from `getDisplayName()`), `isThisProcess` (compare to `ProcessHandle.current().pid()`), `source=ATTACH_API`.
- `mainClass` falls back to `null` if `getDisplayName()` returns `""` (some JVMs do).
- Failures from `VirtualMachine.list()` (e.g. `AttachNotSupportedException` on some sandbox configurations) are caught and logged at DEBUG; method returns an empty list rather than throwing.
- Time budget: must complete within 100 ms on a typical Linux dev machine. If it takes longer, log a WARN and continue.

### Phase 4 — Linux `/proc` strategy

**Goal**: enrich the attach-API list (or replace, on Linux). Parse `/proc/*/cmdline` for every numeric PID, extract `-agentlib:jdwp=` if present, populate `JdwpEndpoint`.

Files touched:
- `JvmDiscoveryService` — add `viaProcFs()`.
- `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/discovery/JdwpAgentArgParser.java` — pure function: `parse(String agentLibSubstring) → JdwpEndpoint`. Pulled out for unit-testing.
- Test: `JdwpAgentArgParserTest` — covers `transport=dt_socket,server=y,suspend=n,address=*:5005`, `address=localhost:5005`, `address=5005` (bare port), missing fields, malformed input.
- Test: `JvmDiscoveryServiceProcFsTest` — points the service at a temp directory containing fake `<pid>/cmdline` files; verify parsing.

Acceptance:
- Linux only: gated on `System.getProperty("os.name").toLowerCase().contains("linux")`.
- Reads `/proc/*/cmdline` lazily (one PID at a time); ignores `IOException` per-file (read permission denied is expected for some PIDs).
- For each readable cmdline that contains `-agentlib:jdwp=` OR `-Xrunjdwp:`, produces a `JdwpEndpoint` with `state=UNKNOWN` (port not probed yet).
- Cmdline strings are masked: any substring matching `(?i)(password|secret|token|apikey|api_key)=[^\s\0]+` → `<KEY>=***`.
- Merged with attach-API output by PID — `Source.BOTH` when both strategies see the same PID.
- Time budget: 200 ms total for `/proc` walk on a box with 500 processes.

### Phase 5 — JDWP handshake confirmation

**Goal**: confirm a candidate port actually speaks JDWP, not just "something is listening on 5005".

Files touched:
- `JvmDiscoveryService` — add `confirmJdwpHandshake(host, port, timeoutMs)`.
- Test: integration test that opens a real `ServerSocket`, echoes the 14-byte magic, asserts confirmation succeeds; second test against a port that ignores the bytes, asserts it returns false within timeout.

Acceptance:
- Sends exactly `"JDWP-Handshake"` (14 bytes, ASCII), waits up to 100 ms for the same 14 bytes back.
- On match: endpoint state moves `UNKNOWN` → `LISTENING`. If the parsed cmdline says `suspend=y`, state is `SUSPENDED`. If `pid == this.connectedTargetPid` (from `JDIConnectionService`), state is `CONNECTED_TO_US`.
- On no-match / timeout / `ConnectException`: endpoint state moves to `UNREACHABLE`.
- Only probes localhost (or whatever the cmdline `address=` host says, but never an off-host IP).
- Per-port budget: 100 ms; total budget over all candidates: 500 ms with a small fixed-size thread pool (4 threads).

### Phase 6 — Wire into `jdwp_diagnose`

**Goal**: `jdwp_diagnose` now always emits three blocks (MCP-server / JDWP-connection / Local-JVMs). Existing diagnostic report continues to appear in the JDWP-connection block when connected.

Files touched:
- `JDWPTools.jdwp_diagnose` — restructure output. Optionally add a new boolean param `inspectAll` (`@McpToolParam(required=false, description="If true, attach to each candidate JVM to read its JDWP port directly. Default false — costs one short attach per JVM and is visible to targets.")`).
- New helper methods on `JDWPTools` (or extracted to `DiagnoseReportRenderer` if `JDWPTools` is getting too crowded — TBD during impl).
- Tests in `JDWPToolsDiagnoseTest` (likely a new file): covers the three blocks, both connected and disconnected states, with `JvmDiscoveryService` mocked.

Acceptance:
- `jdwp_diagnose()` returns a three-block string regardless of connection state.
- The connection block reports last-attempt timestamp + error from `JDIConnectionService` when not connected. Requires a small addition to `JDIConnectionService`: record `lastConnectAttempt: Instant?` and `lastConnectError: String?` on failed connects.
- The JVM block correctly flags the MCP-server's own row as `THIS PROCESS`.
- When `inspectAll=true`, the service additionally calls `VirtualMachine.attach(pid).getAgentProperties()` for each JVM whose `jdwp` is still null, looking up `sun.jdwp.listenerAddress`. Per-JVM time budget: 200 ms; on timeout, leave the endpoint null. Defaults to false.
- Total `jdwp_diagnose()` time with `inspectAll=false`: <500 ms.

### Phase 7 — Wire into `jdwp_wait_for_attach` timeout

**Goal**: when the poll loop times out without finding a listening JVM at the configured port, surface the local-JVM list so the user can see what they might have meant.

Files touched:
- `JDWPTools.jdwp_wait_for_attach` — on timeout, append the rendered "Local JVMs" block (same helper used by `jdwp_diagnose`).

Acceptance:
- Timeout message format: existing message + blank line + "Local JVMs visible to user 'X':" + rendered table.
- Success path unchanged (no discovery call when attach succeeds).
- Discovery call shares the same time budget as `jdwp_diagnose`.

### Phase 8 — Output rendering

**Goal**: pretty, scannable, ASCII-safe. Use the existing rendering style in `JDWPTools` (box-drawing characters already appear elsewhere — match the existing register).

Sample output (locked target):

```
═══════════════════════════════════════════════════════════════
 MCP JDWP Inspector — Diagnostic Report
═══════════════════════════════════════════════════════════════

▸ MCP server
  Status:        Running (PID 12345, uptime 2h 14m)
  Java:          OpenJDK 21.0.1 (Eclipse Temurin)
  Tools:         44 registered
  Configured:    target host=localhost port=5005
  Working dir:   /www/oss/mcp-jdwp-java

▸ JDWP connection
  ⚠ Not connected
  Last attempt:  2026-05-16 14:33:12 → "Connection refused"
  Suggestion:    Launch target with -agentlib:jdwp=...,address=*:5005
                 then jdwp_connect (or jdwp_wait_for_attach to poll).

▸ Local JVMs visible to user 'jno' (3 found via attach + /proc)
  PID    Main class / JAR                          JDWP            State
  ─────  ────────────────────────────────────────  ──────────────  ──────────
  23145  com.example.app.Main                      :5005           LISTENING
  23801  org.apache.maven.surefire.booter.…        :5006 (s)       SUSPENDED
  24102  JdwpMcpServerApplication (THIS PROCESS)   —               —
  24505  com.example.tools.Cli                     —               no JDWP

  Legend: (s)=suspend=y, ARMED state requires JDWP handshake confirmation.
  💡 To attach: jdwp_connect (port 5005) or jdwp_wait_for_attach(port=5006)
```

Connected-state sample (same three blocks, JDWP-connection block carries the existing report):

```
▸ JDWP connection
  ✓ Connected to localhost:5005 (PID 23145)
  Suspending: yes, on thread http-nio-8080-exec-3 at OrderService:42
  Recent events (5):
    14:42:15  BREAKPOINT     BP #3 at OrderService:42, thread exec-3
    14:42:16  LOGPOINT       BP #7 → "order=1041 total=2495.0"
    …
  [existing buildDiagnosticReport output continues here]
```

Acceptance:
- ASCII art renders fine on `xterm-256color` and on the Claude Code transcript.
- No box-drawing characters outside the section dividers (some terminals render them poorly when copy/pasted).
- Numbers right-aligned in the PID column; text left-aligned elsewhere.
- Truncation: main-class names > 38 chars are truncated with `…`.

### Phase 9 — Documentation

Files touched:
- `README.md` — replace the existing "Diagnostics (1)" tool row with an expanded `jdwp_diagnose` description; add a sample output collapse-able `<details>` block under the "Features beyond standard JDWP" section. Mention the new `inspectAll` parameter in the tool table.
- `skills/java-debug/SKILL.md` (and/or `references/troubleshooting.md`) — add "if nothing's working, run `jdwp_diagnose` first" as the standard opening step.
- `docs/JDWP_DIAGNOSE_DISCOVERY_PLAN.md` (this file) — mark each phase as **DONE** as it lands.

Acceptance:
- README `jdwp_diagnose` description correctly mentions discovery + `inspectAll`.
- A user reading `README.md` can guess what `jdwp_diagnose` does without opening source.

### Phase 10 — Test coverage targets

By the end of implementation:
- `JvmDiscoveryServiceTest` — list discovery, dedup, masking, time budget.
- `JdwpAgentArgParserTest` — every realistic `-agentlib:jdwp=` shape (covered by Phase 4 already).
- `JvmDiscoveryServiceProcFsTest` — Linux strategy with `/proc` fixture (Phase 4).
- `JdwpHandshakeProbeTest` — real socket integration test (Phase 5).
- `JDWPToolsDiagnoseTest` — three-block output, connected/disconnected, this-process flag, inspectAll on/off (Phase 6).
- `JDWPToolsWaitForAttachTest` — timeout includes discovery, success does not (Phase 7).

Mockito static-mocking of `com.sun.tools.attach.VirtualMachine.list()` requires `mockito-inline`. If it isn't already on the test classpath, add it as part of Phase 3.

## 8. Security review checklist (before merge)

- [ ] No remote network calls (only localhost socket probes).
- [ ] No process spawning (`Runtime.exec` / `ProcessBuilder`).
- [ ] No raising of OS privileges; all reads are within current-user permissions.
- [ ] Cmdline credential masking covers `password`, `secret`, `token`, `apikey`, `api_key`.
- [ ] `inspectAll=true` requires the user to opt in; default is false.
- [ ] Discovery never auto-attaches to a JVM the user did not explicitly target.
- [ ] No reading of `/proc/<pid>/environ` (which can contain secrets) — cmdline only.

## 9. Risks / open questions

- **macOS attach-only**: same-user JVMs are discoverable but the JDWP port cannot be read without `inspectAll=true`. Acceptable per Decision #2; document it in the troubleshooting section.
- **`/proc/<pid>/cmdline` and Linux capabilities**: some containers strip read permissions on `/proc/*/cmdline` for non-self PIDs (`hidepid=2` mount option). Strategy must degrade silently when this is in effect.
- **Mockito static-mock of JDK classes** sometimes interacts poorly with sealed/hidden classes in newer JDKs. If `MockedStatic<VirtualMachine>` doesn't work cleanly, refactor `JvmDiscoveryService` to take a `Supplier<List<VirtualMachineDescriptor>>` dependency so the test can swap in a fake without static mocking.
- **Display name overlap**: surefire-test JVMs and our own MCP-server JVM both show up in the list; ensure the THIS-PROCESS flag is the only distinguisher the user needs.
- **Race between probe and attach**: it's possible the user calls `jdwp_diagnose` while their target JVM is mid-startup. The handshake probe will say UNREACHABLE; that's correct — the next call will say LISTENING. Document the transient nature.

## 10. Estimated effort

Eyeballed sizes (one focused dev session each):

| Phase | Effort |
|---|---|
| 1 — Module wiring | 30 min |
| 2 — Records + skeleton service | 30 min |
| 3 — Attach-API strategy | 1 h |
| 4 — `/proc` strategy + parser | 2 h |
| 5 — Handshake probe | 1 h |
| 6 — Wire into `jdwp_diagnose` | 1.5 h |
| 7 — Wire into `jdwp_wait_for_attach` | 30 min |
| 8 — Output rendering polish | 1 h |
| 9 — Documentation | 1 h |
| 10 — Final test pass + review | 1 h |
| **Total** | **~10 h** |

Implement in any order from Phase 2 onward as long as Phase 1 lands first. Phases 6 and 7 depend on Phases 3–5 being done.

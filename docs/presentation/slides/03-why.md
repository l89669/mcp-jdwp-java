## Why bother?

Two goals, in priority order.

---

## Goal #1 — failing tests

When a test fails, **don't** read the stack trace and guess.

- Re-run the test with `-Dmaven.surefire.debug` (Maven sets `suspend=y` by default)
- Agent attaches at port 5005 **before** any user code runs
- Sets a breakpoint at the failing assertion
- Inspects locals, evaluates expressions, walks object graphs

The agent now sees what the **debugger** sees — not what the log printed.

Note:
This is the killer use case. Today most agents pattern-match on the stack trace and hallucinate the cause. With JDWP they observe runtime state. The Maven-suspend integration matters because by the time a slow agent process attaches, ordinary test runs are already over — `suspend=y` holds the JVM until we say go.

---

## Goal #2 — running applications

Production-like Spring Boot app misbehaving locally?

- Start it with `-agentlib:jdwp=...,suspend=n,address=*:5005`
- Agent attaches **while it's running**
- Logpoints + field watchpoints trace state without stopping traffic
- Conditional breakpoints fire only on the bad request

No restart, no extra logging, no print-statement-and-redeploy loop.

Note:
Secondary goal but a huge time-saver for "why is this only broken in staging" investigations. Mention that field watchpoints are new — they let you trace *who* mutates a field without instrumenting code.

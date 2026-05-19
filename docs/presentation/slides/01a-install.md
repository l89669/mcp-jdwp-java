## Install — and why it's safe

```bash
/plugin marketplace add https://github.com/FgForrest/mcp-jdwp-java.git
/plugin install jdwp-debugging@mcp-jdwp-java
```

- Claude Code **clones the source repo** into `~/.claude/plugins/…`
- First session runs **Maven on your machine** → builds the JAR locally
- No prebuilt binaries, no opaque download
- Sources are public — read, diff, or **ask Claude Code itself to audit them**

<small class="muted">Prerequisites: JDK 17+ on PATH (must be a JDK, not JRE — JDI lives in <code>jdk.jdi</code>) and Maven 3.8+.</small>

Note:
Claude Code's plugin model is essentially "git clone + run". Marketplaces and plugins are Git repos; install means checkout, and the artifact you execute is compiled on your hardware from sources you can inspect.

Supply-chain story: the only thing you need to trust is the Git history. You can answer that yourself, in Claude Code, before you trust the plugin — "audit these sources for anything that touches the network or filesystem beyond the JVM target". The agent has all the tools to do that read.

Practical detail: first session triggers the Maven build (~30 s, one-off). After that, the built JAR is cached. A `git pull` + restart picks up updates.

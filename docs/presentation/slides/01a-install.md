<!-- .slide: class="install-slide" data-background-image="images/bg-content.png" data-background-size="cover" -->

## Install — and why it's safe

```text
/plugin marketplace add https://github.com/FgForrest/mcp-jdwp-java.git
/plugin install jdwp-debugging@mcp-jdwp-java
```

- Claude Code **clones the source repo** under `~/.claude/plugins/`
- First session runs **Maven on your machine** → builds the JAR locally
- No prebuilt binaries, no opaque download
- Sources are public — read, diff, or **ask Claude Code itself to audit them**

<small class="muted">Prerequisites: JDK 17+ on PATH (must be a JDK, not JRE — JDI lives in <code>jdk.jdi</code>) and Maven 3.8+.</small>

Note:
- Plugin model = "git clone + run" — marketplaces are Git repos
- Artifact you execute is compiled on your hardware from sources you can read
- Trust surface collapses to "is the Git history clean?" — answerable in Claude Code itself
- Try: "audit these sources for anything that touches network or filesystem beyond the JVM target"
- First session triggers Maven (~30 s, one-off); JAR cached after; `git pull` + restart updates

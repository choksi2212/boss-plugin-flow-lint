# AGENTS.md

## Project Overview

**Flow Lint** (`ai.rever.boss.plugin.dynamic.flowlint`) is a dynamic plugin
for the BOSS desktop application.

A pre-run validator for flow-tab graphs - 12 rules flag unreachable nodes,
missing TRIGGERs, missing browser ancestors, empty selectors, HTTP URL
mistakes, undefined variables, unresolved secrets, and stale node types
before a flow runs.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.flowlint`
- **Main Class**: `ai.rever.boss.plugin.dynamic.flowlint.FlowLintDynamicPlugin`
- **API Version**: 1.0.93

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build (compile + tests + plugin jar)
./gradlew test               # Run unit tests only
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure

```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.flowlint)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
src/test/kotlin/   → Unit tests for the linter rules
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns

- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`.
- UI: `PanelComponentWithUI` with `@Composable Content()`.
- State: ViewModel pattern with `StateFlow`.
- The plugin has no compile-time references to `flow-tab`; it reaches the
  active graph through `PluginContext.mcpToolRegistry.invoke("flow_get", ...)`.
  - The MCP tool provider falls back to a clear error when `flow_get` is not
    registered.
- MCP tools that drive the panel's local state read `lastComponent()` - same
  shape flow-bridge and Plugin X-Ray use internally.
- `FlowLinter` is stateless and cheap to allocate; the linter is the one
  source of truth shared between the panel ViewModel and the MCP tool provider.

### Adding a new lint rule

1. Add a new `object XxxRule : LintRule` to `Rules.kt`.
2. Append it to `FlowLinter.defaultRules()` in `FlowLinter.kt`.
3. Add at least one positive and one negative case to `FlowLinterTest.kt`.
4. Update `README.md`'s rules table.

### Dependencies

- **boss-plugin-api**: compileOnly (provided by host app at runtime).
- **Compose Desktop**: UI framework.
- **Decompose**: Navigation and component lifecycle.
- **Coroutines**: Async operations.
- **kotlinx.serialization**: JSON for graph parsing and MCP reply payloads.

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json`
at build time. Never manually edit the version in `plugin.json` - only change
it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific).
- All Kotlin files must end with a newline.
- Handle null providers gracefully - show fallback UI, never crash.
- Use spaced hyphens (` - `) in prose; never em-dashes (U+2014).

## CI/CD

Pushes to `main` trigger the release workflow which:

1. Builds the plugin JAR.
2. Creates a GitHub release.
3. Publishes to the BOSS Plugin Store.

The workflow is defined in `.github/workflows/build.yml` and delegates to the
shared workflow in `risa-labs-inc/BossConsole-Releases`.

Pull requests run the test workflow at `.github/workflows/test.yml`, which
downloads `boss-plugin-api`, compiles, runs the unit tests, and assembles
the JAR.

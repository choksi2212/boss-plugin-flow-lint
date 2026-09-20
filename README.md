# BOSS Flow Lint

The first standalone validator for BOSS flow-tab graphs. Today `flow-tab` lets
an operator build a graph (TRIGGER -> ... -> actions) and run it; the only
feedback is what fails mid-flow, after the browser is already half-driven
through. Flow Lint runs the same graph through 12 static rules before it is
submitted and reports the issues in a panel and over MCP.

It has no compile-time references to `flow-tab`. The "Lint active graph"
button reaches the open graph through the host's MCP registry and the
`flow_get` tool, so the plugin compiles and runs without `flow-tab` on the
classpath. When `flow-tab` is not loaded, the panel renders a status line and
`flow_lint_check_active` returns a clear error.

## What it does

- A sidebar panel with a paste-a-JSON box, a Lint button, and a Lint-active-graph
  button. Each issue renders with its rule id, severity, message, and (when
  the linter found one) the chain from TRIGGER to the offending node.
- An MCP tool provider exposing `flow_lint_check`, `flow_lint_check_active`,
  and `flow_lint_rules`. All three are read-only; the active-graph one
  returns a clear error when `flow-tab` is missing.
- One source of truth for both surfaces: the panel's ViewModel and the MCP
  tools share the same `FlowLinter`, so an agent and an operator see the same
  findings.

## The 12 rules

| Rule id | Severity | What it catches |
|---|---|---|
| `no-trigger` | error | Graph has zero TRIGGER nodes (unrunnable) or more than one |
| `multiple-open-browser` | error | More than one OPEN_BROWSER node (the executor fences the browser) |
| `cycle` | error | Any cycle in the graph (except TRIGGER->TRIGGER self-edges) |
| `disconnected` | error | Any non-TRIGGER node with no incoming edge |
| `missing-browser-ancestor` | error | CLICK/TYPE/EXTRACT/INJECT/NAVIGATE without a preceding OPEN_BROWSER in the same chain |
| `http-url` | error | HTTP node with empty URL or non-http(s) scheme |
| `empty-selector` | error | CLICK/TYPE node without a selector |
| `empty-condition-or-template` | error | IF node with empty condition or CODE node with empty template |
| `merge-single-input` | warning | MERGE node with only one input edge |
| `undefined-variable` | error | References to `{{ varName }}` not defined anywhere upstream |
| `unresolved-secret` | warning | References to `{{ secret:foo }}` without a registered secret provider |
| `unknown-node-type` | error | Nodes referencing a NodeType that no longer exists |

For each finding the linter emits `ruleId`, `severity`, `message`, `nodeId`
(null for graph-level rules), and a `path` (the chain from a TRIGGER to the
offending node when the rule had to walk a chain to detect it).

## MCP tools

| Tool | Purpose |
|---|---|
| `flow_lint_check` | Lint a flow-tab graph JSON the caller supplies. Returns a JSON object with `issues`, `ruleCount`, `nodeCount`, and a `summary`. |
| `flow_lint_check_active` | Read the active flow-tab graph via the `flow_get` MCP tool and lint it. Returns a clear error when `flow-tab` is not loaded. |
| `flow_lint_rules` | List every rule the linter ships with (id and description). |

Every handler returns an `McpToolResult` with `isError = true` and a sentence
the agent can act on when something is missing or malformed.

## Install

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-flow-lint-0.1.0.jar ~/.boss/plugins/
```

Then enable Flow Lint from the Toolbox and open its panel from the left
sidebar (under Plugin X-Ray, in the `left_bottom` slot at priority 62).
Install `flow-tab` to unlock the "Lint active graph" button.

## Requirements

- BOSS >= 9.5.0, `boss-plugin-api` >= 1.0.93.
- `flow-tab` is recommended but not required; without it the active-graph
  button degrades to a status line.

## License

Proprietary - Risa Labs Inc.

package ai.rever.boss.plugin.dynamic.flowlint

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tool provider for Flow Lint.
 *
 * Three tools, all read-only:
 *
 *  - `flow_lint_check` - lint a graph JSON the caller supplies.
 *  - `flow_lint_check_active` - read the active flow-tab graph via the
 *    `flow_get` MCP tool and lint it; gracefully reports "flow-tab not loaded"
 *    rather than crashing when the host does not have it.
 *  - `flow_lint_rules` - list the rule set the linter ships with.
 *
 * Results are JSON objects so an agent can iterate the issues field. The
 * linter runs synchronously and is cheap, so we never need a coroutine, but
 * the tool handler signature is `suspend` to match the host contract.
 */
internal class FlowLintMcpToolProvider(
    override val providerId: String,
    private val linter: FlowLinter,
    private val registry: McpToolRegistry?,
    private val component: () -> FlowLintComponent?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "flow_lint_check",
            description =
                "Lint a flow-tab graph JSON against the 12 Flow Lint rules. " +
                    "Returns a JSON object with `issues`, `ruleCount`, `nodeCount`, and a `summary`. " +
                    "Each issue has `ruleId`, `severity` (error/warning/info), `message`, `nodeId`, and `path`.",
            inputSchema = """{"type":"object","properties":{"graph":{"type":"string","description":"Flow-tab graph as a JSON string. Must be a JSON object containing `nodes` and `edges` arrays."}},"required":["graph"]}""",
            handler = McpToolHandler { handleCheck(it) },
        ),
        McpToolDefinition(
            name = "flow_lint_check_active",
            description =
                "Read the currently open flow-tab graph (via the flow_get MCP tool) and lint it. " +
                    "Returns a clear error when flow-tab is not loaded.",
            handler = McpToolHandler { handleCheckActive() },
        ),
        McpToolDefinition(
            name = "flow_lint_rules",
            description =
                "List every rule the Flow Linter knows about, with id, description, and severity " +
                    "convention (error/warning/info).",
            handler = McpToolHandler { handleRules() },
        ),
    )

    // ---- handlers -------------------------------------------------------

    private suspend fun handleCheck(args: McpToolArgs): McpToolResult {
        val raw = args.string("graph")
            ?: return McpToolResult("Missing required argument: graph", isError = true)
        if (raw.isBlank()) {
            return McpToolResult("Argument 'graph' must not be blank", isError = true)
        }
        val graph = try {
            parseGraph(raw)
        } catch (e: Exception) {
            return McpToolResult(
                "Could not parse graph JSON: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }
        val result = linter.lint(graph)
        pushToPanel(result, graph)
        return McpToolResult(json.encodeToString(LintResult.serializer(), result))
    }

    private suspend fun handleCheckActive(): McpToolResult {
        val reg = registry
            ?: return McpToolResult("flow_lint_check_active: MCP tool registry unavailable in this host", isError = true)
        if (!hasTool(reg, "flow_get")) {
            return McpToolResult(
                "flow-tab is not loaded - install it from the Toolbox before running flow_lint_check_active.",
                isError = true,
            )
        }
        val invoke = try {
            reg.invoke("flow_get", "{}")
        } catch (e: Exception) {
            return McpToolResult(
                "flow_get invocation failed: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }
        if (invoke.isError) {
            return McpToolResult("flow_get refused: ${invoke.text}", isError = true)
        }
        val graph = try {
            parseGraph(invoke.text)
        } catch (e: Exception) {
            return McpToolResult(
                "Could not parse active graph JSON: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }
        val result = linter.lint(graph)
        pushToPanel(result, graph)
        return McpToolResult(json.encodeToString(LintResult.serializer(), result))
    }

    private suspend fun handleRules(): McpToolResult {
        val body = buildJsonArray {
            for (rule in linter.rules) {
                add(buildJsonObject {
                    put("id", rule.id)
                    put("description", rule.description)
                })
            }
        }
        return McpToolResult(json.encodeToString(JsonArray.serializer(), body))
    }

    // ---- helpers --------------------------------------------------------

    private fun pushToPanel(result: LintResult, graph: Graph) {
        // The ViewModel is the source of truth the panel reads. When the
        // panel is not open, `component()` is null and we silently skip -
        // the agent's MCP reply is the same either way.
        val comp = component() ?: return
        comp.exposedViewModel.acceptExternalResult(graph, result)
    }

    private fun hasTool(reg: McpToolRegistry, name: String): Boolean =
        reg.allTools.value.any { it.definition.name == name }

    /**
     * Parse a graph JSON string into a [Graph].
     *
     * Accepts either the canonical shape (`{nodes:[...], edges:[...]}`) or the
     * flow-tab wire shape (the same thing, different field names). Anything
     * that fails strict decoding is re-tried with a permissive parser so a
     * graph with one extra field does not break lint.
     */
    private fun parseGraph(raw: String): Graph {
        // Wrap as JsonElement first so a malformed payload returns a clean
        // exception with the offset rather than a deserialization error
        // aimed at the wrong field.
        val element = json.parseToJsonElement(raw)
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("graph must be a JSON object")

        return json.decodeFromString(Graph.serializer(), obj.toString())
    }

    private companion object {
        val json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}

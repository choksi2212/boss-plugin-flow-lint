package ai.rever.boss.plugin.dynamic.flowlint

import ai.rever.boss.plugin.api.McpToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * State for the Flow Lint panel.
 *
 * Holds:
 *  - `lastResult` - the most recent [LintResult] the panel renders.
 *  - `activeGraphLoaded` - whether flow-tab is reachable on this host; the
 *    panel reads this to decide whether to show the "Lint active graph"
 *    button or a status line.
 *  - `info` / `error` - transient toast text.
 *
 * The MCP tools push results into the ViewModel through
 * [acceptExternalResult]; the panel subscribes via `collectAsState()`.
 */
class FlowLintViewModel(
    private val linter: FlowLinter,
    private val registry: McpToolRegistry?,
    private val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scope = pluginScope

    private val _lastResult = MutableStateFlow<LintResult?>(null)
    val lastResult: StateFlow<LintResult?> = _lastResult.asStateFlow()

    private val _activeGraphLoaded = MutableStateFlow(false)
    val activeGraphLoaded: StateFlow<Boolean> = _activeGraphLoaded.asStateFlow()

    private val _activeGraphName = MutableStateFlow<String?>(null)
    val activeGraphName: StateFlow<String?> = _activeGraphName.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refreshActiveGraphAvailability()
    }

    /**
     * Lint a graph the user pasted into the panel.
     *
     * Called from the UI thread; launches the parse and the linter on
     * [scope] so a giant paste doesn't block the panel. Errors land in
     * [error] and a successful run replaces [lastResult].
     */
    fun lintPasted(rawJson: String) {
        if (rawJson.isBlank()) {
            _error.value = "Paste a flow graph JSON first"
            return
        }
        scope.launch {
            try {
                val graph = parseGraph(rawJson)
                val result = linter.lint(graph)
                _lastResult.value = result
                _activeGraphName.value = null
                _info.value = "Linted ${graph.nodes.size} node(s) - ${result.summary()}"
            } catch (e: Exception) {
                _error.value = "Could not parse graph: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * Lint the currently open flow-tab graph.
     *
     * Re-checks availability first so a freshly-loaded flow-tab is picked
     * up without restarting the panel. A "not loaded" error stays as a
     * status line in [error] and the button stays disabled on the UI side.
     */
    fun lintActiveGraph() {
        val reg = registry
        if (reg == null) {
            _error.value = "MCP tool registry unavailable in this host"
            return
        }
        if (!hasTool(reg, "flow_get")) {
            _error.value = "flow-tab is not loaded - install it from the Toolbox"
            return
        }
        scope.launch {
            try {
                val invoke = reg.invoke("flow_get", "{}")
                if (invoke.isError) {
                    _error.value = "flow_get refused: ${invoke.text}"
                    return@launch
                }
                val graph = parseGraph(invoke.text)
                val result = linter.lint(graph)
                _lastResult.value = result
                _activeGraphName.value = graph.name.ifBlank { graph.id.ifBlank { null } }
                _info.value = "Linted active graph - ${result.summary()}"
            } catch (e: Exception) {
                _error.value = "Active graph lint failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * Push an external result (from an MCP tool call) into the panel.
     *
     * The ViewModel is the single source of truth the panel renders, so any
     * agent-driven lint must route through here. The graph is kept so the
     * panel can render a one-line summary header.
     */
    fun acceptExternalResult(graph: Graph, result: LintResult) {
        _lastResult.value = result
        _activeGraphName.value = graph.name.ifBlank { graph.id.ifBlank { null } }
        _info.value = "Linted ${graph.nodes.size} node(s) - ${result.summary()}"
    }

    /** Clear the info / error toast. Called from the UI after the dismiss timer. */
    fun clearMessages() {
        _info.value = null
        _error.value = null
    }

    /** Drop the rendered lint result. */
    fun clearResult() {
        _lastResult.value = null
        _activeGraphName.value = null
    }

    /**
     * Re-check whether flow-tab is loaded and update [activeGraphLoaded].
     *
     * The MCP registry emits on every register/unregister; this is a one-shot
     * check the panel calls on open. The "Lint active graph" button is shown
     * only when [activeGraphLoaded] is true.
     */
    fun refreshActiveGraphAvailability() {
        val reg = registry
        _activeGraphLoaded.value = reg != null && hasTool(reg, "flow_get")
    }

    private fun hasTool(reg: McpToolRegistry, name: String): Boolean =
        reg.allTools.value.any { it.definition.name == name }

    /**
     * Parse a graph JSON string into a [Graph]. Same shape the MCP tools
     * use, kept private so the panel and the MCP tools share one parser.
     */
    private fun parseGraph(raw: String): Graph {
        return try {
            lenientJson.decodeFromString(Graph.serializer(), raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid graph JSON: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private companion object {
        val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}

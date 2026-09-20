package ai.rever.boss.plugin.dynamic.flowlint

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The NodeType enum mirrors the kind ids the flow-tab plugin uses internally.
 *
 * We deliberately do NOT import flow-tab's enum. This plugin compiles without
 * flow-tab on the classpath, so the enum is reproduced here from the kind
 * ids the flow-tab MCP tools accept (`browser.navigate`, `browser.click`,
 * `if`, `merge`, ...) and the graph JSON the plugin persists. If flow-tab ever
 * adds a new kind, add it here and to [UNKNOWN] handling so the linter still
 * reads existing graphs.
 */
@Serializable
enum class NodeType {
    @SerialName("TRIGGER")
    TRIGGER,

    @SerialName("OPEN_BROWSER")
    OPEN_BROWSER,

    @SerialName("NAVIGATE")
    NAVIGATE,

    @SerialName("CLICK")
    CLICK,

    @SerialName("TYPE")
    TYPE,

    @SerialName("EXTRACT")
    EXTRACT,

    @SerialName("INJECT")
    INJECT,

    @SerialName("HTTP")
    HTTP,

    @SerialName("SET")
    SET,

    @SerialName("CODE")
    CODE,

    @SerialName("IF")
    IF,

    @SerialName("MERGE")
    MERGE,

    @SerialName("UNKNOWN")
    UNKNOWN;

    companion object {
        /**
         * Parse a kind string into a [NodeType]. Recognises both the canonical
         * enum form (`"TRIGGER"`) and the browser-dot form (`"browser.click"`).
         * Anything else lands on [UNKNOWN] - the stale-node rule exists exactly
         * to surface that case.
         */
        fun fromString(raw: String): NodeType {
            val normalized = raw.trim().uppercase()
            entries.firstOrNull { it.name == normalized }?.let { return it }
            if (raw.startsWith("browser.", ignoreCase = true)) {
                val suffix = raw.substringAfterLast('.').uppercase()
                entries.firstOrNull { it.name == suffix }?.let { return it }
            }
            return UNKNOWN
        }
    }
}

/**
 * One node in a flow graph.
 *
 * `type` is decoded into [NodeType] via [kind]; unknown kinds still load as
 * [NodeType.UNKNOWN] rather than throwing, so the linter can report them
 * instead of failing the parse. `config` is kept as a free-form JSON object
 * because the rules read individual fields (selector, url, condition,
 * template) directly out of it.
 */
@Serializable
data class GraphNode(
    val id: String,
    val kind: String,
    val type: NodeType = NodeType.fromString(kind),
    val name: String = "",
    val config: JsonObject = JsonObject(emptyMap()),
)

/**
 * One directed edge in a flow graph.
 *
 * Edges are node-id pairs, no port indices here - the flow-tab format is the
 * same shape the linter reads.
 */
@Serializable
data class GraphEdge(
    val from: String,
    val to: String,
)

/**
 * The full graph the linter reads.
 *
 * Built to accept either the canonical flow-tab shape (nodes + edges) or the
 * older kind-only shape. Both fields default so a minimal payload still
 * parses; the linter then walks what is there.
 */
@Serializable
data class Graph(
    val id: String = "",
    val name: String = "",
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
)

/**
 * Helper for reading optional string fields out of a node's `config` object.
 *
 * Configs flow into the linter as [JsonObject] because the rules read several
 * shapes (selector, url, condition, template) and we don't want to model them
 * all. `configString(node, "selector")` returns the trimmed value or an empty
 * string when the field is missing.
 */
internal fun configString(node: GraphNode, key: String): String {
    val element: JsonElement = node.config[key] ?: return ""
    val primitive = element as? JsonPrimitive ?: return ""
    return primitive.content.trim()
}

/** Read a JSON object's string field, returning `null` when absent. */
internal fun JsonObject.optString(key: String): String? {
    val element: JsonElement = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content.takeIf { it.isNotEmpty() }
}

/** A node's `name` when present, otherwise the id - used for human messages. */
internal fun GraphNode.displayName(): String =
    if (name.isNotBlank()) name else id

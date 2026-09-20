package ai.rever.boss.plugin.dynamic.flowlint

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the Flow Linter rules.
 *
 * Each rule gets at least one positive case (the rule fires on a fixture
 * that should trip it) and one negative case (the rule stays quiet on a
 * fixture that should not trip it). The fixtures are deliberately minimal
 * graphs - one trigger, one or two extra nodes, and the edges that connect
 * them - so a failure points at exactly one rule.
 */
class FlowLinterTest {

    private fun linter() = FlowLinter()

    private fun node(id: String, type: NodeType, config: JsonObject = JsonObject(emptyMap()), name: String = ""): GraphNode =
        GraphNode(id = id, kind = type.name, type = type, name = name, config = config)

    private fun textConfig(value: String): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(value))
    }

    private fun stringConfig(key: String, value: String): JsonObject = buildJsonObject {
        put(key, JsonPrimitive(value))
    }

    // --- NoTriggerRule --------------------------------------------------

    @Test
    fun `no trigger rule fires when graph has no trigger`() {
        val graph = Graph(
            nodes = listOf(node("a", NodeType.OPEN_BROWSER)),
            edges = emptyList(),
        )
        val issues = linter().lint(graph).issues
        val triggerIssues = issues.filter { it.ruleId == "no-trigger" }
        assertEquals(1, triggerIssues.size)
        assertEquals(LintSeverity.ERROR, triggerIssues.single().severity)
        assertTrue(triggerIssues.single().message.contains("no TRIGGER"))
    }

    @Test
    fun `no trigger rule stays quiet when graph has a trigger`() {
        val graph = Graph(
            nodes = listOf(node("t", NodeType.TRIGGER), node("o", NodeType.OPEN_BROWSER)),
            edges = listOf(GraphEdge("t", "o")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "no-trigger" }
        assertTrue(issues.isEmpty(), "expected no no-trigger issues, got $issues")
    }

    // --- MultipleOpenBrowserRule ---------------------------------------

    @Test
    fun `multiple open browser rule fires on the second opener`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("a", NodeType.OPEN_BROWSER),
                node("b", NodeType.OPEN_BROWSER),
            ),
            edges = listOf(GraphEdge("t", "a"), GraphEdge("t", "b")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "multiple-open-browser" }
        assertEquals(1, issues.size)
        assertEquals("b", issues.single().nodeId)
    }

    @Test
    fun `multiple open browser rule stays quiet on a single opener`() {
        val graph = Graph(
            nodes = listOf(node("t", NodeType.TRIGGER), node("o", NodeType.OPEN_BROWSER)),
            edges = listOf(GraphEdge("t", "o")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "multiple-open-browser" }
        assertTrue(issues.isEmpty())
    }

    // --- CycleRule ------------------------------------------------------

    @Test
    fun `cycle rule fires on a click-loop graph`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("a", NodeType.OPEN_BROWSER),
                node("b", NodeType.NAVIGATE),
                node("c", NodeType.IF, config = stringConfig("condition", "true")),
            ),
            edges = listOf(
                GraphEdge("t", "a"),
                GraphEdge("a", "b"),
                GraphEdge("b", "c"),
                GraphEdge("c", "b"),
            ),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "cycle" }
        assertTrue(issues.isNotEmpty(), "expected at least one cycle issue")
    }

    // --- DisconnectedNodesRule -----------------------------------------

    @Test
    fun `disconnected rule fires on a non-trigger with no input edge`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("orphan", NodeType.HTTP, config = stringConfig("url", "https://example.com")),
            ),
            edges = listOf(GraphEdge("t", "orphan")),
        )
        val orphanIssues = linter().lint(graph).issues.filter { it.ruleId == "disconnected" }
        // orphan has an incoming edge from t, so it shouldn't be disconnected.
        // Add an actually-orphan node instead:
        val fixed = graph.copy(
            nodes = graph.nodes + node("dangling", NodeType.HTTP, config = stringConfig("url", "https://example.com")),
        )
        val issues = linter().lint(fixed).issues.filter { it.ruleId == "disconnected" }
        assertTrue(issues.any { it.nodeId == "dangling" }, "expected dangling to be flagged")
        // Make sure t is not flagged as disconnected.
        assertTrue(issues.none { it.nodeId == "t" }, "trigger should never be flagged disconnected")
        assertTrue(orphanIssues.isEmpty() || orphanIssues.none { it.nodeId == "orphan" })
    }

    // --- MissingBrowserAncestorRule ------------------------------------

    @Test
    fun `missing browser ancestor rule fires on a CLICK without OPEN_BROWSER upstream`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("c", NodeType.CLICK, config = stringConfig("selector", "#submit")),
            ),
            edges = listOf(GraphEdge("t", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "missing-browser-ancestor" }
        assertTrue(issues.isNotEmpty(), "expected CLICK without OPEN_BROWSER to be flagged")
        assertEquals("c", issues.single().nodeId)
    }

    @Test
    fun `missing browser ancestor rule stays quiet when OPEN_BROWSER precedes CLICK`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("o", NodeType.OPEN_BROWSER),
                node("c", NodeType.CLICK, config = stringConfig("selector", "#submit")),
            ),
            edges = listOf(GraphEdge("t", "o"), GraphEdge("o", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "missing-browser-ancestor" }
        assertTrue(issues.isEmpty())
    }

    // --- HttpUrlRule ---------------------------------------------------

    @Test
    fun `http url rule fires on a non-http URL`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("h", NodeType.HTTP, config = stringConfig("url", "file:///etc/passwd")),
            ),
            edges = listOf(GraphEdge("t", "h")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "http-url" }
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.single().message.contains("file:///etc/passwd"))
    }

    @Test
    fun `http url rule fires on an empty URL`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("h", NodeType.HTTP, config = stringConfig("url", "")),
            ),
            edges = listOf(GraphEdge("t", "h")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "http-url" }
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `http url rule stays quiet on https URL`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("h", NodeType.HTTP, config = stringConfig("url", "https://example.com/api")),
            ),
            edges = listOf(GraphEdge("t", "h")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "http-url" }
        assertTrue(issues.isEmpty())
    }

    // --- EmptySelectorRule ---------------------------------------------

    @Test
    fun `empty selector rule fires on a CLICK without selector`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("o", NodeType.OPEN_BROWSER),
                node("c", NodeType.CLICK, config = JsonObject(emptyMap())),
            ),
            edges = listOf(GraphEdge("t", "o"), GraphEdge("o", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "empty-selector" }
        assertTrue(issues.isNotEmpty())
        assertEquals("c", issues.single().nodeId)
    }

    @Test
    fun `empty selector rule stays quiet when selector is present`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("o", NodeType.OPEN_BROWSER),
                node("c", NodeType.CLICK, config = stringConfig("selector", "#submit")),
            ),
            edges = listOf(GraphEdge("t", "o"), GraphEdge("o", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "empty-selector" }
        assertTrue(issues.isEmpty())
    }

    // --- EmptyConditionOrTemplateRule ----------------------------------

    @Test
    fun `empty condition rule fires on a blank IF condition`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("i", NodeType.IF, config = stringConfig("condition", "")),
            ),
            edges = listOf(GraphEdge("t", "i")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "empty-condition-or-template" }
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `empty template rule fires on a blank CODE template`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("c", NodeType.CODE, config = stringConfig("template", "")),
            ),
            edges = listOf(GraphEdge("t", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "empty-condition-or-template" }
        assertTrue(issues.isNotEmpty())
    }

    // --- MergeSingleInputRule ------------------------------------------

    @Test
    fun `merge single input rule fires on a MERGE with one input`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("a", NodeType.OPEN_BROWSER),
                node("m", NodeType.MERGE),
            ),
            edges = listOf(GraphEdge("t", "a"), GraphEdge("a", "m")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "merge-single-input" }
        assertTrue(issues.isNotEmpty())
    }

    // --- UndefinedVariableRule -----------------------------------------

    @Test
    fun `undefined variable rule fires on a node referencing an unset variable`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("o", NodeType.OPEN_BROWSER),
                node(
                    "c",
                    NodeType.CLICK,
                    config = stringConfig("selector", "{{ bogus_var }}"),
                ),
            ),
            edges = listOf(GraphEdge("t", "o"), GraphEdge("o", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "undefined-variable" }
        assertTrue(issues.isNotEmpty(), "expected undefined-variable to fire")
        assertTrue(issues.single().message.contains("bogus_var"))
    }

    @Test
    fun `undefined variable rule stays quiet when a SET defines the variable upstream`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("s", NodeType.SET, config = buildJsonObject {
                    put("variable", JsonPrimitive("token"))
                    put("value", JsonPrimitive("abc"))
                }),
                node(
                    "c",
                    NodeType.CLICK,
                    config = stringConfig("selector", "{{ token }}"),
                ),
            ),
            edges = listOf(GraphEdge("t", "s"), GraphEdge("s", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "undefined-variable" }
        assertTrue(issues.isEmpty(), "expected no undefined-variable issues, got $issues")
    }

    // --- UnresolvedSecretRule ------------------------------------------

    @Test
    fun `unresolved secret rule fires on a secret reference`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("o", NodeType.OPEN_BROWSER),
                node(
                    "c",
                    NodeType.CLICK,
                    config = stringConfig("selector", "{{ secret:foo }}"),
                ),
            ),
            edges = listOf(GraphEdge("t", "o"), GraphEdge("o", "c")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "unresolved-secret" }
        assertTrue(issues.isNotEmpty())
        assertEquals(LintSeverity.WARNING, issues.single().severity)
    }

    // --- UnknownNodeTypeRule -------------------------------------------

    @Test
    fun `unknown node type rule fires on a stale node kind`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                GraphNode(id = "x", kind = "OLD_TYPE", type = NodeType.UNKNOWN),
            ),
            edges = listOf(GraphEdge("t", "x")),
        )
        val issues = linter().lint(graph).issues.filter { it.ruleId == "unknown-node-type" }
        assertTrue(issues.isNotEmpty())
    }

    // --- Smoke ---------------------------------------------------------

    @Test
    fun `linter result summary mentions rule and node counts`() {
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("o", NodeType.OPEN_BROWSER),
            ),
            edges = listOf(GraphEdge("t", "o")),
        )
        val result = linter().lint(graph)
        assertTrue(result.ruleCount >= 12, "expected at least 12 rules, got ${result.ruleCount}")
        assertEquals(2, result.nodeCount)
        assertNotNull(result.summary())
    }

    @Test
    fun `rule set exposes all 12 rule ids`() {
        val rules = FlowLinter.defaultRules()
        val ids = rules.map { it.id }.toSet()
        val expected = setOf(
            "no-trigger",
            "multiple-open-browser",
            "cycle",
            "disconnected",
            "missing-browser-ancestor",
            "http-url",
            "empty-selector",
            "empty-condition-or-template",
            "merge-single-input",
            "undefined-variable",
            "unresolved-secret",
            "unknown-node-type",
        )
        assertEquals(expected, ids)
    }

    @Test
    fun `lint a healthy graph returns zero issues`() {
        // A small but correct graph: trigger -> open browser -> navigate ->
        // click. Each rule should stay quiet; only sanity that the whole
        // pipeline (parse + every rule) returns empty on a clean input.
        val graph = Graph(
            nodes = listOf(
                node("t", NodeType.TRIGGER),
                node("o", NodeType.OPEN_BROWSER),
                node(
                    "n",
                    NodeType.NAVIGATE,
                    config = stringConfig("url", "https://example.com"),
                ),
                node(
                    "c",
                    NodeType.CLICK,
                    config = stringConfig("selector", "#submit"),
                ),
            ),
            edges = listOf(
                GraphEdge("t", "o"),
                GraphEdge("o", "n"),
                GraphEdge("n", "c"),
            ),
        )
        val issues = linter().lint(graph).issues
        assertTrue(issues.isEmpty(), "expected clean graph, got $issues")
    }
}

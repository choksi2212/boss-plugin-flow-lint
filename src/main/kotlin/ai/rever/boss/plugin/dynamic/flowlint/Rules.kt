package ai.rever.boss.plugin.dynamic.flowlint

/**
 * The 12 lint rules this build ships.
 *
 * Each rule is one `object` (stateless, no per-call allocations beyond the
 * issue list) and exposes the [LintRule] interface. The linter drives them
 * through that interface; this file owns the bodies.
 *
 * Naming: `XxxRule` mirrors the host's "many small types in one file" shape
 * (e.g. PluginStorageProvider rule list). New rules are added by appending
 * an entry to [FlowLinter.defaultRules] - no other wiring.
 */
object NoTriggerRule : LintRule {
    override val id = "no-trigger"
    override val description = "Graph must contain exactly one TRIGGER node; none means the flow is unrunnable."

    override fun check(graph: Graph): List<LintIssue> {
        val triggers = graph.nodes.filter { it.type == NodeType.TRIGGER }
        return when {
            triggers.isEmpty() -> listOf(
                LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "no TRIGGER node - graph is unrunnable",
                    nodeId = null,
                    path = emptyList(),
                ),
            )
            triggers.size > 1 -> triggers.drop(1).map { extra ->
                LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "extra TRIGGER node '${extra.displayName()}' - only one TRIGGER is allowed",
                    nodeId = extra.id,
                    path = emptyList(),
                )
            }
            else -> emptyList()
        }
    }
}

object MultipleOpenBrowserRule : LintRule {
    override val id = "multiple-open-browser"
    override val description = "Only one OPEN_BROWSER node is allowed per run; the executor fences the browser."

    override fun check(graph: Graph): List<LintIssue> {
        val opens = graph.nodes.filter { it.type == NodeType.OPEN_BROWSER }
        if (opens.size <= 1) return emptyList()
        return opens.drop(1).map { extra ->
            LintIssue(
                ruleId = id,
                severity = LintSeverity.ERROR,
                message = "more than one OPEN_BROWSER - '${extra.displayName()}' will collide at run time",
                nodeId = extra.id,
                path = pathTo(graph, extra.id),
            )
        }
    }
}

object CycleRule : LintRule {
    override val id = "cycle"
    override val description = "Any cycle in the graph (except TRIGGER->TRIGGER) - the executor will loop forever."

    override fun check(graph: Graph): List<LintIssue> {
        // Build the adjacency map once; cycles are detected by DFS over it.
        val adjacency = buildAdjacency(graph)
        val issues = mutableListOf<LintIssue>()

        for (start in graph.nodes) {
            val path = mutableListOf<String>()
            val seenInPath = mutableSetOf<String>()
            walk(start.id, adjacency, path, seenInPath, graph, issues)
        }

        return issues.distinctBy { it.message }.sortedBy { it.message }
    }

    private fun walk(
        nodeId: String,
        adjacency: Map<String, List<String>>,
        path: MutableList<String>,
        seenInPath: MutableSet<String>,
        graph: Graph,
        issues: MutableList<LintIssue>,
    ) {
        if (nodeId in seenInPath) {
            // Cycle closes at nodeId. Slice the visible cycle out of `path`.
            val cycleStart = path.indexOf(nodeId)
            if (cycleStart < 0) return
            val cycleNodes = path.subList(cycleStart, path.size) + nodeId
            // Skip TRIGGER->TRIGGER loops: those are deliberate self-edges,
            // not cycles in the executor's sense.
            if (cycleNodes.size == 2 &&
                graph.nodes.firstOrNull { it.id == cycleNodes[0] }?.type == NodeType.TRIGGER
            ) {
                return
            }
            val labels = cycleNodes.mapNotNull { id ->
                graph.nodes.firstOrNull { it.id == id }?.displayName()
            }
            issues += LintIssue(
                ruleId = id,
                severity = LintSeverity.ERROR,
                message = "cycle: ${labels.joinToString(" -> ")}",
                nodeId = nodeId,
                path = cycleNodes.toList(),
            )
            return
        }
        seenInPath += nodeId
        path += nodeId
        for (next in adjacency[nodeId].orEmpty()) {
            walk(next, adjacency, path, seenInPath, graph, issues)
        }
        path.removeAt(path.size - 1)
        seenInPath -= nodeId
    }
}

object DisconnectedNodesRule : LintRule {
    override val id = "disconnected"
    override val description = "Any node (other than TRIGGER) with no input edge is unreachable from the run."

    override fun check(graph: Graph): List<LintIssue> {
        val incoming = graph.edges.groupingBy { it.to }.eachCount()
        return graph.nodes
            .filter { it.type != NodeType.TRIGGER && (incoming[it.id] ?: 0) == 0 }
            .map { node ->
                LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "node '${node.displayName()}' (${node.type.name}) is unreachable from TRIGGER",
                    nodeId = node.id,
                    path = emptyList(),
                )
            }
    }
}

object MissingBrowserAncestorRule : LintRule {
    override val id = "missing-browser-ancestor"
    override val description =
        "CLICK/TYPE/EXTRACT/INJECT/NAVIGATE without a preceding OPEN_BROWSER in the same chain have no session."

    override fun check(graph: Graph): List<LintIssue> {
        val browserTypes = setOf(
            NodeType.CLICK,
            NodeType.TYPE,
            NodeType.EXTRACT,
            NodeType.INJECT,
            NodeType.NAVIGATE,
        )
        val adjacency = buildReverseAdjacency(graph)
        val issues = mutableListOf<LintIssue>()

        for (node in graph.nodes.filter { it.type in browserTypes }) {
            // Walk backwards up the chain to find an OPEN_BROWSER. If we never
            // find one before we run out of predecessors, this node has no
            // browser session to draw on.
            val visited = mutableSetOf<String>()
            val stack = ArrayDeque<String>()
            adjacency[node.id]?.forEach { stack.addLast(it) }
            var foundOpenBrowser = false
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (current in visited) continue
                visited += current
                val currentNode = graph.nodes.firstOrNull { it.id == current } ?: continue
                if (currentNode.type == NodeType.OPEN_BROWSER) {
                    foundOpenBrowser = true
                    break
                }
                adjacency[current]?.forEach { stack.addLast(it) }
            }
            if (!foundOpenBrowser) {
                issues += LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "browser node '${node.displayName()}' (${node.type.name}) has no OPEN_BROWSER ancestor",
                    nodeId = node.id,
                    path = pathTo(graph, node.id),
                )
            }
        }
        return issues
    }
}

object HttpUrlRule : LintRule {
    override val id = "http-url"
    override val description = "HTTP node URLs must be non-empty and http(s)."

    override fun check(graph: Graph): List<LintIssue> {
        return graph.nodes.filter { it.type == NodeType.HTTP }.mapNotNull { node ->
            val url = configString(node, "url")
            when {
                url.isBlank() -> LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "HTTP node '${node.displayName()}' has empty URL",
                    nodeId = node.id,
                    path = pathTo(graph, node.id),
                )
                !(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) -> LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "HTTP node '${node.displayName()}' URL '$url' is not http(s)",
                    nodeId = node.id,
                    path = pathTo(graph, node.id),
                )
                else -> null
            }
        }
    }
}

object EmptySelectorRule : LintRule {
    override val id = "empty-selector"
    override val description = "CLICK/TYPE nodes must declare a selector."

    override fun check(graph: Graph): List<LintIssue> {
        val targets = setOf(NodeType.CLICK, NodeType.TYPE)
        return graph.nodes.filter { it.type in targets }.mapNotNull { node ->
            val selector = configString(node, "selector")
            if (selector.isBlank()) {
                LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "${node.type.name} node '${node.displayName()}' has empty selector",
                    nodeId = node.id,
                    path = pathTo(graph, node.id),
                )
            } else {
                null
            }
        }
    }
}

object EmptyConditionOrTemplateRule : LintRule {
    override val id = "empty-condition-or-template"
    override val description = "IF condition and CODE template must not be blank."

    override fun check(graph: Graph): List<LintIssue> {
        val issues = mutableListOf<LintIssue>()
        for (node in graph.nodes) {
            when (node.type) {
                NodeType.IF -> {
                    val cond = configString(node, "condition")
                    if (cond.isBlank()) {
                        issues += LintIssue(
                            ruleId = id,
                            severity = LintSeverity.ERROR,
                            message = "IF node '${node.displayName()}' has empty condition",
                            nodeId = node.id,
                            path = pathTo(graph, node.id),
                        )
                    }
                }
                NodeType.CODE -> {
                    val template = configString(node, "template").ifBlank { configString(node, "code") }
                    if (template.isBlank()) {
                        issues += LintIssue(
                            ruleId = id,
                            severity = LintSeverity.ERROR,
                            message = "CODE node '${node.displayName()}' has empty template",
                            nodeId = node.id,
                            path = pathTo(graph, node.id),
                        )
                    }
                }
                else -> Unit
            }
        }
        return issues
    }
}

object MergeSingleInputRule : LintRule {
    override val id = "merge-single-input"
    override val description = "MERGE nodes need more than one input edge to be meaningful."

    override fun check(graph: Graph): List<LintIssue> {
        val incoming = graph.edges.groupingBy { it.to }.eachCount()
        return graph.nodes.filter { it.type == NodeType.MERGE && (incoming[it.id] ?: 0) < 2 }
            .map { node ->
                LintIssue(
                    ruleId = id,
                    severity = LintSeverity.WARNING,
                    message = "MERGE node '${node.displayName()}' has only ${incoming[node.id] ?: 0} input(s)",
                    nodeId = node.id,
                    path = pathTo(graph, node.id),
                )
            }
    }
}

object UndefinedVariableRule : LintRule {
    override val id = "undefined-variable"
    override val description = "References to `{{ varName }}` that are not defined anywhere upstream are flagged."

    override fun check(graph: Graph): List<LintIssue> {
        // Variable producer types. SET/CODE/EXTRACT and HTTP responses all
        // expose variables; CLICK/TYPE/IF do not. TRIGGER is implicit and
        // always available; OPEN_BROWSER has no variables of its own.
        val producerTypes = setOf(
            NodeType.SET,
            NodeType.CODE,
            NodeType.EXTRACT,
            NodeType.HTTP,
            NodeType.TRIGGER,
        )

        val adjacency = buildReverseAdjacency(graph)
        val issues = mutableListOf<LintIssue>()

        // Variable pattern: {{ varName }} - alphanumeric + underscore + dot.
        val variablePattern = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}""")

        for (node in graph.nodes) {
            val text = collectTextFields(node)
            if (text.isEmpty()) continue

            val referenced = variablePattern.findAll(text)
                .map { it.groupValues[1] }
                // Secret refs (`{{ secret:foo }}`) live in their own rule and
                // are explicitly filtered out here so they don't double-report.
                .filter { !it.startsWith("secret:") }
                .toList()

            if (referenced.isEmpty()) continue

            val upstream = mutableSetOf<String>()
            val stack = ArrayDeque<String>()
            adjacency[node.id]?.forEach { stack.addLast(it) }
            val visited = mutableSetOf<String>()
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (current in visited) continue
                visited += current
                upstream += current
                adjacency[current]?.forEach { stack.addLast(it) }
            }

            val definedVariables = upstream
                .mapNotNull { id -> graph.nodes.firstOrNull { it.id == id } }
                .filter { it.type in producerTypes }
                .flatMap { producer -> extractProducerVariables(producer) }
                .toSet()

            // TRIGGER can introduce variables from event payload - we don't
            // model that here, so treat it as defining everything.
            val effectiveDefined = if (graph.nodes.any { it.type == NodeType.TRIGGER }) {
                definedVariables + "event" + "" // event.* and bare names
            } else {
                definedVariables
            }

            for (variable in referenced.distinct()) {
                if (variable in effectiveDefined) continue
                if (isLikelyDefinedImplicitly(variable)) continue
                issues += LintIssue(
                    ruleId = id,
                    severity = LintSeverity.ERROR,
                    message = "${node.type.name} node '${node.displayName()}' references undefined variable '$variable'",
                    nodeId = node.id,
                    path = pathTo(graph, node.id),
                )
            }
        }
        return issues
    }

    private fun collectTextFields(node: GraphNode): String {
        val sb = StringBuilder()
        for (key in TEXT_KEYS) {
            sb.append(configString(node, key))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun extractProducerVariables(node: GraphNode): List<String> {
        // SET declares its output variable as `variable` or `name`. CODE/EXTRACT
        // expose whatever they `set` or write; we can't statically prove what
        // they write, so we accept any name they declare on a `set` field.
        // HTTP exposes the response as a variable named in `variable`.
        return when (node.type) {
            NodeType.SET -> listOfNotNull(
                configString(node, "variable").ifBlank { null },
                configString(node, "name").ifBlank { null },
            )
            NodeType.CODE -> buildList {
                configString(node, "variable").ifBlank { null }?.let(::add)
                // CODE may declare multiple outputs - one per line that looks
                // like `name = ...` at column zero. Conservative.
                configString(node, "outputs").ifBlank { null }?.let(::add)
            }
            NodeType.EXTRACT -> listOfNotNull(
                configString(node, "variable").ifBlank { null },
                configString(node, "name").ifBlank { null },
            )
            NodeType.HTTP -> listOfNotNull(
                configString(node, "variable").ifBlank { null },
            )
            NodeType.TRIGGER -> listOf("event")
            else -> emptyList()
        }
    }

    /**
     * Implicit definitions that are hard to model but obvious to humans:
     * iterators from a parent IF/MERGE, page elements from OPEN_BROWSER,
     * response.* from HTTP. These are listed so the rule doesn't drown the
     * user in noise on everyday graphs.
     */
    private fun isLikelyDefinedImplicitly(name: String): Boolean {
        return name.startsWith("page.") ||
            name.startsWith("response.") ||
            name.startsWith("input.") ||
            name.startsWith("browser.") ||
            name.startsWith("element.") ||
            name.startsWith("url.") ||
            name == "url" ||
            name == "page" ||
            name == "input" ||
            name == "element"
    }

    private val TEXT_KEYS = listOf(
        "selector",
        "url",
        "condition",
        "template",
        "code",
        "value",
        "text",
        "prompt",
        "description",
        "name",
        "expression",
    )
}

object UnresolvedSecretRule : LintRule {
    override val id = "unresolved-secret"
    override val description =
        "References to `{{ secret:foo }}` without a registered secret provider are warned about."

    override fun check(graph: Graph): List<LintIssue> {
        val issues = mutableListOf<LintIssue>()
        val secretPattern = Regex("""\{\{\s*secret:([A-Za-z0-9_.\-]+)\s*\}\}""")

        for (node in graph.nodes) {
            val text = buildString {
                for (key in SECRET_KEYS) {
                    append(configString(node, key))
                    append('\n')
                }
            }
            if (text.isEmpty()) continue
            for (match in secretPattern.findAll(text)) {
                val name = match.groupValues[1]
                issues += LintIssue(
                    ruleId = id,
                    severity = LintSeverity.WARNING,
                    message = "${node.type.name} node '${node.displayName()}' references secret '$name' " +
                        "but no secret provider is registered",
                    nodeId = node.id,
                    path = pathTo(graph, node.id),
                )
            }
        }
        return issues.distinctBy { "${it.nodeId}:${it.message}" }
    }

    private val SECRET_KEYS = listOf(
        "selector",
        "url",
        "condition",
        "template",
        "code",
        "value",
        "text",
        "prompt",
        "description",
        "name",
        "expression",
    )
}

object UnknownNodeTypeRule : LintRule {
    override val id = "unknown-node-type"
    override val description = "Nodes referencing a NodeType that no longer exists are flagged as stale."

    override fun check(graph: Graph): List<LintIssue> {
        return graph.nodes.filter { it.type == NodeType.UNKNOWN }.map { node ->
            LintIssue(
                ruleId = id,
                severity = LintSeverity.ERROR,
                message = "node '${node.displayName()}' has unknown type '${node.kind}'",
                nodeId = node.id,
                path = pathTo(graph, node.id),
            )
        }
    }
}

// ============================================================================
// helpers
// ============================================================================

private fun buildAdjacency(graph: Graph): Map<String, List<String>> {
    val map = mutableMapOf<String, MutableList<String>>()
    for (edge in graph.edges) {
        map.getOrPut(edge.from) { mutableListOf() } += edge.to
    }
    return map
}

private fun buildReverseAdjacency(graph: Graph): Map<String, List<String>> {
    val map = mutableMapOf<String, MutableList<String>>()
    for (edge in graph.edges) {
        map.getOrPut(edge.to) { mutableListOf() } += edge.from
    }
    return map
}

/**
 * Find one path from any TRIGGER to [targetNodeId], or empty if none.
 *
 * Used for the `path` field on issues: it tells the user how the executor
 * would reach the offending node. The path is BFS-shaped (shortest), not
 * exhaustive - one path is enough for the message.
 */
internal fun pathTo(graph: Graph, targetNodeId: String): List<String> {
    val triggers = graph.nodes.filter { it.type == NodeType.TRIGGER }
    if (triggers.isEmpty()) return emptyList()

    val reverse = buildReverseAdjacency(graph)
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<Pair<String, List<String>>>()
    for (trigger in triggers) {
        queue.addLast(trigger.id to listOf(trigger.id))
    }

    while (queue.isNotEmpty()) {
        val (current, path) = queue.removeFirst()
        if (current == targetNodeId) return path
        if (current in visited) continue
        visited += current
        for (previous in reverse[current].orEmpty()) {
            if (previous in visited) continue
            queue.addLast(previous to (path + previous))
        }
    }
    return emptyList()
}

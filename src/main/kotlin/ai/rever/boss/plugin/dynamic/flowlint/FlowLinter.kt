package ai.rever.boss.plugin.dynamic.flowlint

/**
 * One linter rule.
 *
 * Each rule inspects a [Graph] and returns the [LintIssue]s it found. Rules
 * are deliberately small (one rule, one concern) so the panel can colour them
 * and an agent can ask "which rules fired?". The linter aggregates the issues
 * across every rule it owns.
 *
 * Implementations live in [Rules]; this interface is the one the linter and
 * the MCP tools share so a new rule joins by adding itself to the
 * [FlowLinter.rules] list, nothing else.
 */
interface LintRule {
    /** Stable id shown in lint output. Convention: lower-snake, e.g. `no-trigger`. */
    val id: String

    /** One-line description of what this rule catches. */
    val description: String

    /** Run the rule. Return zero or more issues, in display order. */
    fun check(graph: Graph): List<LintIssue>
}

/**
 * The linter. Owns the rule set, runs them against a graph, and returns the
 * combined [LintResult].
 *
 * Construction has no dependencies - the rule set is a static list of
 * object/class instances. The linter is therefore cheap to allocate and the
 * MCP tools can build one per call.
 */
class FlowLinter(
    val rules: List<LintRule> = defaultRules(),
) {
    /** Lint the given [graph] across every rule. */
    fun lint(graph: Graph): LintResult {
        val issues = rules.flatMap { rule ->
            try {
                rule.check(graph)
            } catch (_: Exception) {
                // A single rule throwing must not poison the rest. Surface the
                // failure as an issue so the user can see which rule tripped,
                // rather than silently dropping it.
                listOf(
                    LintIssue(
                        ruleId = rule.id,
                        severity = LintSeverity.WARNING,
                        message = "${rule.id}: rule raised an exception while linting",
                        nodeId = null,
                        path = emptyList(),
                    ),
                )
            }
        }
        return LintResult(
            issues = issues,
            ruleCount = rules.size,
            nodeCount = graph.nodes.size,
        )
    }

    companion object {
        /**
         * The full rule set this build ships.
         *
         * Order is meaningful: the panel renders issues in the order the
         * rules emitted them, so heavier rules (cycles, no trigger) sit
         * first. Adding a new rule means appending here.
         */
        fun defaultRules(): List<LintRule> = listOf(
            NoTriggerRule,
            MultipleOpenBrowserRule,
            CycleRule,
            DisconnectedNodesRule,
            MissingBrowserAncestorRule,
            HttpUrlRule,
            EmptySelectorRule,
            EmptyConditionOrTemplateRule,
            MergeSingleInputRule,
            UndefinedVariableRule,
            UnresolvedSecretRule,
            UnknownNodeTypeRule,
        )
    }
}

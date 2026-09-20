package ai.rever.boss.plugin.dynamic.flowlint

import kotlinx.serialization.Serializable

/**
 * Severity of a lint finding.
 *
 * `error` means the flow cannot run as built. `warning` means it can run but
 * has a shape that almost always indicates a mistake. `info` is advisory.
 */
@Serializable
enum class LintSeverity {
    @Serializable
    ERROR,

    @Serializable
    WARNING,

    @Serializable
    INFO,
}

/**
 * One finding from a lint pass.
 *
 * `ruleId` identifies the rule (matches [LintRule.id]). `message` is the
 * human-readable line shown in the panel. `nodeId` is null for graph-level
 * rules. `path` is the chain from a TRIGGER to the offending node when the
 * rule had to walk a chain to detect it; empty otherwise.
 */
@Serializable
data class LintIssue(
    val ruleId: String,
    val severity: LintSeverity,
    val message: String,
    val nodeId: String? = null,
    val path: List<String> = emptyList(),
)

/**
 * The aggregated result of a lint pass.
 *
 * `issues` are the per-finding records. `ruleCount` and `nodeCount` are the
 * totals the linter observed - useful for the panel's header line and for
 * the MCP tools' reply string.
 */
@Serializable
data class LintResult(
    val issues: List<LintIssue>,
    val ruleCount: Int,
    val nodeCount: Int,
) {
    /** Short text the panel renders in the header. */
    fun summary(): String {
        val errors = issues.count { it.severity == LintSeverity.ERROR }
        val warnings = issues.count { it.severity == LintSeverity.WARNING }
        val infos = issues.count { it.severity == LintSeverity.INFO }
        return "errors=$errors warnings=$warnings info=$infos nodes=$nodeCount rules=$ruleCount"
    }
}

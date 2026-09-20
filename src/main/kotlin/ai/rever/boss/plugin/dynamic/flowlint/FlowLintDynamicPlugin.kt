package ai.rever.boss.plugin.dynamic.flowlint

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

/**
 * Flow Lint dynamic plugin - loaded from external JAR.
 *
 * The first standalone validator for flow-tab graphs. Two surfaces:
 *
 *  - A sidebar panel (`FlowLintInfo`) with a paste-a-JSON box and a Lint
 *    button, plus an "Lint active graph" section that reads the open
 *    flow-tab graph through the host's MCP registry and lints it.
 *  - An MCP tool provider exposing `flow_lint_check`,
 *    `flow_lint_check_active`, and `flow_lint_rules`, removed automatically
 *    when the plugin is disabled or unloaded.
 *
 * The plugin has no compile-time references to flow-tab; it reaches the
 * active graph through MCP tool names registered in the host's
 * [ai.rever.boss.plugin.api.McpToolRegistry]. On hosts that predate the
 * registry, the panel's "Lint active graph" button degrades to a status
 * line and `flow_lint_check_active` returns a clear error.
 */
class FlowLintDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.flowlint"
    override val displayName: String = "Flow Lint (Dynamic)"
    override val version: String = manifestVersion()
    override val description: String =
        "Pre-run validator for flow-tab graphs - 12 rules flag unreachable nodes, missing TRIGGERs, " +
            "missing browser ancestors, empty selectors, HTTP URL mistakes, undefined variables, " +
            "unresolved secrets, and stale node types before a flow runs."
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/choksi2212/boss-plugin-flow-lint"

    /**
     * Last opened panel, so the MCP tool provider can drive the same state
     * the panel renders. A destroyed component's scope is cancelled, so MCP
     * tools driving a destroyed component would silently no-op with false
     * success - clear the reference when the panel closes.
     */
    @Volatile
    private var lastComponent: FlowLintComponent? = null

    /** Held so we can unregister in [dispose] if the host ever asks. */
    private var toolProvider: FlowLintMcpToolProvider? = null

    override fun register(context: PluginContext) {
        val linter = FlowLinter()
        val registry = context.mcpToolRegistry

        context.panelRegistry.registerPanel(FlowLintInfo) { ctx, panelInfo ->
            FlowLintComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                linter = linter,
                registry = registry,
                pluginScope = context.pluginScope,
            ).also { comp ->
                lastComponent = comp
                ctx.lifecycle.doOnDestroy {
                    if (lastComponent === comp) {
                        lastComponent = null
                    }
                }
            }
        }

        val provider = FlowLintMcpToolProvider(
            providerId = pluginId,
            linter = linter,
            registry = registry,
            component = { lastComponent },
        )
        toolProvider = provider
        context.registerMcpToolProvider(provider)
    }

    override fun dispose() {
        lastComponent = null
        toolProvider = null
    }

    /**
     * The version from *this* plugin's manifest.
     *
     * Every BOSS plugin ships `/META-INF/boss-plugin/plugin.json` at the same
     * resource path, so a single `getResourceAsStream` returns whichever jar
     * comes first if the host ever loads plugins through a shared or
     * parent-first classloader - and this plugin would report someone else's
     * version. Only the one naming this plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}

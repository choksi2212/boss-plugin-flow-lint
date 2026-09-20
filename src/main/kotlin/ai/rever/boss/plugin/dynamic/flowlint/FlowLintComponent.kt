package ai.rever.boss.plugin.dynamic.flowlint

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Panel component for Flow Lint.
 *
 * Owns the [FlowLintViewModel]. The MCP tools reach the same ViewModel through
 * the plugin entry point's last-component reference, the same shape
 * flow-bridge uses. The component itself does no linting; every action is
 * forwarded to the ViewModel, which runs the work on [pluginScope].
 */
class FlowLintComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val linter: FlowLinter,
    private val registry: McpToolRegistry?,
    private val pluginScope: CoroutineScope,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = FlowLintViewModel(
        linter = linter,
        registry = registry,
        pluginScope = pluginScope,
    )

    /** Exposed so the MCP tool provider can drive results into the panel. */
    val exposedViewModel: FlowLintViewModel get() = viewModel

    @Composable
    override fun Content() {
        FlowLintContent(viewModel = viewModel)
    }
}

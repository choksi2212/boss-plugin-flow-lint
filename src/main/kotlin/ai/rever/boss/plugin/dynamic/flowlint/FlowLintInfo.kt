package ai.rever.boss.plugin.dynamic.flowlint

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare

/**
 * Flow Lint panel info.
 *
 * Lives in the left bottom slot at priority 62 - just below Plugin X-Ray
 * (60) and the heavier management surfaces above it. Lint is a lightweight
 * validator panel, so a low-priority slot keeps it from competing with
 * editing surfaces while still being one slot away from the read-mostly
 * analysis tools.
 */
object FlowLintInfo : PanelInfo {
    override val id = PanelId("flow-lint", 62)
    override val displayName = "Flow Lint"
    override val icon = FeatherIcons.CheckSquare
    override val defaultSlotPosition = left.bottom
}

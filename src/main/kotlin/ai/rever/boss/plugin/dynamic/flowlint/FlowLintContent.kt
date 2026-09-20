package ai.rever.boss.plugin.dynamic.flowlint

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The Flow Lint panel.
 *
 * Two stacked sections:
 *
 *  1. **Paste a graph** - a multi-line text box for the user to drop a
 *     flow-tab JSON, with a Lint button that runs the rules.
 *  2. **Active graph** - a button that reads flow-tab's active graph via
 *     `flow_get` and lints it. The section collapses to a status line when
 *     flow-tab is not loaded.
 *
 * Below the controls sits the rendered [LintResult]: a coloured list of
 * issues with the rule id, severity, message, and (when the linter found
 * one) the chain from TRIGGER to the offending node.
 */
@Composable
fun FlowLintContent(viewModel: FlowLintViewModel) {
    BossTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderRow()

                Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

                val info by viewModel.info.collectAsState()
                val error by viewModel.error.collectAsState()
                Toast(info = info, error = error, onDismiss = { viewModel.clearMessages() })

                ControlsSection(viewModel = viewModel)
                ResultSection(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Flow Lint",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
    }
}

@Composable
private fun Toast(info: String?, error: String?, onDismiss: () -> Unit) {
    if (info == null && error == null) return
    LaunchedEffect(info, error) {
        delay(4000)
        onDismiss()
    }
    val message = error ?: info ?: return
    val isError = error != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.Error else Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = BossThemeColors.TextPrimary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = BossThemeColors.TextPrimary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ControlsSection(viewModel: FlowLintViewModel) {
    val activeLoaded by viewModel.activeGraphLoaded.collectAsState()
    val activeName by viewModel.activeGraphName.collectAsState()

    var rawText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Paste a graph",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
        )

        OutlinedTextField(
            value = rawText,
            onValueChange = { rawText = it },
            placeholder = { Text("{ \"nodes\": [...], \"edges\": [...] }", fontSize = 10.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            colors = TextFieldDefaults.outlinedTextFieldColors(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { viewModel.lintPasted(rawText.trim()) },
                modifier = Modifier.height(28.dp),
                colors = ButtonDefaults.buttonColors(),
                enabled = rawText.isNotBlank(),
            ) {
                Text(text = "Lint pasted", fontSize = 11.sp, color = BossThemeColors.TextPrimary)
            }
            OutlinedButton(
                onClick = { rawText = "" },
                modifier = Modifier.height(28.dp),
                enabled = rawText.isNotEmpty(),
            ) {
                Text(text = "Clear", fontSize = 11.sp)
            }
        }

        Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (activeLoaded) BossThemeColors.SuccessColor else BossThemeColors.WarningColor,
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Active graph",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            if (activeName != null) {
                Text(
                    text = activeName.orEmpty(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (activeLoaded) {
            Button(
                onClick = { viewModel.lintActiveGraph() },
                modifier = Modifier.height(28.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(text = "Lint active graph", fontSize = 11.sp, color = BossThemeColors.TextPrimary)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = BossThemeColors.WarningColor.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .background(BossThemeColors.WarningColor.copy(alpha = 0.05f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = BossThemeColors.WarningColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "flow-tab not loaded - install from Toolbox",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground,
                )
            }
        }
    }
}

@Composable
private fun ResultSection(viewModel: FlowLintViewModel) {
    val result by viewModel.lastResult.collectAsState()
    if (result == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Text(
                text = "Lint a graph above; issues appear here.",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }
        return
    }
    val current = result ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SummaryBadge(label = "errors", count = current.issues.count { it.severity == LintSeverity.ERROR })
            Spacer(modifier = Modifier.width(8.dp))
            SummaryBadge(label = "warnings", count = current.issues.count { it.severity == LintSeverity.WARNING })
            Spacer(modifier = Modifier.width(8.dp))
            SummaryBadge(label = "info", count = current.issues.count { it.severity == LintSeverity.INFO })
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.clearResult() },
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(text = "Clear", fontSize = 10.sp)
            }
        }

        if (current.issues.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = BossThemeColors.SuccessColor.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .background(BossThemeColors.SuccessColor.copy(alpha = 0.08f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = BossThemeColors.SuccessColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "no issues across ${current.ruleCount} rule(s)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground,
                )
            }
        } else {
            for (issue in current.issues) {
                IssueRow(issue = issue)
            }
        }
    }
}

@Composable
private fun SummaryBadge(label: String, count: Int) {
    val color = when (label) {
        "errors" -> if (count > 0) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor
        "warnings" -> if (count > 0) BossThemeColors.WarningColor else BossThemeColors.SuccessColor
        else -> MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label=$count",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

@Composable
private fun IssueRow(issue: LintIssue) {
    val tone = when (issue.severity) {
        LintSeverity.ERROR -> BossThemeColors.ErrorColor
        LintSeverity.WARNING -> BossThemeColors.WarningColor
        LintSeverity.INFO -> MaterialTheme.colors.onBackground.copy(alpha = 0.5f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = tone.copy(alpha = 0.4f),
                shape = RoundedCornerShape(4.dp),
            )
            .background(tone.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = issue.ruleId,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = tone,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = issue.severity.name.lowercase(),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = tone,
            )
        }
        Text(
            text = issue.message,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground,
        )
        if (issue.path.isNotEmpty()) {
            Text(
                text = issue.path.joinToString(" -> "),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

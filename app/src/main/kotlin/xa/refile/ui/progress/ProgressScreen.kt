package xa.refile.ui.progress

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.core.rename.RenameOperation
import xa.refile.core.rename.RenameReport
import xa.refile.core.rename.RenameResult
import xa.refile.ui.theme.ErrorRed
import xa.refile.ui.theme.SuccessGreen
import xa.refile.ui.theme.TextSecondary
import xa.refile.ui.theme.WarningAmber

/**
 * 执行进度页 + 结果报告页（计划 §M4 Task 4.3）。
 *
 * 单页面承载两态：
 * - 执行中：线性进度条 progressCurrent/progressTotal + 当前文件名（monospace 灰色小字）+ 取消按钮。
 * - 完成：顶部大图标（✅ 全部成功 / ⚠️ 部分失败 / ❌ 全部失败 / 取消 / 出错）+ 统计卡片
 *   （成功 N / 失败 M / 跳过 K）+ 失败项列表（原文件名 + 原因 + HTTP 状态码）+ 跳过项可折叠 +
 *   底部「重试失败项」「返回首页」按钮。
 *
 * 状态全部来自 [ProgressViewModel]；[onBackHome] 由导航层提供（返回服务器列表首页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onBackHome: () -> Unit,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val workInfo by viewModel.workInfo.collectAsStateWithLifecycle()
    val progressCurrent by viewModel.progressCurrent.collectAsStateWithLifecycle()
    val progressTotal by viewModel.progressTotal.collectAsStateWithLifecycle()
    val currentFilename by viewModel.currentFilename.collectAsStateWithLifecycle()
    val isFinished by viewModel.isFinished.collectAsStateWithLifecycle()
    val isCancelled by viewModel.isCancelled.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    // 用局部 val 承接可空 report 以获得 smart-cast（by 委托属性无法 smart-cast）。
    val r = report
    val resultKind = when {
        isCancelled -> ResultKind.CANCELLED
        r == null -> ResultKind.ERROR
        r.failed == 0 -> ResultKind.ALL_SUCCESS
        r.succeeded == 0 -> ResultKind.ALL_FAILURE
        else -> ResultKind.PARTIAL_FAILURE
    }
    val hasFailures = resultKind == ResultKind.ALL_FAILURE ||
        resultKind == ResultKind.PARTIAL_FAILURE

    // B27: WorkInfo 进入 terminal state 时 ViewModel 会清零 progressCurrent/progressTotal，
    // 而 Crossfade(300) 过渡期间旧 RunningContent 仍显示，会出现 "0 / 0" 闪烁。
    // 用快照缓存最后一次非零的进度值，过渡期 RunningContent 显示快照而非已清零的实时值。
    var lastCurrent by remember { mutableStateOf(0) }
    var lastTotal by remember { mutableStateOf(0) }
    if (!isFinished && progressTotal > 0) {
        lastCurrent = progressCurrent
        lastTotal = progressTotal
    }
    val displayCurrent = if (isFinished) lastCurrent else progressCurrent
    val displayTotal = if (isFinished) lastTotal else progressTotal

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isFinished) stringResource(R.string.progress_done) else stringResource(R.string.progress_in_progress),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
            )
        },
        bottomBar = {
            if (!isFinished) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = { viewModel.cancelWork() }) { Text(stringResource(R.string.common_cancel)) }
                }
            } else {
                ResultButtons(
                    hasFailures = hasFailures,
                    onRetry = viewModel::retryFailed,
                    onBackHome = onBackHome,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Task 5.5：执行中 → 完成 用 crossfade(300) 过渡。
            Crossfade(
                targetState = isFinished,
                animationSpec = tween(300),
                label = "progressState",
            ) { finished ->
                if (!finished) {
                    RunningContent(
                        current = displayCurrent,
                        total = displayTotal,
                        filename = currentFilename,
                        pending = workInfo == null,
                    )
                } else {
                    ResultContent(
                        resultKind = resultKind,
                        report = r,
                        errorMessage = errorMessage,
                    )
                }
            }
        }
    }
}

/** 结果态分类，决定大图标与文案。 */
private enum class ResultKind { ALL_SUCCESS, PARTIAL_FAILURE, ALL_FAILURE, CANCELLED, ERROR }

// ---- 执行中态 ----

@Composable
private fun RunningContent(
    current: Int,
    total: Int,
    filename: String?,
    pending: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (pending || total <= 0) {
            // 尚未观察到 WorkInfo，或 Worker 还未上报进度 → 不确定进度。
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.progress_preparing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.progress_in_progress), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.progress_ratio, current, total),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                // B23: 防御性内联检查，不依赖外层 if (total <= 0) 守卫。
                progress = { if (total > 0) current.toFloat() / total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            // 当前文件名：monospace 灰色小字。
            filename?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- 完成态 ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultContent(
    resultKind: ResultKind,
    report: RenameReport?,
    errorMessage: String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ResultHeader(resultKind, errorMessage) }
        if (report != null) {
            item { StatsCard(report) }
            val failedOps = report.failedOperations
            if (failedOps.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.progress_failed_items), failedOps.size, ErrorRed) }
                items(failedOps, key = { it.first.sourcePath }) { (op, failed) ->
                    FailedItemRow(
                        sourcePath = op.sourcePath,
                        reason = failed.reason,
                        httpCode = failed.httpCode,
                        modifier = Modifier.animateItemPlacement(),
                    )
                }
            }
            val skipped = report.results.mapNotNull { (op, res) ->
                (res as? RenameResult.Skipped)?.let { op to it }
            }
            if (skipped.isNotEmpty()) {
                item { SkippedSection(skipped) }
            }
        }
        // 底部按钮区留白，避免列表末项被 bottomBar 遮挡。
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun ResultHeader(resultKind: ResultKind, errorMessage: String?) {
    val icon: ImageVector
    val color: Color
    val title: String
    when (resultKind) {
        ResultKind.ALL_SUCCESS -> {
            icon = Icons.Filled.CheckCircle; color = SuccessGreen; title = stringResource(R.string.progress_all_success)
        }
        ResultKind.PARTIAL_FAILURE -> {
            icon = Icons.Filled.Warning; color = WarningAmber; title = stringResource(R.string.progress_partial_failure)
        }
        ResultKind.ALL_FAILURE -> {
            icon = Icons.Filled.Error; color = ErrorRed; title = stringResource(R.string.progress_all_failure)
        }
        ResultKind.CANCELLED -> {
            icon = Icons.Filled.Cancel; color = TextSecondary; title = stringResource(R.string.progress_cancelled)
        }
        ResultKind.ERROR -> {
            icon = Icons.Filled.Error; color = ErrorRed; title = stringResource(R.string.progress_error)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (errorMessage != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 统计卡片：成功 N / 失败 M / 跳过 K。 */
@Composable
private fun StatsCard(report: RenameReport) {
    val skipped = report.total - report.succeeded - report.failed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCell(stringResource(R.string.progress_stat_success), report.succeeded, SuccessGreen)
            StatCell(stringResource(R.string.progress_stat_failed), report.failed, ErrorRed)
            StatCell(stringResource(R.string.progress_stat_skipped), skipped, TextSecondary)
        }
    }
}

@Composable
private fun StatCell(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, color: Color) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun FailedItemRow(
    sourcePath: String,
    reason: String,
    httpCode: Int?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = sourcePath.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (httpCode != null) {
            Text(
                text = "HTTP $httpCode",
                style = MaterialTheme.typography.labelSmall,
                color = ErrorRed,
            )
        }
    }
    HorizontalDivider()
}

/** 跳过项可折叠列表。 */
@Composable
private fun SkippedSection(skipped: List<Pair<RenameOperation, RenameResult.Skipped>>) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.progress_skipped_items, skipped.size),
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.progress_collapse) else stringResource(R.string.progress_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            skipped.forEach { (op, res) ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = op.sourcePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = res.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultButtons(
    hasFailures: Boolean,
    onRetry: () -> Unit,
    onBackHome: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        if (hasFailures) {
            Button(onClick = onRetry) { Text(stringResource(R.string.progress_retry_failed)) }
        }
        OutlinedButton(onClick = onBackHome) { Text(stringResource(R.string.progress_back_home)) }
    }
}

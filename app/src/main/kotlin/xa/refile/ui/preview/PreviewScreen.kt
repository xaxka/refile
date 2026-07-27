package xa.refile.ui.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import xa.refile.core.matcher.MatchCandidate
import xa.refile.core.model.MediaType
import xa.refile.core.rename.CompanionRename
import xa.refile.ui.common.EmptyState
import xa.refile.ui.match.MatchSessionViewModel
import xa.refile.ui.match.MatchViewModel
import xa.refile.ui.theme.AccentAmber
import xa.refile.ui.theme.ErrorRed
import xa.refile.ui.theme.PosterPlaceholder
import xa.refile.ui.theme.SuccessGreen
import xa.refile.ui.theme.WarningAmber

/** 卡片圆角 / 卡片间距 / 页面左右边距（设计规范：12pt / 12pt / 16pt）。 */
private val CardRadius = 12.dp
private val CardSpacing = 12.dp
private val PageMargin = 16.dp
/** 触控热区最小尺寸（HIG/Material 规范 44pt）。 */
private val MinTouchSize = 44.dp

/**
 * 重命名预览页（计划 §M3 Task 3.4，只预览不执行）。
 *
 * 设计要点：
 * - 顶部统计行压缩为一行小字摘要，支持点击筛选列表（自动/待确认/冲突）。
 * - 卡片三段式：标题行（剧名+集号+状态徽章）→ 上下两行重命名（旧名 → 新名，新名完整显示不截断）
 *   → 操作行（重新匹配 / 伴随 / 排除，统一线性图标，44dp 触控热区）。
 * - 卡片圆角 12pt、间距 12pt、页面边距 16pt；同屏主色不超过 2 个（品牌橙 + 状态语义色）。
 * - 底部按钮把可执行数量并入文案「执行（N）」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PreviewScreen(
    serverId: Long,
    matches: List<MatchViewModel.FileMatch>,
    selectedPaths: List<String>,
    matchType: MatchViewModel.MatchType,
    matchSessionVm: MatchSessionViewModel,
    onBack: () -> Unit,
    onProceedToProgress: (workId: String) -> Unit,
    onEditMatch: (filePath: String) -> Unit,
    onOpenBatchMatch: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel(),
    // 预览页内嵌的匹配 VM：替代原独立 MatchScreen 跑匹配，进度直接在顶部展示
    matchViewModel: MatchViewModel = hiltViewModel(),
) {
    // onEditMatch 保留供卡片点击直接进入重新匹配页（用户要求：点击卡片直接出现重新匹配页面，
    // 删除「修改目标路径」弹窗）。
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val matchState by matchViewModel.uiState.collectAsStateWithLifecycle()
    val matchProgress = matchState.progress

    // 进入预览页时若会话 VM 还没匹配结果（matches 空）→ 启动匹配；
    // matches 非空时说明已匹配完成或从 EditMatch 回来 → 直接加载预览。
    LaunchedEffect(selectedPaths, matchType) {
        if (matches.isEmpty() &&
            selectedPaths.isNotEmpty() &&
            matchProgress is MatchViewModel.Progress.Idle
        ) {
            matchViewModel.setFiles(selectedPaths)
            matchViewModel.startMatch(matchType)
        }
    }

    // 匹配完成（Done）一次性触发：把结果写回会话 VM，预览页 viewModel.load 会自动渲染
    LaunchedEffect(matchProgress) {
        if (matchProgress is MatchViewModel.Progress.Done) {
            val combined = matchState.results + matchState.pending
            // 仅在会话 VM 还没结果时写回，避免重复覆盖 EditMatch 回写的结果
            if (matchSessionVm.matches.value.isEmpty()) {
                matchSessionVm.setMatches(combined)
            }
        }
    }

    // matches 非空时触发一次加载（VM 内 initialized 守卫避免重复）
    LaunchedEffect(matches) {
        if (matches.isNotEmpty()) viewModel.load(serverId, matches)
    }

    // EditMatch 回写后脏标记触发，用最新 matches 重新渲染预览
    val dirty by matchSessionVm.dirty.collectAsStateWithLifecycle()
    LaunchedEffect(dirty) {
        if (dirty) {
            viewModel.reload(matchSessionVm.matches.value)
            matchSessionVm.clearDirty()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val err = state.error
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    // Task 3.5：executableCount 仅统计 AUTO 项（待确认/冲突项排除在执行列表外，待确认不阻塞执行）。
    val executableCount = state.previewItems.count { it.status == PreviewViewModel.PreviewStatus.AUTO }
    // 批量编辑入口仅在批次为剧集目录时展示（多数文件为剧集类型）。
    val isEpisodeBatch = state.previewItems.isNotEmpty() &&
        state.previewItems.count { it.mediaType == MediaType.EPISODE } * 2 >= state.previewItems.size

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.defaultMinSize(minWidth = MinTouchSize, minHeight = MinTouchSize),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = "预览",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    if (isEpisodeBatch) {
                        IconButton(
                            onClick = onOpenBatchMatch,
                            modifier = Modifier.defaultMinSize(minWidth = MinTouchSize, minHeight = MinTouchSize),
                        ) {
                            Icon(Icons.Outlined.EditNote, contentDescription = "批量匹配编辑")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar(
                executableCount = executableCount,
                conflictBlocking = state.conflictCount > 0,
                onExecute = {
                    val id = viewModel.enqueueRename()
                    if (id != null) onProceedToProgress(id)
                },
            )
        },
    ) { padding ->
        // 匹配阶段：会话 VM 还没有匹配结果且 matchProgress 未到 Done → 顶部显示匹配进度条占满。
        // 匹配完成后 matchProgress == Done，matches 写回，loading 短暂为 true 后切到分类面板。
        val isMatching = matches.isEmpty() &&
            matchProgress !is MatchViewModel.Progress.Done
        if (isMatching) {
            MatchingProgressContent(
                progress = matchProgress,
                error = matchState.error,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            // Task 5.5：加载 → 内容用 crossfade(300)。
            Crossfade(
                targetState = state.loading,
                animationSpec = tween(300),
                label = "previewLoad",
            ) { loading ->
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    state.previewItems.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Movie,
                        title = "无可预览的匹配项",
                        subtitle = "请返回文件选择页选择文件后再来预览",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )

                    state.activeItems.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.FilterList,
                        title = "当前筛选下无项目",
                        subtitle = "点击顶部统计切换筛选",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )

                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        StatsSummaryRow(
                            totalCount = state.previewItems.size,
                            needsConfirmCount = state.needsConfirmCount,
                            conflictCount = state.conflictCount,
                            currentFilter = state.filter,
                            onFilter = viewModel::setFilter,
                        )
                        if (state.conflictCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = viewModel::autoResolveConflicts,
                                enabled = !state.detecting,
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PageMargin, vertical = 4.dp),
                            ) {
                                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("一键解决冲突（${state.conflictCount}）")
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = PageMargin,
                                vertical = 8.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(CardSpacing),
                        ) {
                            items(state.activeItems, key = { it.sourcePath }) { item ->
                                PreviewCard(
                                    item = item,
                                    loadCompanions = { viewModel.loadCompanions(item) },
                                    onClick = { onEditMatch(item.sourcePath) },
                                    onConfirmCandidate = { c -> viewModel.confirmPending(item.sourcePath, c) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 匹配进度内容：替代原独立 MatchScreen 的运行阶段 UI。
 *
 * - Idle：等待启动（用户刚进入预览页，匹配 VM 即将 startMatch）
 * - Running：LinearProgressIndicator + 「正在匹配 N / M」+ 当前文件名
 * - 错误：展示 matchState.error（如未配置 TMDB API Key），用户返回文件选择页处理
 *
 * 匹配完成（Done）后调用方会切换到分类面板，此 composable 不再被调用。
 */
@Composable
private fun MatchingProgressContent(
    progress: MatchViewModel.Progress,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = PageMargin, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "正在匹配",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        when (progress) {
            is MatchViewModel.Progress.Running -> {
                val total = progress.total.coerceAtLeast(1)
                val ratio = progress.current.toFloat() / total.toFloat()
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${progress.current} / ${progress.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            MatchViewModel.Progress.Idle -> {
                // 短暂等待：startMatch 即将把 progress 切到 Running。
                // 若 API Key 缺失等错误已设置，则不显示转圈，仅展示下方错误文本。
                if (error.isNullOrBlank()) {
                    CircularProgressIndicator()
                    Text(
                        text = "准备匹配…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            MatchViewModel.Progress.Done -> Unit // 调用方不会在此分支使用此 composable
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * 顶部统计行：一行小字摘要 + 状态徽章（点击切换筛选）。
 *
 * 用户要求顶部分类为「全部 / 未匹配 / 冲突」：
 * - 全部：所有项
 * - 未匹配：[PreviewViewModel.PreviewStatus.NEEDS_CONFIRM]（待确认/无匹配）
 * - 冲突：[PreviewViewModel.PreviewStatus.CONFLICT]
 *
 * 点击徽章筛选对应类别；再次点击同一徽章回退到 ALL。
 */
@Composable
private fun StatsSummaryRow(
    totalCount: Int,
    needsConfirmCount: Int,
    conflictCount: Int,
    currentFilter: PreviewViewModel.StatusFilter,
    onFilter: (PreviewViewModel.StatusFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChipPill(
            label = "全部 $totalCount",
            tint = MaterialTheme.colorScheme.primary,
            enabled = true,
            selected = currentFilter == PreviewViewModel.StatusFilter.ALL,
            onClick = { onFilter(PreviewViewModel.StatusFilter.ALL) },
        )
        FilterChipPill(
            label = "未匹配 $needsConfirmCount",
            tint = WarningAmber,
            enabled = needsConfirmCount > 0,
            selected = currentFilter == PreviewViewModel.StatusFilter.UNMATCHED,
            onClick = { onFilter(PreviewViewModel.StatusFilter.UNMATCHED) },
        )
        FilterChipPill(
            label = "冲突 $conflictCount",
            tint = ErrorRed,
            enabled = conflictCount > 0,
            selected = currentFilter == PreviewViewModel.StatusFilter.CONFLICT,
            onClick = { onFilter(PreviewViewModel.StatusFilter.CONFLICT) },
        )
    }
}

/** 单个筛选徽章：未选中为浅灰描边小药丸；选中时填充对应语义色淡背景。 */
@Composable
private fun FilterChipPill(
    label: String,
    tint: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) tint.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) tint.copy(alpha = 0.6f) else Color.Transparent
    val textColor = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
        )
    }
}

/**
 * 底部操作栏：把可执行数量并入按钮文案「执行（N）」。
 */
@Composable
private fun BottomActionBar(
    executableCount: Int,
    conflictBlocking: Boolean,
    onExecute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onExecute,
            enabled = !conflictBlocking && executableCount > 0,
            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("执行（$executableCount）")
        }
    }
}

/**
 * 预览卡片：标题行 + 上下两行重命名结构 + 伴随文件展开。
 *
 * 结构：
 * 1. 标题行：剧名 + 季/集徽章（特殊高亮） + 状态徽章
 * 2. 旧文件名（灰色小字，次要层级，单行省略）
 * 3. 新文件名（正文加粗、完整显示，允许换行；冲突标红）
 * 4. （可选）目标目录（小字灰色，置于新名之上）
 * 5. 冲突原因 / 警告
 * 6. 伴随文件展开按钮（仅当有伴随文件时）
 *
 * 交互：整卡可点击直接进入重新匹配页（EditMatch）；用户要求删除「修改目标路径」弹窗，
 * 点击卡片即跳转重新匹配。排除功能已整体移除（加入预览即参与重命名）。
 */
@Composable
private fun PreviewCard(
    item: PreviewViewModel.PreviewItem,
    loadCompanions: suspend () -> List<CompanionRename>,
    onClick: () -> Unit,
    onConfirmCandidate: (MatchCandidate) -> Unit,
) {
    val statusColor = when (item.status) {
        PreviewViewModel.PreviewStatus.AUTO -> SuccessGreen
        PreviewViewModel.PreviewStatus.NEEDS_CONFIRM -> WarningAmber
        PreviewViewModel.PreviewStatus.CONFLICT -> ErrorRed
    }
    val statusIcon = when (item.status) {
        PreviewViewModel.PreviewStatus.AUTO -> Icons.Outlined.Check
        PreviewViewModel.PreviewStatus.NEEDS_CONFIRM -> Icons.Outlined.WarningAmber
        PreviewViewModel.PreviewStatus.CONFLICT -> Icons.Outlined.Close
    }
    // 用户要求：右侧徽章显示识别到的剧集/电影类型，而非「自动」状态文案。
    // 状态（冲突/待确认）通过左侧图标 + 文件名颜色 + 冲突原因传达。
    val typeLabel = if (item.mediaType == MediaType.EPISODE) "剧集" else "电影"
    val newFileNameColor = when (item.status) {
        PreviewViewModel.PreviewStatus.CONFLICT -> ErrorRed
        PreviewViewModel.PreviewStatus.NEEDS_CONFIRM -> WarningAmber
        else -> AccentAmber
    }
    var companionsExpanded by remember { mutableStateOf(false) }
    // B3: renderItem 把 companions 硬编码为 emptyList()（避免万级文件万次请求），
    // 伴随文件改由 loadCompanions 按需发现。用户首次展开伴随文件时触发一次加载，
    // 结果缓存到 loadedCompanions；card 重组（如展开/收起候选）不会重复请求。
    var loadedCompanions by remember(item.sourcePath) { mutableStateOf<List<CompanionRename>>(item.companions) }
    LaunchedEffect(companionsExpanded, item.sourcePath) {
        if (companionsExpanded && loadedCompanions.isEmpty()) {
            loadedCompanions = loadCompanions()
        }
    }
    val companions = loadedCompanions
    var candidatesExpanded by remember { mutableStateOf(false) }
    val isPendingWithCandidates =
        item.status == PreviewViewModel.PreviewStatus.NEEDS_CONFIRM && item.candidates.isNotEmpty()

    // 标题与季/集号拆分：mediaTitle 形如「剧名 · S01E03」，拆出季集号做特殊徽章展示。
    val titleParts = splitTitleAndEpisode(item.mediaTitle)
    val fallbackTitle = item.sourcePath.substringAfterLast('/')

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(CardRadius))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        // 1. 标题行：状态图标 + 剧名 + 季/集徽章 + 状态徽章
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleParts.first.ifBlank { fallbackTitle },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 季/集号特殊徽章：品牌橙填充 + 白字 + 圆角，区别于普通状态徽章。
                    titleParts.second?.let { ep ->
                        Spacer(Modifier.width(6.dp))
                        EpisodeBadge(text = ep)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(label = typeLabel, tint = statusColor)
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))

        // 2-5. 重命名结构（待确认且有候选时不展示新旧名，改为候选展开提示）
        if (isPendingWithCandidates) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "待确认 · 点击选择候选",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarningAmber,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { candidatesExpanded = !candidatesExpanded }) {
                    Text(if (candidatesExpanded) "收起" else "选择候选")
                }
            }
        } else {
            RenameDetailBlock(
                sourceFileName = item.sourcePath.substringAfterLast('/'),
                targetDir = if (item.hasDirectory) {
                    item.targetPath.substringBeforeLast('/').ifBlank { "" }
                } else "",
                targetFileName = item.targetFileName.ifBlank { item.targetPath.substringAfterLast('/') },
                newFileNameColor = newFileNameColor,
            )
        }

        // 6. 冲突原因 / 警告
        if (item.conflictReason != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = item.conflictReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                )
            }
        }
        if (item.warnings.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = item.warnings.joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                )
            }
        }

        // 候选海报网格
        AnimatedVisibility(visible = candidatesExpanded && isPendingWithCandidates) {
            Column {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "候选（${item.candidates.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                CandidatePosterGrid(
                    candidates = item.candidates,
                    onConfirm = { c -> onConfirmCandidate(c.candidate) },
                )
            }
        }
        // 伴随文件
        AnimatedVisibility(visible = companionsExpanded && companions.isNotEmpty()) {
            Column {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "伴随文件（${companions.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                companions.forEach { CompanionRow(it) }
            }
        }

        // 伴随文件展开按钮（仅当有伴随文件时展示数量徽章）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (companions.isNotEmpty()) {
                BadgedBox(
                    badge = { Badge { Text("${companions.size}") } },
                ) {
                    IconActionButton(
                        icon = if (companionsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (companionsExpanded) "收起伴随文件" else "展开伴随文件",
                        onClick = { companionsExpanded = !companionsExpanded },
                    )
                }
            }
        }
    }
}

/**
 * 拆分 [mediaTitle] 中的剧名与季/集号。
 *
 * mediaTitle 由 [PreviewViewModel.formatMediaTitle] 生成，形如「剧名 · S01E03」或
 * 「剧名 · S01E01-E03」。用 ` · ` 分隔，右侧即为季/集号；无分隔符时返回 (全标题, null)。
 */
private fun splitTitleAndEpisode(mediaTitle: String?): Pair<String, String?> {
    if (mediaTitle.isNullOrBlank()) return "" to null
    val sep = " · "
    val idx = mediaTitle.indexOf(sep)
    return if (idx >= 0) {
        mediaTitle.substring(0, idx).trim() to mediaTitle.substring(idx + sep.length).trim().takeIf { it.isNotEmpty() }
    } else {
        mediaTitle.trim() to null
    }
}

/** 季/集号特殊徽章：品牌橙填充 + 白字 + 圆角，强调剧集定位信息。 */
@Composable
private fun EpisodeBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AccentAmber)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 上下两行重命名展示：
 * - 旧文件名（灰色小字、次要层级，单行省略）
 * - 新文件名（正文加粗、完整显示，允许换行不截断）
 * - 有目录移动时，目标目录作为小字灰色前缀置于新名上方
 */
@Composable
private fun RenameDetailBlock(
    sourceFileName: String,
    targetDir: String,
    targetFileName: String,
    newFileNameColor: Color,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = sourceFileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        if (targetDir.isNotBlank()) {
            Text(
                text = targetDir,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
        // 新文件名：完整显示、允许换行、禁止截断（预览页核心信息）
        Text(
            text = targetFileName,
            style = MaterialTheme.typography.bodyLarge,
            color = newFileNameColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 状态徽章：圆角小药丸，淡色背景 + 语义色文字。 */
@Composable
private fun StatusBadge(label: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 统一图标按钮：线性图标 + 44dp 最小触控热区。 */
@Composable
private fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .size(MinTouchSize)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * Task 3.4：候选海报墙网格（2 列），候选墙交互源自原独立匹配页（已合并入预览页）。选中候选后回调 [onConfirm]。
 */
@Composable
private fun CandidatePosterGrid(
    candidates: List<MatchViewModel.Candidate>,
    onConfirm: (MatchViewModel.Candidate) -> Unit,
) {
    val columns = 2
    candidates.chunked(columns).forEach { rowCandidates ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowCandidates.forEach { c ->
                CandidatePosterCard(
                    candidate = c,
                    onClick = { onConfirm(c) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - rowCandidates.size) {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 单张候选海报卡片：海报（2:3 圆角）+ 标题（1 行省略）+ 年份小字。 */
@Composable
private fun CandidatePosterCard(
    candidate: MatchViewModel.Candidate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(PosterPlaceholder),
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = candidate.posterUrl,
                contentDescription = candidate.candidate.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PosterPlaceholder),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PosterPlaceholder),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = candidate.candidate.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        candidate.candidate.year?.let { year ->
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CompanionRow(companion: CompanionRename) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = companion.sourcePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = companion.targetPath,
            style = MaterialTheme.typography.bodySmall,
            color = AccentAmber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

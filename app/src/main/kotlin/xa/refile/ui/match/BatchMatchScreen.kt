package xa.refile.ui.match

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import xa.refile.R
import xa.refile.core.model.MediaType
import xa.refile.ui.match.BatchMatchViewModel.SlotKey
import xa.refile.ui.match.EditMatchViewModel.EpisodeInfo
import xa.refile.ui.match.EditMatchViewModel.MediaCandidate
import xa.refile.ui.theme.AccentAmber
import xa.refile.ui.theme.ErrorRed
import xa.refile.ui.theme.WarningAmber

/** 卡片圆角 / 卡片间距 / 页面边距（与 PreviewScreen 对齐）。 */
private val CardRadius = 12.dp
private val CardSpacing = 12.dp
private val PageMargin = 16.dp

/**
 * 批量匹配编辑页（Season Board 集位槽模型）。
 *
 * 由 [xa.refile.ui.navigation.AppNavHost] 经 `batch_match` 路由进入。
 * 从 Activity 作用域 [MatchSessionViewModel.matches] 取整批次文件，载入
 * [BatchMatchViewModel]；保存后整表回写 [MatchSessionViewModel.replaceMatches] 再返回。
 *
 * UI 结构（与 EditMatchScreen 交互对齐）：
 * - 正常模式：已选剧集卡（点击进入重新选择）+ 校验状态条 + 集位槽列表
 * - 重新选择模式 / 首次选择：搜索框 + 候选列表 + 季选择器（季选择器在此视图内，不在主页面）
 *
 * 集位槽交互：点击空槽 → 弹出文件选择器选文件绑定；点击有文件的槽 → 弹出选择器选文件交换。
 * 绑定/交换逻辑由 [BatchMatchViewModel.onDropFile] 统一处理（含跨槽交换）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchMatchScreen(
    matchSessionVm: MatchSessionViewModel,
    onBack: () -> Unit,
    viewModel: BatchMatchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val files by matchSessionVm.matches.collectAsStateWithLifecycle()

    // 进入时载入整批次文件（VM 内部守卫避免重复加载）
    LaunchedEffect(files) {
        if (files.isNotEmpty()) viewModel.load(files)
    }

    // 批量保存 → 回写整表 + 返回
    LaunchedEffect(state.batchSaved) {
        val saved = state.batchSaved
        if (saved != null) {
            matchSessionVm.replaceMatches(saved)
            viewModel.consumeBatchSaved()
            onBack()
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

    // ---- 弹窗 / Sheet 状态 ----
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showApplyConfirm by remember { mutableStateOf(false) }
    /** 清空 bindings 的确认回调（selectMedia / setSeason / unbindAll 触发）。 */
    var pendingClearAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** 精确编辑 BottomSheet 对应的文件路径（从文件侧进入：改绑到其他槽）。 */
    var preciseEditFile by remember { mutableStateOf<String?>(null) }
    /** 槽位文件选择器对应的 SlotKey（从槽位侧进入：选文件绑定/交换到该槽）。 */
    var slotPickerKey by remember { mutableStateOf<SlotKey?>(null) }
    /** 重新选择模式：点击已选剧集卡后进入，搜索框 + 季选择器在此视图内展示。
     *  与 EditMatchScreen 交互对齐：点击已匹配剧集卡 → 重新选择（含季）；非按钮触发。 */
    var reselectMode by rememberSaveable { mutableStateOf(false) }

    // 返回拦截：重新选择模式优先退出重新选择；否则 dirty 时弹放弃确认。
    BackHandler(enabled = reselectMode || (state.dirty && !state.loading)) {
        if (reselectMode) {
            reselectMode = false
        } else {
            showDiscardConfirm = true
        }
    }

    // 应用按钮启用条件：只要没有未绑定文件即可执行重命名（不再要求 dirty / 无重复）。
    // 重复槽位会由预览页冲突检测兜底，这里不阻断。
    val canApply = !state.loading &&
        state.files.isNotEmpty() &&
        state.unboundFiles.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (reselectMode) {
                            reselectMode = false
                        } else if (state.dirty && !state.loading) {
                            showDiscardConfirm = true
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                // 标题：「批量匹配 · N」N 为已绑定文件数
                title = { Text(stringResource(R.string.batch_match_title, state.boundCount)) },
                actions = {
                    IconButton(
                        onClick = { showApplyConfirm = true },
                        enabled = canApply,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.batch_match_apply))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BatchBottomBar(
                hasEpisodeList = state.episodeList.isNotEmpty(),
                loading = state.loading,
                canApply = canApply,
                onSmartAssign = viewModel::smartAssignFromParsed,
                onFillSequential = viewModel::fillSequential,
                onApply = { showApplyConfirm = true },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                val selectedMedia = state.selectedMedia
                val numberOfSeasons = state.numberOfSeasons

                if (selectedMedia != null && !reselectMode) {
                    // ---- 正常模式：已选剧集卡（固定）+ 季选择器 + 校验状态条 + 集位槽（滚动）----
                    SelectedMediaSummary(
                        media = selectedMedia,
                        seasonNumber = state.seasonNumber,
                        onClick = { reselectMode = true },
                    )
                    // 季选择器移至外页：用户无需进入重新选择模式即可切季。
                    if (numberOfSeasons != null && selectedMedia.mediaType == MediaType.EPISODE) {
                        SeasonSelectorRow(
                            seasonNumber = state.seasonNumber,
                            numberOfSeasons = numberOfSeasons,
                            onSetSeason = { s ->
                                if (state.bindings.isNotEmpty()) {
                                    pendingClearAction = { viewModel.setSeason(s) }
                                } else {
                                    viewModel.setSeason(s)
                                }
                            },
                        )
                    }
                    ValidationStatusBar(state = state)

                    if (state.episodeList.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.batch_match_wait_episodes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        SlotBoard(
                            modifier = Modifier.weight(1f),
                            state = state,
                            onSlotClick = { key -> slotPickerKey = key },
                            onFileClick = { fp -> preciseEditFile = fp },
                        )
                    }
                } else {
                    // ---- 重新选择模式 / 首次选择：搜索 + 候选 ----
                    MediaReselectSection(
                        modifier = Modifier.weight(1f),
                        state = state,
                        onSearch = viewModel::searchMedia,
                        onSelect = { c ->
                            if (state.bindings.isNotEmpty()) {
                                pendingClearAction = {
                                    viewModel.selectMedia(c)
                                    reselectMode = false
                                }
                            } else {
                                viewModel.selectMedia(c)
                                reselectMode = false
                            }
                        },
                    )
                }
            }

            // 顶部加载进度条
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // ---- 弹窗 ----

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.batch_match_discard_title)) },
            text = { Text(stringResource(R.string.batch_match_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onBack()
                }) { Text(stringResource(R.string.batch_match_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.batch_match_continue_edit)) }
            },
        )
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text(stringResource(R.string.batch_match_apply_title)) },
            text = { Text(state.summaryText) },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    viewModel.batchApply()
                }) { Text(stringResource(R.string.batch_match_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    pendingClearAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingClearAction = null },
            title = { Text(stringResource(R.string.batch_match_clear_bindings_title)) },
            text = { Text(stringResource(R.string.batch_match_clear_bindings_text)) },
            confirmButton = {
                TextButton(onClick = {
                    action()
                    pendingClearAction = null
                }) { Text(stringResource(R.string.batch_match_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAction = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 从文件侧进入：精确编辑 BottomSheet（改文件绑到哪个槽）
    preciseEditFile?.let { fp ->
        PreciseEditSheet(
            filePath = fp,
            state = state,
            onDismiss = { preciseEditFile = null },
            onPick = { slot ->
                viewModel.setBinding(fp, slot)
                preciseEditFile = null
            },
        )
    }

    // 从槽位侧进入：文件选择器 BottomSheet（选文件绑到该槽，含交换）
    slotPickerKey?.let { key ->
        SlotFilePickerSheet(
            slotKey = key,
            state = state,
            onDismiss = { slotPickerKey = null },
            onPickFile = { fp ->
                viewModel.onDropFile(fp, key)
                slotPickerKey = null
            },
            onUnbind = {
                viewModel.onDropFile(
                    // 找到当前槽位的文件解绑
                    filePath = state.bindings.entries.firstOrNull { it.value == key }?.key ?: "",
                    targetSlot = null,
                )
                slotPickerKey = null
            },
        )
    }
}

// ---- 1. 已选剧集卡 + 重新选择视图 ----

/**
 * 已选剧集摘要卡（正常模式）：点击进入重新选择视图（搜索 + 季选择器）。
 * 与 EditMatchScreen 的 [SelectedMediaSummary] 交互对齐：点击卡 → 重新选择。
 * 含剧集简介（overview），与单集编辑页保持一致。
 */
@Composable
private fun SelectedMediaSummary(
    media: MediaCandidate,
    seasonNumber: Int?,
    onClick: () -> Unit,
) {
    val typeLabel = if (media.mediaType == MediaType.EPISODE) stringResource(R.string.match_type_tv) else stringResource(R.string.match_type_movie)
    val seasonLabel = if (seasonNumber == null) stringResource(R.string.common_season_all) else stringResource(R.string.common_season_n, seasonNumber)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin, vertical = 4.dp)
            .clip(RoundedCornerShape(CardRadius))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(CardRadius),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PosterThumb(posterUrl = media.posterUrl, sizeW = 56.dp, sizeH = 84.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = if (media.year != null) "${media.name} (${media.year})" else media.name
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val metaLabel = buildString {
                    append(typeLabel)
                    if (media.mediaType == MediaType.EPISODE) {
                        append(" · ")
                        append(seasonLabel)
                    }
                }
                Text(
                    metaLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                media.overview?.takeIf { it.isNotBlank() }?.let { ov ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ov,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 重新选择视图：搜索框 + 候选列表。
 *
 * 季选择器已移至主页面（正常模式下 SelectedMediaSummary 下方），此处仅负责
 * 重新搜索/选择剧集。用户选了新候选即退出；点返回按钮（由外层 BackHandler 拦截）退出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaReselectSection(
    modifier: Modifier = Modifier,
    state: BatchMatchViewModel.UiState,
    onSearch: (String) -> Unit,
    onSelect: (MediaCandidate) -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = PageMargin, vertical = 8.dp)) {
        // 搜索框
        OutlinedTextField(
            value = state.mediaSearchQuery,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.batch_match_search_hint)) },
            singleLine = true,
            leadingIcon = {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                }
            },
        )
        if (state.mediaSearchResults.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.batch_match_search_results, state.mediaSearchResults.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.mediaSearchResults, key = { it.tmdbId }) { c ->
                    CandidateRow(candidate = c, onClick = { onSelect(c) })
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: MediaCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PosterThumb(posterUrl = candidate.posterUrl, sizeW = 48.dp, sizeH = 72.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            val title = if (candidate.year != null) "${candidate.name} (${candidate.year})" else candidate.name
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (candidate.mediaType == MediaType.EPISODE) stringResource(R.string.match_type_tv) else stringResource(R.string.match_type_movie),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            candidate.overview?.takeIf { it.isNotBlank() }?.let { ov ->
                Spacer(Modifier.height(4.dp))
                Text(
                    ov,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- 2. 季选择器 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonSelectorRow(
    seasonNumber: Int?,
    numberOfSeasons: Int,
    onSetSeason: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val allSeasonsLabel = stringResource(R.string.common_season_all)
    // 选项列表：「全部季」+ 1..numberOfSeasons。
    val options = buildList {
        add(null to allSeasonsLabel)
        for (s in 1..numberOfSeasons) add(s to stringResource(R.string.common_season_n, s))
    }
    val currentLabel = if (seasonNumber == null) allSeasonsLabel else stringResource(R.string.common_season_n, seasonNumber)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.common_season_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSetSeason(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ---- 3. 批量工具条（已合并到底部栏 BatchBottomBar）----

// ---- 4. 校验状态条 ----

@Composable
private fun ValidationStatusBar(state: BatchMatchViewModel.UiState) {
    val duplicateSlotsMsg = stringResource(
        R.string.batch_match_duplicate_slots,
        state.duplicates.joinToString { "S${it.season}E${it.episode}" },
    )
    val unsavedChangesMsg = stringResource(R.string.batch_match_unsaved_changes)
    val messages = buildList {
        if (state.duplicates.isNotEmpty()) {
            add(ErrorRed to duplicateSlotsMsg)
        }
        // 空槽数量不再展示（按需求移除）
        if (state.dirty) {
            add(AccentAmber to unsavedChangesMsg)
        }
    }
    if (messages.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        messages.forEach { (color, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

// ---- 5/6. 集位槽 + 未绑定区 ----

@Composable
private fun SlotBoard(
    state: BatchMatchViewModel.UiState,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
    onSlotClick: (SlotKey) -> Unit,
    onFileClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = PageMargin,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        header?.invoke(this)
        // B: LazyColumn key 必须是可存入 Bundle 的类型（String/Int/Long 等），
        // SlotKey 是自定义 data class 不能存入 Bundle，会抛 IllegalArgumentException 闪退。
        items(state.slots, key = { "slot_${it.slotKey.season}_${it.slotKey.episode}" }) { row ->
            SlotCard(
                row = row,
                isAllSeasonsMode = state.seasonNumber == null,
                isDuplicate = row.slotKey in state.duplicates,
                onSlotClick = { onSlotClick(row.slotKey) },
                onFileClick = onFileClick,
            )
        }
        item(key = "unbound") {
            UnboundFilesArea(
                files = state.unboundFiles,
                onFileClick = onFileClick,
            )
        }
    }
}

@Composable
private fun SlotCard(
    row: BatchMatchViewModel.SlotRow,
    isAllSeasonsMode: Boolean,
    isDuplicate: Boolean,
    onSlotClick: () -> Unit,
    onFileClick: (String) -> Unit,
) {
    val borderColor = if (isDuplicate) ErrorRed else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isDuplicate) 2.dp else 1.dp
    // 槽位标签：全部季模式显示 S01E05，单季模式显示 E05
    val slotLabel = if (isAllSeasonsMode) {
        "S${"%02d".format(row.episode.seasonNumber)}E${"%02d".format(row.episode.episodeNumber)}"
    } else {
        "E${"%02d".format(row.episode.episodeNumber)}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surface)
            .border(borderWidth, borderColor, RoundedCornerShape(CardRadius))
            .clickable(onClick = onSlotClick)
            .padding(12.dp),
    ) {
        // 集位标题行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slotLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDuplicate) ErrorRed else AccentAmber,
                modifier = Modifier.width(if (isAllSeasonsMode) 84.dp else 56.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.episode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.episode.airDate?.takeIf { it.isNotBlank() }?.let { d ->
                    Text(
                        text = d,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        // 绑定的文件列表（文件名加粗突出）
        if (row.files.isEmpty()) {
            Text(
                text = stringResource(R.string.batch_match_empty_slot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            row.files.forEach { fm ->
                FileChip(
                    filePath = fm.filePath,
                    onClick = { onFileClick(fm.filePath) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun UnboundFilesArea(
    files: List<MatchViewModel.FileMatch>,
    onFileClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(CardRadius))
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.batch_match_unbound, files.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (files.isEmpty()) {
            Text(
                text = stringResource(R.string.batch_match_all_bound),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            files.forEach { fm ->
                FileChip(
                    filePath = fm.filePath,
                    onClick = { onFileClick(fm.filePath) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * 文件卡：点击进入精确编辑 Sheet（改文件绑到哪个槽）。文件名加粗显示。
 */
@Composable
private fun FileChip(
    filePath: String,
    onClick: () -> Unit,
) {
    val fileName = filePath.substringAfterLast('/')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---- 精确编辑 BottomSheet（从文件侧进入：改文件绑到哪个槽） ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreciseEditSheet(
    filePath: String,
    state: BatchMatchViewModel.UiState,
    onDismiss: () -> Unit,
    onPick: (SlotKey?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = PageMargin, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.batch_match_select_slot),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = filePath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    OutlinedButton(
                        onClick = { onPick(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.batch_match_unbind))
                    }
                }
                // B: LazyColumn key 必须是可存入 Bundle 的类型，不能用 SlotKey data class。
                items(state.episodeList, key = { "ep_${it.seasonNumber}_${it.episodeNumber}" }) { ep ->
                    val key = SlotKey(ep.seasonNumber, ep.episodeNumber)
                    EpisodePickRow(
                        episode = ep,
                        isAllSeasonsMode = state.seasonNumber == null,
                        isBound = state.bindings[filePath] == key,
                        onClick = { onPick(key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodePickRow(
    episode: EpisodeInfo,
    isAllSeasonsMode: Boolean,
    isBound: Boolean,
    onClick: () -> Unit,
) {
    val slotLabel = if (isAllSeasonsMode) {
        "S${"%02d".format(episode.seasonNumber)}E${"%02d".format(episode.episodeNumber)}"
    } else {
        "E${"%02d".format(episode.episodeNumber)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isBound) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBound) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = slotLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(if (isAllSeasonsMode) 84.dp else 48.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.airDate?.takeIf { it.isNotBlank() }?.let { d ->
                Text(
                    text = d,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- 槽位文件选择器 BottomSheet（从槽位侧进入：选文件绑定/交换到该槽） ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotFilePickerSheet(
    slotKey: SlotKey,
    state: BatchMatchViewModel.UiState,
    onDismiss: () -> Unit,
    onPickFile: (String) -> Unit,
    onUnbind: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val slotLabel = "S${"%02d".format(slotKey.season)}E${"%02d".format(slotKey.episode)}"
    val currentFile = state.bindings.entries.firstOrNull { it.value == slotKey }?.key
    val epInfo = state.episodeList.firstOrNull {
        SlotKey(it.seasonNumber, it.episodeNumber) == slotKey
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = PageMargin, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.batch_match_select_file, slotLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            epInfo?.let { ep ->
                Text(
                    text = ep.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (currentFile != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.batch_match_current, currentFile.substringAfterLast('/')),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (currentFile != null) {
                    item {
                        OutlinedButton(
                            onClick = onUnbind,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.batch_match_unbind_current))
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                // 未绑定文件优先列出（点击即绑定到当前槽）
                val unbound = state.files.filter { it.filePath !in state.bindings }
                if (unbound.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.batch_match_unbound_files),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(unbound, key = { it.filePath }) { fm ->
                        PickerFileRow(
                            filePath = fm.filePath,
                            slotLabel = null,
                            onClick = { onPickFile(fm.filePath) },
                        )
                    }
                }
                // 已绑定到其他槽的文件（点击触发交换）
                val bound = state.files.filter { it.filePath in state.bindings && it.filePath != currentFile }
                if (bound.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.batch_match_bound_files),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(bound, key = { it.filePath }) { fm ->
                        val bKey = state.bindings[fm.filePath]
                        val bLabel = bKey?.let {
                            "S${"%02d".format(it.season)}E${"%02d".format(it.episode)}"
                        }
                        PickerFileRow(
                            filePath = fm.filePath,
                            slotLabel = bLabel,
                            onClick = { onPickFile(fm.filePath) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerFileRow(
    filePath: String,
    slotLabel: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = filePath.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        slotLabel?.let { l ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = l,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---- 底部操作栏 ----

@Composable
private fun BatchBottomBar(
    hasEpisodeList: Boolean,
    loading: Boolean,
    canApply: Boolean,
    onSmartAssign: () -> Unit,
    onFillSequential: () -> Unit,
    onApply: () -> Unit,
) {
    // 智能 / 顺序 两按钮 + 应用按钮。文字单行展示，避免换行。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onSmartAssign,
            enabled = hasEpisodeList && !loading,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.batch_match_smart), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        FilledTonalButton(
            onClick = onFillSequential,
            enabled = hasEpisodeList && !loading,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.batch_match_sequential), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        Button(
            onClick = onApply,
            enabled = canApply,
            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.batch_match_apply), maxLines = 1)
        }
    }
}

// ---- 通用 ----

@Composable
private fun PosterThumb(
    posterUrl: String?,
    sizeW: androidx.compose.ui.unit.Dp,
    sizeH: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(width = sizeW, height = sizeH)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

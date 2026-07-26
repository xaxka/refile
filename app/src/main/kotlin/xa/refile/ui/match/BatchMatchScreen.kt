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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
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

    // dirty 时拦截返回，弹放弃确认
    BackHandler(enabled = state.dirty && !state.loading) {
        showDiscardConfirm = true
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
                        if (state.dirty && !state.loading) showDiscardConfirm = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 标题后追加「应用 N 个文件」（原底部栏摘要文案上移简化）
                title = { Text("批量匹配编辑 · 应用 ${state.boundCount} 个文件") },
                actions = {
                    IconButton(
                        onClick = { showApplyConfirm = true },
                        enabled = canApply,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "应用")
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
                onUnbindAll = {
                    if (state.bindings.isNotEmpty()) {
                        pendingClearAction = { viewModel.unbindAll() }
                    }
                },
                onApply = { showApplyConfirm = true },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                val selectedMedia = state.selectedMedia
                val numberOfSeasons = state.numberOfSeasons

                if (selectedMedia != null && !reselectMode) {
                    // ---- 正常模式：已选剧集卡（点击重新选择）+ 集位槽 ----
                    SelectedMediaSummary(
                        media = selectedMedia,
                        seasonNumber = state.seasonNumber,
                        onClick = { reselectMode = true },
                    )
                    ValidationStatusBar(state = state)

                    if (state.episodeList.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "请等待集列表加载…",
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
                    // ---- 重新选择模式 / 首次选择：搜索 + 候选 + 季选择器 ----
                    MediaReselectSection(
                        modifier = Modifier.weight(1f),
                        state = state,
                        numberOfSeasons = numberOfSeasons,
                        showReselectClose = selectedMedia != null,
                        onCloseReselect = { reselectMode = false },
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
                        onSetSeason = { s ->
                            if (state.bindings.isNotEmpty()) {
                                pendingClearAction = { viewModel.setSeason(s) }
                            } else {
                                viewModel.setSeason(s)
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
            title = { Text("放弃修改？") },
            text = { Text("当前有未应用的修改，确认放弃并返回？") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onBack()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("继续编辑") }
            },
        )
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text("应用批量修改") },
            text = { Text(state.summaryText) },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    viewModel.batchApply()
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text("取消") }
            },
        )
    }

    pendingClearAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingClearAction = null },
            title = { Text("清空全部绑定？") },
            text = { Text("此操作将清空当前所有集位绑定，确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    action()
                    pendingClearAction = null
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAction = null }) { Text("取消") }
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
 */
@Composable
private fun SelectedMediaSummary(
    media: MediaCandidate,
    seasonNumber: Int?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin, vertical = 8.dp)
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
                    append(if (media.mediaType == MediaType.EPISODE) "剧集" else "电影")
                    if (media.mediaType == MediaType.EPISODE) {
                        append(" · ")
                        append(if (seasonNumber == null) "全部季" else "第 $seasonNumber 季")
                    }
                }
                Text(
                    metaLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "点击重新选择",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 重新选择视图：搜索框 + 候选列表 + 季选择器（含「取消」关闭按钮）。
 *
 * 与 EditMatchScreen 的 MediaSearchSection 交互对齐：季选择器在此视图内，
 * 不在主页面。用户选了新候选 / 点「取消」即退出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaReselectSection(
    modifier: Modifier = Modifier,
    state: BatchMatchViewModel.UiState,
    numberOfSeasons: Int?,
    showReselectClose: Boolean,
    onCloseReselect: () -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (MediaCandidate) -> Unit,
    onSetSeason: (Int?) -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = PageMargin, vertical = 8.dp)) {
        // 顶部：标题 + 关闭按钮（仅已选 media 重新选择时展示）
        if (showReselectClose) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "重新选择",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onCloseReselect) { Text("取消") }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 季选择器（已知总季数时展示，移入重新选择视图与搜索同区）
        if (numberOfSeasons != null) {
            SeasonSelectorRow(
                seasonNumber = state.seasonNumber,
                numberOfSeasons = numberOfSeasons,
                onSetSeason = onSetSeason,
            )
            Spacer(Modifier.height(8.dp))
        }

        // 搜索框
        OutlinedTextField(
            value = state.mediaSearchQuery,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索剧集标题") },
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
                text = "搜索结果 (${state.mediaSearchResults.size})",
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
                if (candidate.mediaType == MediaType.EPISODE) "剧集" else "电影",
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
    // 选项列表：「全部季」+ 1..numberOfSeasons。
    val options = remember(numberOfSeasons) {
        buildList {
            add(null to "全部季")
            for (s in 1..numberOfSeasons) add(s to "第 $s 季")
        }
    }
    val currentLabel = if (seasonNumber == null) "全部季" else "第 $seasonNumber 季"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("季") },
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
    val messages = buildList {
        if (state.duplicates.isNotEmpty()) {
            add(ErrorRed to "重复槽位：${state.duplicates.joinToString { "S${it.season}E${it.episode}" }}")
        }
        // 空槽数量不再展示（按需求移除）
        if (state.dirty) {
            add(AccentAmber to "有未应用的修改")
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
                text = "（空槽，点击选择文件绑定）",
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
            text = "未绑定（${files.size}）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (files.isEmpty()) {
            Text(
                text = "所有文件已绑定",
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
                text = "选择槽位绑定",
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
                        Text("解绑")
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
                text = "选择文件 → $slotLabel",
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
                    text = "当前：${currentFile.substringAfterLast('/')}",
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
                            Text("解绑当前文件")
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                // 未绑定文件优先列出（点击即绑定到当前槽）
                val unbound = state.files.filter { it.filePath !in state.bindings }
                if (unbound.isNotEmpty()) {
                    item {
                        Text(
                            text = "未绑定文件",
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
                            text = "已绑定文件（点击交换）",
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
    onUnbindAll: () -> Unit,
    onApply: () -> Unit,
) {
    // 智能 / 顺序 / 解绑 三按钮原位于批量工具条，现按需求移到底部栏「应用」按钮左侧。
    // 摘要文案已上移到 TopAppBar 标题，这里不再展示。
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
        ) { Text("智能", style = MaterialTheme.typography.labelMedium) }
        FilledTonalButton(
            onClick = onFillSequential,
            enabled = hasEpisodeList && !loading,
            modifier = Modifier.weight(1f),
        ) { Text("顺序", style = MaterialTheme.typography.labelMedium) }
        FilledTonalButton(
            onClick = onUnbindAll,
            enabled = hasEpisodeList && !loading,
            modifier = Modifier.weight(1f),
        ) { Text("解绑", style = MaterialTheme.typography.labelMedium) }
        Button(
            onClick = onApply,
            enabled = canApply,
            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("应用")
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

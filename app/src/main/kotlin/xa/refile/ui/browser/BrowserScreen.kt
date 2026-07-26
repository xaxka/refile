package xa.refile.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xa.refile.core.webdav.MediaFileTypes
import xa.refile.core.webdav.WebDavEntry
import xa.refile.ui.common.EmptyState
import xa.refile.ui.match.MatchViewModel
import xa.refile.ui.theme.AccentAmber

/** 目录图标用主强调色（蓝）。 */
private val DirAccentColor = AccentAmber
/** 匹配方式卡片强调色：自动=主色蓝 / 电影=靛蓝 / 剧集=青。 */
private val MatchAutoColor = AccentAmber
private val MatchMovieColor = Color(0xFF5C6BC0)
private val MatchTvColor = Color(0xFF26A69A)

/**
 * WebDAV 文件浏览器（计划 §M1 SubTask 1.5）。
 *
 * - 顶部 TopAppBar：返回 + 可点击面包屑 + 刷新/排序菜单。
 * - 列表：每行图标（目录/视频/字幕/其它）+ 名称 + 大小 + 修改日期；iso 仅显示并置灰。
 * - 选择规则：所有类型都显示；「视频文件 + 目录」可勾选（多选模式显示复选框）；
 *   字幕/nfo/图片/iso 置灰、无复选框。非多选模式目录点击进入子目录。
 * - 多选：长按视频或目录进入；底栏显示计数 + 全选/反选 + 「匹配」。
 *   下一步时递归展开选中目录为视频文件路径，再进入匹配流程（保持 匹配→预览→重命名 不变）。
 * - 空目录居中提示；加载中转圈。
 * - 系统返回键：多选先退出，否则逐级回退，根目录回退到上一屏。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    serverId: Long,
    onBack: () -> Unit,
    onProceedToPreview: (serverId: Long, selectedPaths: List<String>, matchType: MatchViewModel.MatchType) -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSortMenu by remember { mutableStateOf(false) }
    /** 「匹配」按钮上方 3 选 1 浮层（自动/电影/剧集）展开状态。
     *  点击「匹配」按钮先弹出此浮层，用户选定类型后再递归展开目录并跳转预览页。 */
    var showMatchTypePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverId) { viewModel.init(serverId) }

    BackHandler {
        if (state.multiSelectMode) viewModel.exitMultiSelect()
        else if (!viewModel.goUp()) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        breadcrumbs(state.currentPath, state.rootPath).forEachIndexed { index, (label, path) ->
                            if (index > 0) {
                                Text(
                                    text = " / ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.navigateTo(path) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        val sf = state.sortField
                        DropdownMenuItem(
                            text = { Text("按名称${if (sf == BrowserViewModel.SortField.NAME) " ✓" else ""}") },
                            onClick = {
                                viewModel.toggleSort(BrowserViewModel.SortField.NAME)
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("按大小${if (sf == BrowserViewModel.SortField.SIZE) " ✓" else ""}") },
                            onClick = {
                                viewModel.toggleSort(BrowserViewModel.SortField.SIZE)
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("按时间${if (sf == BrowserViewModel.SortField.TIME) " ✓" else ""}") },
                            onClick = {
                                viewModel.toggleSort(BrowserViewModel.SortField.TIME)
                                showSortMenu = false
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (state.sortAsc) "切换为降序" else "切换为升序") },
                            onClick = {
                                viewModel.toggleSortOrder()
                                showSortMenu = false
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.multiSelectMode) {
                MultiSelectBottomBar(
                    selectedCount = state.selectedPaths.size,
                    expanding = state.expanding,
                    onSelectAll = { viewModel.selectAll() },
                    onInvert = { viewModel.invertSelection() },
                    onExit = { viewModel.exitMultiSelect() },
                    // 点击「匹配」先弹出底部 sheet 选择匹配类型（自动/电影/剧集）
                    onProceed = { showMatchTypePicker = true },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && state.entries.isEmpty() && state.error == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null && state.entries.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { viewModel.refresh() }) { Text("重试") }
                        }
                    }
                }
                state.entries.isEmpty() -> {
                    // Task 5.5：空目录友好空状态。
                    EmptyState(
                        icon = Icons.Default.Folder,
                        title = "空文件夹",
                        subtitle = "此目录没有文件",
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.href }) { entry ->
                            val name = entry.displayName ?: nameFromHref(entry.href)
                            val fullPath = joinPath(state.currentPath, name)
                            BrowserEntryRow(
                                modifier = Modifier.animateItemPlacement(),
                                entry = entry,
                                name = name,
                                multiSelectMode = state.multiSelectMode,
                                isSelected = fullPath in state.selectedPaths,
                                onClick = {
                                    if (state.multiSelectMode) {
                                        // 多选模式：目录和视频都 toggle 选中。
                                        viewModel.toggleSelected(fullPath, entry.isCollection)
                                    } else if (entry.isCollection) {
                                        viewModel.navigateInto(entry)
                                    }
                                    // 非多选模式视频点击无动作（保持原行为）。
                                },
                                onLongClick = {
                                    if (!state.multiSelectMode &&
                                        (entry.isCollection || MediaFileTypes.isSelectableVideo(name))
                                    ) {
                                        viewModel.enterMultiSelect(fullPath, entry.isCollection)
                                    }
                                },
                                onToggle = { viewModel.toggleSelected(fullPath, entry.isCollection) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            // 底部 sheet 选择匹配方式（自动/电影/剧集）
            // 选中类型后递归展开选中目录为视频文件并跳转预览页（预览页内启动匹配）
            if (showMatchTypePicker) {
                MatchTypePickerSheet(
                    expanding = state.expanding,
                    onSelect = { type ->
                        showMatchTypePicker = false
                        scope.launch {
                            val files = viewModel.expandSelectionToFiles()
                            if (files.isEmpty()) return@launch
                            onProceedToPreview(serverId, files, type)
                        }
                    },
                    onDismiss = { showMatchTypePicker = false },
                )
            }
        }
    }
}

/** 多选模式底部栏：退出 + 已选计数 + 全选/反选 + 匹配（仅当选中>0）。
 * [expanding] 为 true 时表示正在递归展开目录为视频文件，按钮禁用并显示「展开中...」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectBottomBar(
    selectedCount: Int,
    expanding: Boolean,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onExit: () -> Unit,
    onProceed: () -> Unit,
) {
    BottomAppBar {
        IconButton(onClick = onExit) {
            Icon(Icons.Default.Close, contentDescription = "退出多选")
        }
        Text(
            text = "已选 $selectedCount",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSelectAll) { Text("全选") }
        TextButton(onClick = onInvert) { Text("反选") }
        if (selectedCount > 0) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onProceed, enabled = !expanding) {
                Text(if (expanding) "展开中..." else "匹配")
            }
        }
    }
}

/**
 * 匹配方式选择底部 sheet：自动 / 电影 / 剧集。
 *
 * 用户在文件选择页点击「匹配」按钮后弹出此 sheet，选定匹配类型后才递归展开目录并跳转预览页
 * （匹配过程在预览页顶部进度条内执行）。展开期间 [expanding] 为 true 时禁用所有选项并显示进度。
 *
 * 设计：底部 sheet + 大图标卡片，每个选项一张卡，水平排列，
 * 图标置于着色圆形背景内，配标题与一行说明，圆角浮起带阴影，文字精简强对比。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchTypePickerSheet(
    expanding: Boolean,
    onSelect: (MatchViewModel.MatchType) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题区：大标题 + 副标题，强对比层级
            Text(
                text = "选择匹配方式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "选定后开始识别并整理所选文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            // 3 张大图标卡片，水平等分排列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MatchTypeCard(
                    icon = Icons.Default.AutoAwesome,
                    title = "自动",
                    description = "智能识别类型",
                    accent = MatchAutoColor,
                    enabled = !expanding,
                    onClick = { onSelect(MatchViewModel.MatchType.AUTO) },
                    modifier = Modifier.weight(1f),
                )
                MatchTypeCard(
                    icon = Icons.Default.Movie,
                    title = "电影",
                    description = "单部电影文件",
                    accent = MatchMovieColor,
                    enabled = !expanding,
                    onClick = { onSelect(MatchViewModel.MatchType.MOVIE) },
                    modifier = Modifier.weight(1f),
                )
                MatchTypeCard(
                    icon = Icons.Default.Tv,
                    title = "剧集",
                    description = "电视剧与番剧",
                    accent = MatchTvColor,
                    enabled = !expanding,
                    onClick = { onSelect(MatchViewModel.MatchType.TV) },
                    modifier = Modifier.weight(1f),
                )
            }

            // 展开中：显示进度条与说明，禁用所有选项
            if (expanding) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在展开目录…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 匹配方式卡片：大图标置于着色圆形背景内，下方标题 + 一行说明，
 * 圆角浮起带阴影，整卡可点击。
 */
@Composable
private fun MatchTypeCard(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 着色圆形图标背景，强对比
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** 单条浏览器项。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserEntryRow(
    entry: WebDavEntry,
    name: String,
    multiSelectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelectableVideo = MediaFileTypes.isSelectableVideo(name)
    val isVideo = MediaFileTypes.isVideo(name)
    val isSubtitle = MediaFileTypes.isSubtitle(name)
    val isDisplayOnly = MediaFileTypes.isDisplayOnly(name)

    val icon = when {
        entry.isCollection -> Icons.Default.Folder
        isVideo -> Icons.Default.Movie
        isSubtitle -> Icons.Default.Subtitles
        else -> Icons.Default.InsertDriveFile
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = when {
        entry.isCollection && !multiSelectMode -> DirAccentColor
        multiSelectMode && !isSelectableVideo && !entry.isCollection -> onSurfaceVariant
        isDisplayOnly -> onSurfaceVariant
        else -> onSurface
    }
    val nameColor = when {
        multiSelectMode && !isSelectableVideo && !entry.isCollection -> onSurfaceVariant
        isDisplayOnly -> onSurfaceVariant
        else -> onSurface
    }
    // Task 5.5：多选选中行加 primaryContainer 半透明高亮（卡片态）。
    val rowBackground = if (isSelected && multiSelectMode) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧固定 48dp 选择/图标区：多选模式下「可选视频 + 目录」显示 Checkbox，其余显示 Icon。
        // 固定宽度，避免进入/退出多选时整行内容横向位移（用户反馈"长按选择尺寸改变"）。
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (multiSelectMode && (isSelectableVideo || entry.isCollection)) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = nameColor,
                fontWeight = if (isSelectableVideo) FontWeight.Medium else FontWeight.Normal,
                // 测试反馈 Item 6：文件名太长时换行完整显示，不截断
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatSize(entry.contentLength),
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                )
                entry.lastModified?.let { lm ->
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = formatDate(lm),
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 人性化字节大小，如 `1.2 GB`。null 或目录返回 `—`。 */
private fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "—"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var idx = 0
    while (value >= 1024.0 && idx < units.size - 1) {
        value /= 1024.0
        idx++
    }
    return "%.1f %s".format(value, units[idx])
}

/** 截取 RFC1123 修改时间的日期部分（`dd MMM yyyy`）；格式不符时回退到首段。 */
private fun formatDate(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val parts = raw.split(" ").filter { it.isNotBlank() }
    return if (parts.size >= 4 && parts[0].endsWith(",")) {
        "${parts[1]} ${parts[2]} ${parts[3]}"
    } else {
        raw.substringBefore(" ")
    }
}

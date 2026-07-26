package xa.refile.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import xa.refile.core.model.MediaType
import xa.refile.ui.match.EditMatchViewModel.MediaCandidate

/**
 * Edit Match 页（Task 2.5.1–2.5.3，单条手动修正）。
 *
 * 由 [xa.refile.ui.navigation.AppNavHost] 经 `edit_match/{matchIndex}` 路由进入。
 * 从 Activity 作用域 [MatchSessionViewModel.matches] 取索引对应文件，载入
 * [EditMatchViewModel]；保存后单条回写 [MatchSessionViewModel.updateMatch] 再返回。
 *
 * 批量编辑请使用 [BatchMatchScreen]（集位槽模型，从预览页入口进入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMatchScreen(
    matchIndex: Int,
    matchSessionVm: MatchSessionViewModel,
    onBack: () -> Unit,
    viewModel: EditMatchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val files by matchSessionVm.matches.collectAsStateWithLifecycle()

    // 进入时载入待编辑文件
    // B29: 移除 `&& viewModel.currentMatch.value == null` 守卫——VM 复用时 currentMatch
    // 不为 null 会导致新文件不被加载。load() 内部已有 filePath 守卫避免重复加载同文件。
    LaunchedEffect(files, matchIndex) {
        val current = files.getOrNull(matchIndex)
        if (current != null) {
            viewModel.load(current)
        }
    }

    // 单条保存 → 回写 + 返回
    LaunchedEffect(state.saved) {
        val saved = state.saved
        if (saved != null) {
            matchSessionVm.updateMatch(matchIndex, saved)
            viewModel.consumeSaved()
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancel()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                },
                title = { Text("编辑匹配") },
                actions = {
                    IconButton(
                        onClick = viewModel::applyEdit,
                        enabled = !state.loading,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val current = state.currentMatch
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "无可编辑条目（索引 $matchIndex 越界）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SingleMultiView(
                    state = state,
                    onSwitchType = viewModel::switchMediaType,
                    onSearchMedia = viewModel::searchMedia,
                    onSelectMedia = viewModel::selectMedia,
                    onClearSelectedMedia = viewModel::clearSelectedMedia,
                    onSetSeason = viewModel::setSeason,
                    onToggleEpisode = viewModel::toggleEpisode,
                )
            }
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleMultiView(
    state: EditMatchViewModel.UiState,
    onSwitchType: (MediaType) -> Unit,
    onSearchMedia: (String) -> Unit,
    onSelectMedia: (MediaCandidate) -> Unit,
    onClearSelectedMedia: () -> Unit,
    onSetSeason: (Int?) -> Unit,
    onToggleEpisode: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // 类型切换：电影 / 剧集
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.mediaType == MediaType.MOVIE,
                onClick = { onSwitchType(MediaType.MOVIE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("电影") }
            SegmentedButton(
                selected = state.mediaType == MediaType.EPISODE,
                onClick = { onSwitchType(MediaType.EPISODE) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("剧集") }
        }
        Spacer(Modifier.height(8.dp))

        // 已选候选摘要（点击可清除选择并回显「之前的匹配结果」）
        state.selectedMedia?.let { m ->
            SelectedMediaSummary(
                media = m,
                seasonNumber = if (state.mediaType == MediaType.EPISODE) state.seasonNumber else null,
                onClick = onClearSelectedMedia,
            )
            Spacer(Modifier.height(8.dp))
        }

        // 影视搜索框 + 候选列表
        // 已选定 media 时收起搜索区，把空间让给剧集态的季/集面板；
        // 未选定时搜索区占据剩余空间，海报网格可滚动浏览
        if (state.selectedMedia == null) {
            MediaSearchSection(
                modifier = Modifier.weight(1f),
                query = state.mediaSearchQuery,
                results = state.mediaSearchResults,
                loading = state.loading,
                onSearch = onSearchMedia,
                onSelect = onSelectMedia,
            )
        }

        // 剧集态：季选择器 + 集列表
        if (state.mediaType == MediaType.EPISODE && state.selectedMedia != null) {
            SeasonPicker(
                season = state.seasonNumber,
                numberOfSeasons = state.numberOfSeasons,
                onSetSeason = onSetSeason,
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = "已选 ${state.selectedEpisodeNumbers.size} 集",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))

            // 「全部季」时不加载具体集列表，提示用户保存时会遍历所有季匹配所选集号。
            if (state.seasonNumber == null) {
                Text(
                    text = "已选「全部季」：保存时将遍历所有季查找所选集号（依据文件名解析的集号）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EpisodesPanel(
                episodes = state.episodeList,
                selected = state.selectedEpisodeNumbers,
                onToggle = onToggleEpisode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SelectedMediaSummary(
    media: MediaCandidate,
    seasonNumber: Int?,
    onClick: () -> Unit,
) {
    // 已选区：大海报（2:3）+ 元信息卡片，点击可清除重选
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PosterThumb(posterUrl = media.posterUrl, sizeW = 72.dp, sizeH = 108.dp)
            Spacer(Modifier.width(12.dp))
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

@Composable
private fun MediaSearchSection(
    query: String,
    results: List<MediaCandidate>,
    loading: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (MediaCandidate) -> Unit,
) {
    // 搜索框圆角填充式，前置图标 + loading 指示器
    OutlinedTextField(
        value = query,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜索电影或剧集") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
            }
        },
    )
    if (results.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "搜索结果 (${results.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        // 2 列海报网格，海报为主视觉，标题/年份在下方
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results, key = { it.tmdbId }) { c ->
                CandidatePosterCard(candidate = c, onClick = { onSelect(c) })
            }
        }
    }
}

/**
 * 候选海报卡片：2 列网格单元。
 *
 * - 海报（2:3 比例，圆角，主视觉占主导）
 * - 标题（含年份，1 行省略）
 * - 类型徽章（电影/剧集，左下角叠加于海报上）
 *
 * 替代原 [CandidateRow] 的横排小缩略图 + 文字列表布局。
 */
@Composable
private fun CandidatePosterCard(candidate: MediaCandidate, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = candidate.posterUrl,
                contentDescription = candidate.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
            )
            // 类型徽章：左下角叠加（海报上叠加元信息）
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(topEnd = 8.dp, bottomStart = 8.dp),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text(
                    text = if (candidate.mediaType == MediaType.EPISODE) "剧集" else "电影",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val title = if (candidate.year != null) "${candidate.name} (${candidate.year})" else candidate.name
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        candidate.overview?.takeIf { it.isNotBlank() }?.let { ov ->
            Text(
                ov,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 季选择器：下拉列表展示可用季（1..[numberOfSeasons]）+「全部季」选项。
 *
 * 用户要求：先查询剧集总季数再让用户选择，避免猜错季号触发 404。
 * [season] 为 null 表示「全部季」——保存时由 ViewModel 遍历所有季查找匹配集号。
 * [numberOfSeasons] 为 null（TV 详情未加载）时仅展示「全部季」选项，避免展示错误范围。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonPicker(
    season: Int?,
    numberOfSeasons: Int?,
    onSetSeason: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 选项列表：「全部季」+ 1..numberOfSeasons。numberOfSeasons 未拉取时仅显示「全部季」。
    val options = remember(numberOfSeasons) {
        buildList {
            add(null to "全部季")
            val total = numberOfSeasons ?: 0
            for (s in 1..total) add(s to "第 $s 季")
        }
    }
    val currentLabel = if (season == null) "全部季" else "第 $season 季"

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

// ---- 通用 ----

@Composable
private fun PosterThumb(posterUrl: String?, sizeW: androidx.compose.ui.unit.Dp, sizeH: androidx.compose.ui.unit.Dp) {
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

package xa.refile.ui.match

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MenuAnchorType
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

    // 重新选择模式：点击已选剧集卡后进入，搜索框 + 季选择器在此视图内展示。
    // 退出条件：选了新候选 / 点返回按钮（执行取消逻辑：退出重新选择，保留原选择）。
    var reselectMode by rememberSaveable { mutableStateOf(false) }

    // 返回按钮（含系统返回键）在重新选择模式下执行取消逻辑：退出重新选择而非离开页面。
    val onBackClick: () -> Unit = {
        if (reselectMode) {
            reselectMode = false
        } else {
            viewModel.cancel()
            onBack()
        }
    }
    BackHandler(enabled = reselectMode) { reselectMode = false }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = { Text(stringResource(R.string.edit_match_title)) },
                actions = {
                    IconButton(
                        onClick = viewModel::applyEdit,
                        enabled = !state.loading,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.common_save))
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
                        stringResource(R.string.edit_match_no_entry, matchIndex),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SingleMultiView(
                    state = state,
                    reselectMode = reselectMode,
                    onEnterReselect = {
                        viewModel.enterReselectMode()
                        reselectMode = true
                    },
                    onSwitchType = viewModel::switchMediaType,
                    onSearchMedia = viewModel::searchMedia,
                    onSelectMedia = { c ->
                        viewModel.selectMedia(c)
                        reselectMode = false
                    },
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
    reselectMode: Boolean,
    onEnterReselect: () -> Unit,
    onSwitchType: (MediaType) -> Unit,
    onSearchMedia: (String) -> Unit,
    onSelectMedia: (MediaCandidate) -> Unit,
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
            ) { Text(stringResource(R.string.match_type_movie)) }
            SegmentedButton(
                selected = state.mediaType == MediaType.EPISODE,
                onClick = { onSwitchType(MediaType.EPISODE) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.match_type_tv)) }
        }
        Spacer(Modifier.height(8.dp))

        if (state.selectedMedia == null || reselectMode) {
            // 重新选择视图（含搜索框 + 候选列表 + 季选择器）：
            // - 未选定 media（首次进入）
            // - 已选定 media 且处于重新选择模式（点击已选卡触发）
            MediaSearchSection(
                modifier = Modifier.weight(1f),
                query = state.mediaSearchQuery,
                results = state.mediaSearchResults,
                loading = state.loading,
                onSearch = onSearchMedia,
                onSelect = onSelectMedia,
                mediaType = state.mediaType,
                seasonNumber = state.seasonNumber,
                numberOfSeasons = state.numberOfSeasons,
                onSetSeason = onSetSeason,
            )
        } else if (state.mediaType == MediaType.EPISODE) {
            // 剧集态：已选剧集卡（固定不滚动）+ 集列表（滚动）
            val media = state.selectedMedia
            SelectedMediaSummary(
                media = media,
                seasonNumber = state.seasonNumber,
                onClick = onEnterReselect,
            )
            Spacer(Modifier.height(8.dp))
            EpisodesPanel(
                modifier = Modifier.weight(1f),
                episodes = state.episodeList,
                selected = state.selectedEpisodeNumbers,
                onToggle = onToggleEpisode,
            )
        } else {
            // 电影态：仅显示已选卡
            SelectedMediaSummary(
                media = state.selectedMedia,
                seasonNumber = null,
                onClick = onEnterReselect,
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
    val typeLabel = if (media.mediaType == MediaType.EPISODE) stringResource(R.string.match_type_tv) else stringResource(R.string.match_type_movie)
    val seasonLabel = if (seasonNumber == null) stringResource(R.string.common_season_all) else stringResource(R.string.common_season_n, seasonNumber)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSearchSection(
    modifier: Modifier = Modifier,
    query: String,
    results: List<MediaCandidate>,
    loading: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (MediaCandidate) -> Unit,
    mediaType: MediaType,
    seasonNumber: Int?,
    numberOfSeasons: Int?,
    onSetSeason: (Int?) -> Unit,
) {
    Column(modifier = modifier) {
        // 季选择器：剧集态展示（numberOfSeasons 未拉取时仅展示「全部季」选项）
        if (mediaType == MediaType.EPISODE) {
            SeasonPicker(
                season = seasonNumber,
                numberOfSeasons = numberOfSeasons,
                onSetSeason = onSetSeason,
            )
            Spacer(Modifier.height(8.dp))
        }

        // 搜索框圆角填充式，前置图标 + loading 指示器
        OutlinedTextField(
            value = query,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.edit_match_search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            leadingIcon = {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
        )
        if (results.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.edit_match_search_results, results.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            // 候选列表：横排小海报 + 元信息，与已选区视觉统一
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.tmdbId }) { c ->
                    CandidatePosterCard(candidate = c, onClick = { onSelect(c) })
                }
            }
        }
    }
}

/**
 * 候选卡片：横排小海报 + 元信息列表项。
 *
 * 与 [SelectedMediaSummary]（已选区）保持一致的视觉风格：
 * 左侧固定尺寸海报缩略图（72×108，2:3）+ 右侧标题/类型/简介。
 * 替代原 2 列大海报网格，让搜索结果区与外层界面统一。
 */
@Composable
private fun CandidatePosterCard(candidate: MediaCandidate, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PosterThumb(posterUrl = candidate.posterUrl, sizeW = 72.dp, sizeH = 108.dp)
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
                    text = if (candidate.mediaType == MediaType.EPISODE) stringResource(R.string.match_type_tv) else stringResource(R.string.match_type_movie),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                candidate.overview?.takeIf { it.isNotBlank() }?.let { ov ->
                    Spacer(Modifier.height(6.dp))
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
}

/**
 * 季选择器：下拉列表展示可用季（1..[numberOfSeasons]）+「全部季」选项。
 *
 * 用户要求：先查询剧集总季数再让用户选择，避免猜错季号触发 404。
 * [season] 为 null 表示「全部季」——合并加载所有季集列表，保存时由 ViewModel 遍历查找匹配季。
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
    val allSeasonsLabel = stringResource(R.string.common_season_all)
    val total = numberOfSeasons ?: 0
    // 选项列表：「全部季」+ 1..numberOfSeasons。numberOfSeasons 未拉取时仅显示「全部季」。
    val options = buildList {
        add(null to allSeasonsLabel)
        for (s in 1..total) add(s to stringResource(R.string.common_season_n, s))
    }
    val currentLabel = if (season == null) allSeasonsLabel else stringResource(R.string.common_season_n, season)

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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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

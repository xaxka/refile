package xa.refile.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import xa.refile.R

/**
 * Episodes 面板（Task 2.5.3）。
 *
 * 浏览全季集列表（集号 + 标题 + 海报缩略图 + 简介 + 上映日期），支持：
 * - 单选 toggle：点击选中（行首显示 Check 图标），再点取消，互斥（选新的取消旧的）
 *
 * 作为 EditMatchScreen 的子组件嵌入；亦可通过外层包 BottomSheet 复用。
 * [header] 可用于把「已选剧集卡」等内容作为 LazyColumn 首项随列表一起滚动（不固定）。
 *
 * @param episodes 全季集列表
 * @param selected 已选集号集合
 * @param onToggle 单集勾选回调
 * @param header 可选的头部项（插入到 LazyColumn 首部，随列表滚动）
 */
@Composable
fun EpisodesPanel(
    episodes: List<EditMatchViewModel.EpisodeInfo>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (episodes.isEmpty() && header == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.episodes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 全部季模式下不同季可能有相同集号，key 需含 seasonNumber 避免冲突。
            val multiSeasons = episodes.map { it.seasonNumber }.distinct().size > 1
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                header?.invoke(this)
                items(episodes, key = { "${it.seasonNumber}_${it.episodeNumber}" }) { ep ->
                    val isSelected = ep.episodeNumber in selected
                    EpisodeRow(
                        episode = ep,
                        selected = isSelected,
                        showSeasonPrefix = multiSeasons,
                        onClick = { onToggle(ep.episodeNumber) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EditMatchViewModel.EpisodeInfo,
    selected: Boolean,
    onClick: () -> Unit,
    showSeasonPrefix: Boolean = false,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        StillThumbnail(stillUrl = episode.stillUrl)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = if (showSeasonPrefix) {
                    "S${"%02d".format(episode.seasonNumber)}E${"%02d".format(episode.episodeNumber)}"
                } else {
                    "E${"%02d".format(episode.episodeNumber)}"
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            episode.airDate?.takeIf { it.isNotBlank() }?.let { d ->
                Text(
                    text = d,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episode.overview.isNotBlank()) {
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 剧集 still 缩略图（无图时占位）。 */
@Composable
private fun StillThumbnail(stillUrl: String?) {
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!stillUrl.isNullOrBlank()) {
            AsyncImage(
                model = stillUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
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

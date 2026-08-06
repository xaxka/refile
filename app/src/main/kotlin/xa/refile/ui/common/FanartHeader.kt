package xa.refile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import xa.refile.ui.theme.CinemaBlack

/**
 * fanart 渐变遮罩头部（Task 5.5）。
 *
 * 在卡片顶部或详情区使用：若有 [backdropUrl]，用 Coil 加载背景图并以
 * [Brush.verticalGradient] 从透明渐变到 [CinemaBlack]，使叠加在底部的文字可读；
 * 若无 backdrop，渲染纯 [CinemaBlack] 背景（视觉一致）。内容 [content] 叠加在
 * 顶层，调用方可借助 [BoxScope] 自行对齐。
 *
 * @param backdropUrl 背景 fanart 图 URL，null 时不加载图片
 * @param height      头部高度
 * @param content     叠加内容（标题/副标题等）
 */
@Composable
fun FanartHeader(
    backdropUrl: String?,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 渐变遮罩：从顶部透明渐变到底部 CinemaBlack，确保叠加文字可读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.6f to CinemaBlack.copy(alpha = 0.5f),
                            1f to CinemaBlack,
                        ),
                    ),
            )
        } else {
            // 无 backdrop：纯 CinemaBlack 背景，保持视觉一致
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CinemaBlack),
            )
        }
        content()
    }
}

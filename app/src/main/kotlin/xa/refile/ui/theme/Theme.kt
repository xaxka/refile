package xa.refile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * App-wide Compose theme.
 *
 * 浅色固定方案：[darkTheme] 参数仅为 API 对称保留，应用始终使用 [LightColorScheme]
 * 渲染。Dynamic color (Material You) 已禁用，调色板固定。
 *
 * 状态栏 / 系统栏由 [xa.refile.MainActivity] 中的 `enableEdgeToEdge()` 处理，
 * 此处无需额外的 WindowCompat 调整。
 */
@Composable
fun RefileTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}

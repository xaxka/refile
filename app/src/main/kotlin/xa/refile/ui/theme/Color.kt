package xa.refile.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 应用浅色调色板（蓝色浅色方案）。
 *
 * 冷白底 + 纯白表面 + 蓝色主强调 + 冷青次强调。
 * 语义色（成功绿 / 警告橙 / 错误红）保留通用认知，不跟随品牌色变蓝。
 *
 * 注意：常量名 `AccentAmber` / `SecondaryGold` / `AccentTeal` 为历史命名，保留以避免
 * 11 个业务文件大面积 import 改动；实际色值已改为蓝色调，请勿被命名误导。
 * 当前生效方案为 [LightColorScheme]；[DarkColors] 仅作源码兼容别名，等价于 [LightColorScheme]。
 */

// 背景
val AppWhite = Color(0xFFF7FBFF)
val AppBackground = Color(0xFFF7FBFF)

// Surfaces
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE1E8F0)
val CardBackground = Color(0xFFFFFFFF)
// 海报墙加载占位冷灰框
val PosterPlaceholder = Color(0xFFE1E8F0)

// 强调色（命名保留，实际为蓝色调）
val AccentAmber = Color(0xFF0B6CB8)      // primary 蓝
val SecondaryGold = Color(0xFF0288A6)    // secondary 冷青
val AccentTeal = Color(0xFF7D5260)       // tertiary 暖灰玫红

// Text
val TextPrimary = Color(0xFF1A1C1E)
val TextSecondary = Color(0xFF44474E)
val TextDisabled = Color(0xFF74777F)

// Status（语义色，保留通用认知）
val ErrorRed = Color(0xFFBA1A1A)
val SuccessGreen = Color(0xFF34C759)
val WarningAmber = Color(0xFFFF9500)

// 容器色对（补全 Material3 完整角色）
val PrimaryContainer = Color(0xFFCFE6FF)
val OnPrimaryContainer = Color(0xFF001D36)
val SecondaryContainer = Color(0xFFCDE7F2)
val OnSecondaryContainer = Color(0xFF003543)
val TertiaryContainer = Color(0xFFFFD9E2)
val OnTertiaryContainer = Color(0xFF31111D)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF410002)
val InverseSurface = Color(0xFF2F3033)

// Legacy aliases kept for source compatibility (resolve to light equivalents).
val CinemaBlack = AppBackground
val CinemaDark = LightSurface
val DarkSurface = LightSurface
val DarkSurfaceVariant = LightSurfaceVariant

/**
 * 当前生效的浅色方案。
 */
val LightColorScheme = lightColorScheme(
    primary = AccentAmber,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = SecondaryGold,
    onSecondary = Color.White,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = AccentTeal,
    onTertiary = Color.White,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = LightSurface,
    onSurface = TextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = AccentAmber,
    inverseSurface = InverseSurface,
    inverseOnSurface = AppWhite,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    outline = TextSecondary,
    outlineVariant = TextDisabled,
    scrim = Color(0x99000000),
)

/**
 * Alias kept for source compatibility; resolves to [LightColorScheme].
 */
val DarkColors = LightColorScheme
val DarkColorScheme = LightColorScheme

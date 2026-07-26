# 蓝色浅色主题改造规格

## 目标

将应用 UI 主题从当前的「琥珀/橙暖色浅色方案」改造为「蓝色浅色主题」。
配色思路参考媒体类 App 常见的冷色浅色风格（克制、清爽、长时间浏览不疲劳），
**不引入任何第三方品牌名、商标、版权素材**——仅借鉴通用蓝色浅色调色板，
所有颜色常量、命名、注释均使用本项目自有命名，不出现「Evermusic」等任何外部品牌字样。

## 调研结论

- 未找到第三方 App 公开的精确主题色值，且为规避法律风险，**不直接复制任何外部品牌配色**。
- 改造采用通用 Material Design 蓝色浅色主题推荐取值，针对影视库管理场景微调。
- 关键约束：所有硬编码颜色当前已集中在 `app/src/main/kotlin/xa/refile/ui/theme/Color.kt`
  与 `app/src/main/res/values/colors.xml`，业务文件仅通过命名常量引用——改造成本低、风险可控。

## 配色方案（精确 Hex）

| 用途 | 常量名（保持现有命名） | Hex | 说明 |
|---|---|---|---|
| 主背景 | `AppBackground` | `#F7FBFF` | 极浅冷白，避免纯白刺眼 |
| 启动窗口背景 | `AppWhite` / `window_white` | `#F7FBFF` | 与主背景一致，沉浸式浅顶栏 |
| 表面（卡片/底栏） | `LightSurface` / `surface_white` | `#FFFFFF` | 纯白，与背景形成微弱层级 |
| 表面变体 | `LightSurfaceVariant` / `surface_variant_light` | `#E1E8F0` | 浅蓝灰，输入框/未选中 Chip |
| 卡片背景 | `CardBackground` | `#FFFFFF` | 同 LightSurface |
| 海报占位 | `PosterPlaceholder` | `#E1E8F0` | 与表面变体同色，冷灰占位 |
| 主强调色（Primary） | `AccentAmber`（保留命名） | `#0B6CB8` | 清晰中等饱和蓝，按钮/选中态 |
| 次强调色（Secondary） | `SecondaryGold`（保留命名） | `#0288A6` | 冷青蓝，二级高亮/进度 |
| 第三强调色（Tertiary） | `AccentTeal`（保留命名） | `#7D5260` | 暖灰玫红，仅极少场景 |
| 主文字 | `TextPrimary` | `#1A1C1E` | 近黑略带冷调 |
| 次文字 | `TextSecondary` | `#44474E` | 中蓝灰 |
| 禁用/占位文字 | `TextDisabled` | `#74777F` | 中灰 |
| 错误 | `ErrorRed` | `#BA1A1A` | Material 标准红 |
| 成功 | `SuccessGreen` | `#34C759` | 保持不变（语义色） |
| 警告 | `WarningAmber`（保留命名） | `#FF9500` | 保持不变（语义色，橙保留用于警告） |

### 配色策略说明

- **语义色与品牌色分离**：`WarningAmber` 是「警告」语义色（橙），与「品牌主色」是不同维度。
  改造后主品牌色变蓝，但警告仍保留橙色符合用户认知（红=错误、橙=警告、绿=成功），
  不强行把警告也变蓝，避免语义混乱。
- **`SuccessGreen` 不变**：绿色=成功的语义认知全球通用，保留。
- **常量名保留**：`AccentAmber`/`SecondaryGold`/`AccentTeal` 等命名虽与新色相不符，
  但改名会触发 11 个业务文件的大面积 import 改动且无功能收益。本次仅改色值、不动命名，
  在常量上方注释里说明「命名保留以避免大面积重构，实际为蓝色调主色」。

## 影响范围

### 必改文件（共 3 个）

1. **`app/src/main/kotlin/xa/refile/ui/theme/Color.kt`**
   - 更新全部色值常量的 Hex（除 `SuccessGreen`、`WarningAmber` 外）
   - 更新 `LightColorScheme` 构造：补充 `primaryContainer`/`onPrimaryContainer`/
     `secondaryContainer`/`onSecondaryContainer`/`tertiaryContainer`/`onTertiaryContainer`/
     `errorContainer`/`onErrorContainer` 等当前缺失或填错的容器色对（参照下表）
   - 更新顶部注释（移除「琥珀/橙强调色」描述，改为「蓝色浅色方案」）
   - 保留 `DarkColors`/`DarkColorScheme` 别名指向 `LightColorScheme`

2. **`app/src/main/res/values/colors.xml`**
   - `window_white` / `surface_white` → `#F7FBFF`
   - `surface_variant_light` → `#E1E8F0`
   - `accent_amber` → `#0B6CB8`（虽名为 amber，实为启动期 brand 色，跟随主色）

3. **`app/src/main/kotlin/xa/refile/ui/browser/BrowserScreen.kt`**
   - 第 81 行 `private val AmberColor = Color(0xFFFFC107)`（合集图标高亮色）
   - 改为引用 `MaterialTheme.colorScheme.primary` 或新增常量 `AccentBlue = AccentAmber`，
     消除最后一处硬编码暖色。推荐直接用 `MaterialTheme.colorScheme.primary`，
     语义最清晰、零新增常量。

### 不需改的文件

- `Theme.kt`：仅组装 `LightColorScheme`，无需改动
- `Shape.kt` / `Type.kt`：形状与字体不变
- 11 个业务 Screen 文件：全部通过命名常量或 `MaterialTheme.colorScheme.*` 引用，
  色值变更会自动透传，无需逐文件改动
- `AndroidManifest.xml` / `MainActivity.kt`：状态栏透明 + `windowLightStatusBar=true`
  在浅色主题下仍然正确，无需改动

## LightColorScheme 完整映射表

```
primary             = AccentAmber      (#0B6CB8)
onPrimary           = Color.White
primaryContainer    = Color(0xFFCFE6FF)  // 浅蓝容器
onPrimaryContainer  = Color(0xFF001D36)  // 深蓝文字
secondary           = SecondaryGold    (#0288A6)
onSecondary         = Color.White
secondaryContainer  = Color(0xFFCDE7F2)  // 浅冷青容器
onSecondaryContainer= Color(0xFF003543)
tertiary            = AccentTeal       (#7D5260)
onTertiary          = Color.White
tertiaryContainer   = Color(0xFFFFD9E2)
onTertiaryContainer = Color(0xFF31111D)
background          = AppBackground    (#F7FBFF)
onBackground        = TextPrimary      (#1A1C1E)
surface             = LightSurface     (#FFFFFF)
onSurface           = TextPrimary
surfaceVariant      = LightSurfaceVariant (#E1E8F0)
onSurfaceVariant    = TextSecondary    (#44474E)
surfaceTint         = AccentAmber      (#0B6CB8)
inverseSurface      = Color(0xFF2F3033)
inverseOnSurface    = AppWhite
error               = ErrorRed         (#BA1A1A)
onError             = Color.White
errorContainer      = Color(0xFFFFDAD6)
onErrorContainer    = Color(0xFF410002)
outline             = TextSecondary    (#44474E)
outlineVariant      = TextDisabled     (#74777F)
scrim               = Color(0x99000000)
```

## 验证清单

- [ ] `./gradlew :core:test` 通过
- [ ] `./gradlew :app:assembleRelease` 通过
- [ ] CI Android Build 全步骤 success
- [ ] 全局搜索确认无残留 `#E8941D` / `#FFA630` / `#1F8C8C` / `#FFC107` 等暖色 hex
- [ ] 全局搜索确认无任何「Evermusic」字样出现在源码/注释/资源/提交信息中
- [ ] 视觉抽查（dev APK）：
  - 顶栏/背景为冷白
  - 主按钮、Tab 选中、开关激活为蓝色 `#0B6CB8`
  - 错误态红、警告态橙、成功态绿保持原语义
  - 海报占位框为冷灰，不再是中性灰

## 风险与规避

| 风险 | 规避措施 |
|---|---|
| 法律风险：使用第三方品牌名/配色 | 仅借鉴通用蓝色浅色配色思路，不引用任何品牌名；常量命名均为本项目自有 |
| 语义色被误改导致用户认知混乱 | `SuccessGreen`/`WarningAmber` 不变，仅品牌主色变蓝 |
| 常量名（Amber/Gold/Teal）与实际色相不符造成后续维护困惑 | 在 `Color.kt` 顶部加注释说明「命名保留以避免大面积重构，实际为蓝色调」 |
| 容器色对缺失导致部分组件用默认色 | 在 `LightColorScheme` 中补全所有 `*Container`/`on*Container` 字段 |

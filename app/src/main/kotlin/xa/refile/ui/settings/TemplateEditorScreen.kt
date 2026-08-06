package xa.refile.ui.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.ui.theme.WarningAmber
import kotlinx.coroutines.launch

/**
 * 模板编辑器页（计划 §M3 SubTask 3.3.1 + 测试反馈 Item 9/10）。
 *
 * 布局（自上而下）：
 * - 顶部 TabRow：电影模板 / 剧集模板，分别编辑。
 * - 实时预览卡片：仅展示当前 Tab 对应类型的预览（电影 Tab → 电影示例，剧集 Tab → 剧集示例）。
 * - 模板字符串输入框（多行 monospace），绑定当前 Tab 对应的模板。
 * - 变量插入：按组用 [FlowRow] 自动换行（测试反馈 Item 10，避免横向拥挤）。
 * - 顶部 TopAppBar：保存 + 重置为默认规则（图标按钮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(
    onBack: () -> Unit,
    viewModel: TemplateEditorViewModel = hiltViewModel(),
) {
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val movieField by viewModel.movieTemplateField.collectAsStateWithLifecycle()
    val episodeField by viewModel.episodeTemplateField.collectAsStateWithLifecycle()
    val preview by viewModel.previewResult.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isSaving by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val currentField = when (activeTab) {
        TemplateEditorViewModel.EditorTab.MOVIE -> movieField
        TemplateEditorViewModel.EditorTab.EPISODE -> episodeField
    }

    val savedMsg = stringResource(R.string.template_editor_saved)
    val saveFailedPrefix = stringResource(R.string.template_editor_save_failed_prefix)
    val unknownErrorMsg = stringResource(R.string.template_editor_unknown_error)

    val save: () -> Unit = {
        if (!isSaving) {
            isSaving = true
            scope.launch {
                try {
                    viewModel.save()
                    snackbarHostState.showSnackbar(savedMsg)
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    snackbarHostState.showSnackbar(saveFailedPrefix + (e.message ?: unknownErrorMsg))
                } finally {
                    isSaving = false
                }
            }
        }
    }

    val reset: () -> Unit = {
        showResetDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.template_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = reset) {
                        Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.template_editor_reset_default))
                    }
                    IconButton(onClick = save, enabled = !isSaving) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.common_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 测试反馈 Item 9：电影/剧集模板分 Tab 编辑（京东风格胶囊 Tab）
            PillTabBar(
                tabs = TemplateEditorViewModel.EditorTab.entries.map { tab ->
                    when (tab) {
                        TemplateEditorViewModel.EditorTab.MOVIE -> stringResource(R.string.template_editor_tab_movie)
                        TemplateEditorViewModel.EditorTab.EPISODE -> stringResource(R.string.template_editor_tab_episode)
                    }
                },
                selectedIndex = activeTab.ordinal,
                onTabSelected = { index -> viewModel.selectTab(TemplateEditorViewModel.EditorTab.entries[index]) },
            )

            // 预览放顶部：仅展示当前 Tab 对应类型的示例
            PreviewCard(
                preview = preview,
                activeTab = activeTab,
            )

            val activeTabLabel = when (activeTab) {
                TemplateEditorViewModel.EditorTab.MOVIE -> stringResource(R.string.template_editor_tab_movie)
                TemplateEditorViewModel.EditorTab.EPISODE -> stringResource(R.string.template_editor_tab_episode)
            }
            OutlinedTextField(
                value = currentField,
                onValueChange = viewModel::updateTemplate,
                label = { Text(stringResource(R.string.template_editor_template_label, activeTabLabel)) },
                supportingText = {
                    Text(stringResource(R.string.template_editor_supporting_text))
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                minLines = 4,
                maxLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
            )

            // 测试反馈 Item 10：变量按组可折叠，默认只展开当前 Tab 相关组
            VariableChips(
                tokens = viewModel.availableVariables,
                onInsert = viewModel::insertVariable,
                activeTab = activeTab,
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.template_editor_reset_confirm_title)) },
            text = { Text(stringResource(R.string.template_editor_reset_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetToDefault()
                        showResetDialog = false
                    }
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false }
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * 变量插入 chip：按组用 [ScrollableTabRow] 切换（测试反馈 Item 10 + 拥挤优化）。
 *
 * 优化：~120 个变量 token 全展开太拥挤。改为顶部按组分的可滚动 Tab 栏，
 * 选中某组后下方 FlowRow 仅展示该组的 chip，行高与间距收紧。
 * 默认选中当前 Tab 相关组（电影 Tab → 通用，剧集 Tab → 剧集）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableChips(
    tokens: List<TemplateEditorViewModel.VariableToken>,
    onInsert: (String) -> Unit,
    activeTab: TemplateEditorViewModel.EditorTab,
) {
    // 电影模板时隐藏剧集变量，剧集模板时隐藏电影变量
    val filteredTokens = when (activeTab) {
        TemplateEditorViewModel.EditorTab.MOVIE -> tokens.filter { it.group != "剧集" }
        TemplateEditorViewModel.EditorTab.EPISODE -> tokens.filter { it.group != "电影" }
    }
    val grouped = filteredTokens.groupBy { it.group }
    val groupNames = grouped.keys.toList()
    // 不联动 activeTab：selectedGroup 只在首次组合时初始化为 0
    var selectedGroup by remember { mutableStateOf(0) }
    // 越界保护：过滤后组数可能变少
    val safeIndex = selectedGroup.coerceIn(0, groupNames.lastIndex.coerceAtLeast(0))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PillTabBar(
            tabs = groupNames,
            selectedIndex = safeIndex,
            onTabSelected = { selectedGroup = it },
            compact = true,
        )
        val items = grouped[groupNames[safeIndex]].orEmpty()
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { token ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.clickable { onInsert(token.token) },
                ) {
                    Text(
                        text = "{${token.token}} · ${token.label}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * 京东/淘宝风格 Tab 栏：选中项文字为主题色加粗，底部有一条短粗主题色指示线，
 * 跟随选中 Tab 滑动动画移动。未选中项为默认灰色文字，无背景填充。
 *
 * 指示线宽度为 Tab 宽度的 [indicatorWidthFraction]，居中于选中 Tab 下方，
 * 切换时通过 [animateFloatAsState] 平滑滑动到新位置。
 *
 * 位置计算：用 tab 宽度累加 + scrollState.value 直接算，不依赖 positionInRoot()，
 * 避免滚动时 onGloballyPositioned 回调时序导致指示线位置偏移。
 */
@Composable
private fun PillTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    compact: Boolean = false,
) {
    val tabHeight = if (compact) 32.dp else 38.dp
    val hPadding = if (compact) 12.dp else 16.dp
    val textSizeStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val indicatorHeight = if (compact) 2.dp else 3.dp
    val indicatorWidthFraction = 0.55f

    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // 仅记录每个 Tab 的宽度（含 padding），不依赖 positionInRoot
    val tabWidths = remember { mutableStateMapOf<Int, Float>() }

    // 选中 Tab 在 Row 内容中的左边缘 = 前面所有 Tab 宽度之和
    val selectedTabLeftPx = (0 until selectedIndex).sumOf { (tabWidths[it] ?: 0f).toDouble() }.toFloat()
    val selectedTabWidthPx = tabWidths[selectedIndex] ?: 0f

    // 只在切换 Tab 时动画，滚动时直接跟随
    val animContentLeftPx by animateFloatAsState(
        targetValue = selectedTabLeftPx,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "indicator_content_left",
    )
    val animTabWidthPx by animateFloatAsState(
        targetValue = selectedTabWidthPx,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "indicator_tab_width",
    )

    val indicatorWidthDp = with(density) { (animTabWidthPx * indicatorWidthFraction).toDp() }

    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .height(tabHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                val textColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal

                Text(
                    text = label,
                    style = textSizeStyle,
                    fontWeight = fontWeight,
                    color = textColor,
                    modifier = Modifier
                        .onGloballyPositioned { coords: LayoutCoordinates ->
                            tabWidths[index] = coords.size.width.toFloat()
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onTabSelected(index) }
                        .padding(horizontal = hPadding),
                )
            }
        }

        // 底部指示线：位置 = 动画 tab 位置 - 滚动偏移 + 居中
        if (animTabWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        val scrollPx = scrollState.value.toFloat()
                        val left = animContentLeftPx - scrollPx +
                            animTabWidthPx * (1f - indicatorWidthFraction) / 2f
                        IntOffset(left.roundToInt(), 0)
                    }
                    .width(indicatorWidthDp)
                    .height(indicatorHeight)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(indicatorHeight / 2),
                    ),
            )
        }
    }
}

/**
 * 实时预览卡片：仅展示当前 Tab 对应类型的示例。
 * - 电影 Tab → 只显示电影示例预览。
 * - 剧集 Tab → 只显示剧集示例预览。
 */
@Composable
private fun PreviewCard(
    preview: TemplateEditorViewModel.PreviewUi,
    activeTab: TemplateEditorViewModel.EditorTab,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.template_editor_live_preview), style = MaterialTheme.typography.titleSmall)

            when (activeTab) {
                TemplateEditorViewModel.EditorTab.MOVIE ->
                    PreviewItem(label = stringResource(R.string.template_editor_movie_example), value = preview.movie)
                TemplateEditorViewModel.EditorTab.EPISODE ->
                    PreviewItem(label = stringResource(R.string.template_editor_episode_example), value = preview.episode)
            }

            if (preview.warnings.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                preview.warnings.forEach { w ->
                    Text(
                        text = "⚠ $w",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.ifBlank { stringResource(R.string.template_editor_empty) },
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

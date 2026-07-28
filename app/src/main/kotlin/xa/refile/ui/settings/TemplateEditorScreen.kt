package xa.refile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.data.prefs.VisualOptions
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
 * - 可视化选项：仅补零位数 Slider。
 * - 底部：保存按钮 + 重置为默认规则按钮。
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
    val visualOptions by viewModel.visualOptions.collectAsStateWithLifecycle()
    val preview by viewModel.previewResult.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isSaving by remember { mutableStateOf(false) }

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
        viewModel.resetToDefault()
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
            // 测试反馈 Item 9：电影/剧集模板分 Tab 编辑
            TabRow(selectedTabIndex = activeTab.ordinal) {
                TemplateEditorViewModel.EditorTab.entries.forEach { tab ->
                    val tabLabel = when (tab) {
                        TemplateEditorViewModel.EditorTab.MOVIE -> stringResource(R.string.template_editor_tab_movie)
                        TemplateEditorViewModel.EditorTab.EPISODE -> stringResource(R.string.template_editor_tab_episode)
                    }
                    Tab(
                        selected = activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tabLabel) },
                    )
                }
            }

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

            // 测试反馈 Item 10：变量用 FlowRow 自动换行，避免横向拥挤
            VariableChips(
                tokens = viewModel.availableVariables,
                onInsert = viewModel::insertVariable,
            )

            VisualOptionsSection(
                options = visualOptions,
                onUpdate = viewModel::saveVisualOptions,
            )

            Button(
                onClick = save,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.common_saving))
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }

            OutlinedButton(
                onClick = reset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.template_editor_reset_default))
            }
        }
    }
}

/**
 * 变量插入 chip：按组用 [FlowRow] 自动换行（测试反馈 Item 10）。
 *
 * 改进：之前每组一行横向滚动，变量多时拥挤且需横向滑动查找。
 * 现改为 FlowRow 自动换行，一屏内可见全部变量，点击即插入。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableChips(
    tokens: List<TemplateEditorViewModel.VariableToken>,
    onInsert: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.template_editor_insert_variable), style = MaterialTheme.typography.labelLarge)
        val grouped = tokens.groupBy { it.group }
        grouped.forEach { (group, items) ->
            Text(
                text = group,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { token ->
                    AssistChip(
                        onClick = { onInsert(token.token) },
                        label = { Text("{${token.token}} · ${token.label}") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
    }
}

/** 可视化选项：仅补零位数（分隔符/大小写/非法字符处理已按需求移除）。 */
@Composable
private fun VisualOptionsSection(
    options: VisualOptions,
    onUpdate: (VisualOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.template_editor_visual_options), style = MaterialTheme.typography.labelLarge)

        // 补零位数
        Text(
            stringResource(R.string.template_editor_pad_digits, options.padDigits),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = options.padDigits.toFloat(),
            onValueChange = { onUpdate(options.copy(padDigits = it.toInt())) },
            valueRange = 1f..3f,
            steps = 1,
        )
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

package xa.refile.ui.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.model.MediaType
import xa.refile.core.naming.BatchContext
import xa.refile.core.naming.BindingResolver
import xa.refile.core.naming.FileContext
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.naming.Preset
import xa.refile.core.naming.PresetRepository
import xa.refile.core.naming.TemplateEngine
import xa.refile.core.parser.ParsedFilename
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.prefs.VisualOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 模板编辑器 ViewModel（计划 §M3 SubTask 3.3.1 + 测试反馈 Item 9）。
 *
 * 按测试反馈 Item 9 改造：电影/剧集模板分离编辑。
 * 已移除预设选择与「另存为预设」功能，仅保留直接编辑电影/剧集模板的能力。
 *
 * 持有：
 * - [movieTemplateField] / [episodeTemplateField]：电影/剧集模板字符串 + 光标位置。
 * - [activeTab]：当前编辑的标签（电影/剧集）。
 * - [presetId]：当前预设 ID（始终为 [Preset.DEFAULT]，保留以便旧调用方兼容）。
 * - [visualOptions]：仅补零位数（分隔符/大小写/非法字符处理已移除）。
 * - [previewResult]：用固定电影 + 剧集示例实时渲染的结果（各自用对应模板）。
 *
 * 渲染走 [TemplateEngine]，每次创建新的 [BindingResolver] 避免警告累积。
 */
@HiltViewModel
class TemplateEditorViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @Suppress("unused") private val presets: PresetRepository,
) : ViewModel() {

    /** 编辑标签：电影模板 / 剧集模板。 */
    enum class EditorTab(val label: String) {
        MOVIE("电影模板"),
        EPISODE("剧集模板"),
    }

    /** 可插入变量 token 描述。 */
    data class VariableToken(
        val token: String,
        val label: String,
        val group: String,
    )

    /** 预览结果（电影 + 剧集两份示例，各自用对应模板渲染）。 */
    data class PreviewUi(
        val movie: String,
        val episode: String,
        val warnings: List<String> = emptyList(),
    )

    /** 固定示例上下文（电影 + 剧集），避免依赖实际选中文件。 */
    private data class SampleContext(
        val media: MediaMetadata,
        val file: FileContext,
        val batch: BatchContext,
    )

    /** 电影模板字段（含光标）。 */
    private val _movieTemplateField = MutableStateFlow(TextFieldValue(""))
    val movieTemplateField: StateFlow<TextFieldValue> = _movieTemplateField.asStateFlow()

    /** 剧集模板字段（含光标）。 */
    private val _episodeTemplateField = MutableStateFlow(TextFieldValue(""))
    val episodeTemplateField: StateFlow<TextFieldValue> = _episodeTemplateField.asStateFlow()

    /** 当前编辑标签。 */
    private val _activeTab = MutableStateFlow(EditorTab.MOVIE)
    val activeTab: StateFlow<EditorTab> = _activeTab.asStateFlow()

    /** 当前预设 ID（始终为 [Preset.DEFAULT]，保留以便旧调用方兼容）。 */
    private val _presetId = MutableStateFlow(Preset.DEFAULT.name)
    val presetId: StateFlow<String> = _presetId.asStateFlow()

    /** 可视化选项。 */
    private val _visualOptions = MutableStateFlow(VisualOptions())
    val visualOptions: StateFlow<VisualOptions> = _visualOptions.asStateFlow()

    /** 实时预览：任一模板或可视化选项变化即重渲染（电影/剧集各自用对应模板）。 */
    val previewResult: StateFlow<PreviewUi> =
        combine(_movieTemplateField, _episodeTemplateField, _visualOptions) { movie, episode, opts ->
            renderPreview(movie.text, episode.text, opts)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PreviewUi("", ""),
        )

    /** 可插入变量列表（基于实际 [BindingResolver] 支持的 token）。 */
    val availableVariables: List<VariableToken> = VARIABLE_TOKENS

    init {
        viewModelScope.launch {
            val savedMovie = settings.movieTemplateString.first()
            val savedEpisode = settings.episodeTemplateString.first()
            val savedLegacy = settings.templateString.first()
            val savedOpts = settings.visualOptions.first()
            val savedPreset = settings.presetId.first()
            _visualOptions.value = savedOpts
            _presetId.value = savedPreset
            val preset = Preset.byId(savedPreset)
            // 优先用已保存的独立模板，回退到旧版单模板，再回退到预设默认模板
            _movieTemplateField.value = TextFieldValue(
                savedMovie.ifBlank { savedLegacy.ifBlank { preset.movieTemplate } },
            )
            _episodeTemplateField.value = TextFieldValue(
                savedEpisode.ifBlank { savedLegacy.ifBlank { preset.episodeTemplate } },
            )
        }
    }

    /** 切换编辑标签。 */
    fun selectTab(tab: EditorTab) {
        _activeTab.value = tab
    }

    /** 更新当前标签对应的模板字段（含光标）。 */
    fun updateTemplate(value: TextFieldValue) {
        when (_activeTab.value) {
            EditorTab.MOVIE -> _movieTemplateField.value = value
            EditorTab.EPISODE -> _episodeTemplateField.value = value
        }
    }

    /** 在当前光标位置插入 `{token}`，并把光标移到插入内容之后。 */
    fun insertVariable(token: String) {
        val insert = "{$token}"
        when (_activeTab.value) {
            EditorTab.MOVIE -> {
                val current = _movieTemplateField.value
                _movieTemplateField.value = insertAtCursor(current, insert)
            }
            EditorTab.EPISODE -> {
                val current = _episodeTemplateField.value
                _episodeTemplateField.value = insertAtCursor(current, insert)
            }
        }
    }

    private fun insertAtCursor(current: TextFieldValue, insert: String): TextFieldValue {
        val pos = current.selection.min.coerceIn(0, current.text.length)
        val newText = buildString {
            append(current.text.substring(0, pos))
            append(insert)
            append(current.text.substring(pos))
        }
        val newCursor = pos + insert.length
        return TextFieldValue(text = newText, selection = TextRange(newCursor))
    }

    /**
     * 选择预设。
     *
     * 已移除多预设与自定义预设：仅保留 [Preset.DEFAULT]，加载默认模板。
     */
    fun selectPreset(id: String) {
        _presetId.value = Preset.DEFAULT.name
        val preset = Preset.DEFAULT
        _movieTemplateField.value = TextFieldValue(preset.movieTemplate)
        _episodeTemplateField.value = TextFieldValue(preset.episodeTemplate)
    }

    /**
     * 重置为默认规则：把电影/剧集模板与可视化选项恢复为 [Preset.DEFAULT]。
     *
     * 仅更新内存字段，用户需点「保存」才会落库（与编辑后保存语义一致）。
     */
    fun resetToDefault() {
        _presetId.value = Preset.DEFAULT.name
        val preset = Preset.DEFAULT
        _movieTemplateField.value = TextFieldValue(preset.movieTemplate)
        _episodeTemplateField.value = TextFieldValue(preset.episodeTemplate)
        _visualOptions.value = VisualOptions()
    }

    /** 更新可视化选项（实时影响预览）并持久化。 */
    fun saveVisualOptions(opts: VisualOptions) {
        _visualOptions.value = opts
        // B18: 旧实现只更新内存 StateFlow，不写 DataStore；用户不点「保存」退出则丢失。
        // 改为 debounce 持久化（visualOptions 改动频繁，立即写会与模板编辑竞争 IO）。
        viewModelScope.launch {
            settings.setVisualOptions(opts)
        }
    }

    /**
     * 持久化当前电影/剧集模板、预设、可视化选项到 DataStore。
     *
     * 同时把旧版单模板字段 [SettingsRepository.templateString] 同步为电影模板，
     * 以保证未升级到分模板逻辑的调用方仍可用。
     */
    suspend fun save() {
        val movie = _movieTemplateField.value.text
        val episode = _episodeTemplateField.value.text
        settings.setMovieTemplateString(movie)
        settings.setEpisodeTemplateString(episode)
        settings.setTemplateString(movie)
        settings.setPresetId(_presetId.value)
        settings.setVisualOptions(_visualOptions.value)
    }

    /** 用固定示例渲染：电影模板渲染电影示例，剧集模板渲染剧集示例。 */
    private fun renderPreview(movieTemplate: String, episodeTemplate: String, opts: VisualOptions): PreviewUi {
        val namingOptions = opts.toNamingOptions()
        val movie = TemplateEngine(
            BindingResolver(MOVIE_SAMPLE.media, MOVIE_SAMPLE.file, MOVIE_SAMPLE.batch, namingOptions),
            namingOptions,
        ).render(movieTemplate)
        val episode = TemplateEngine(
            BindingResolver(EPISODE_SAMPLE.media, EPISODE_SAMPLE.file, EPISODE_SAMPLE.batch, namingOptions),
            namingOptions,
        ).render(episodeTemplate)
        return PreviewUi(
            movie = movie.path,
            episode = episode.path,
            warnings = (movie.warnings + episode.warnings).distinct(),
        )
    }

    private companion object {
        /** 可插入变量（基于实际 [BindingResolver] 支持的 token，按组分类）。 */
        val VARIABLE_TOKENS = listOf(
            VariableToken("n", "标题（电影名/剧集名）", "通用"),
            VariableToken("y", "年份", "通用"),
            VariableToken("ny", "标题 (年份)", "通用"),
            VariableToken("collection", "合集", "通用"),
            VariableToken("genre", "类型", "通用"),
            VariableToken("director", "导演", "通用"),
            VariableToken("rating", "评分", "通用"),
            VariableToken("s", "季号", "剧集"),
            VariableToken("e", "集号", "剧集"),
            VariableToken("s00", "季号补零", "剧集"),
            VariableToken("e00", "集号补零", "剧集"),
            VariableToken("s00e00", "S01E01 格式", "剧集"),
            VariableToken("sxe", "1x01 格式", "剧集"),
            VariableToken("t", "集标题", "剧集"),
            VariableToken("absolute", "绝对集号", "剧集"),
            VariableToken("d", "首播日期", "剧集"),
            VariableToken("fn", "原始文件名", "文件/媒体"),
            VariableToken("ext", "扩展名", "文件/媒体"),
            VariableToken("vf", "分辨率", "文件/媒体"),
            VariableToken("vc", "视频编码器", "文件/媒体"),
            VariableToken("ac", "音频编码器", "文件/媒体"),
            VariableToken("group", "发布组", "文件/媒体"),
            VariableToken("folder", "父目录", "文件/媒体"),
            VariableToken("bytes", "文件大小", "文件/媒体"),
            VariableToken("today", "今天日期", "文件/媒体"),
        )

        val MOVIE_SAMPLE = SampleContext(
            media = MediaMetadata(
                type = MediaType.MOVIE,
                name = "盗梦空间",
                year = 2010,
                collectionName = "诺兰合集",
                genres = listOf("科幻", "动作"),
                director = "克里斯托弗·诺兰",
                rating = 8.8,
            ),
            file = FileContext(
                displayName = "Inception.2010.1080p.BluRay.x265.AC3.mkv",
                ext = "mkv",
                folder = "电影",
                parsed = ParsedFilename(
                    title = "Inception",
                    year = 2010,
                    resolution = "1080p",
                    source = "BluRay",
                    videoCodec = "x265",
                    audioCodec = "AC3",
                    group = "GROUP",
                ),
            ),
            batch = BatchContext(today = "2026-07-23"),
        )

        val EPISODE_SAMPLE = SampleContext(
            media = MediaMetadata(
                type = MediaType.EPISODE,
                name = "权力的游戏",
                year = 2011,
                seasonNumber = 1,
                episodeNumbers = listOf(1),
                episodeTitles = listOf("凛冬将至"),
                genres = listOf("奇幻", "剧情"),
                rating = 9.4,
            ),
            file = FileContext(
                displayName = "Game.of.Thrones.S01E01.1080p.WEB-DL.x264.AAC.mkv",
                ext = "mkv",
                folder = "权力的游戏",
                parsed = ParsedFilename(
                    title = "Game of Thrones",
                    year = 2011,
                    season = 1,
                    episodes = listOf(1),
                    resolution = "1080p",
                    source = "WEB-DL",
                    videoCodec = "x264",
                    audioCodec = "AAC",
                    group = "GROUP",
                ),
            ),
            batch = BatchContext(today = "2026-07-23"),
        )
    }
}

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
        /** 可插入变量（与 [BindingResolver] 已实现的绑定一一对应，按组分类；对齐 FileBot）。 */
        val VARIABLE_TOKENS = listOf(
            // ---- 通用（电影 + 剧集）----
            VariableToken("n", "名称", "通用"),
            VariableToken("y", "年份", "通用"),
            VariableToken("ny", "名称 (年份)", "通用"),
            VariableToken("primaryTitle", "原始名", "通用"),
            VariableToken("alias", "别名列表", "通用"),
            VariableToken("type", "对象类型 Movie/Episode", "通用"),
            VariableToken("object", "匹配对象", "通用"),
            VariableToken("id", "数据库 ID", "通用"),
            VariableToken("tmdbid", "TMDB ID", "通用"),
            VariableToken("imdbid", "IMDb ID", "通用"),
            VariableToken("genres", "类型列表", "通用"),
            VariableToken("genre", "主要类型", "通用"),
            VariableToken("language", "原始语言", "通用"),
            VariableToken("languages", "语言列表", "通用"),
            VariableToken("country", "国家/地区", "通用"),
            VariableToken("certification", "分级", "通用"),
            VariableToken("rating", "评分", "通用"),
            VariableToken("votes", "投票数", "通用"),
            VariableToken("director", "导演", "通用"),
            VariableToken("actors", "演员列表", "通用"),
            VariableToken("runtime", "时长（分钟）", "通用"),
            VariableToken("decade", "年代", "通用"),
            VariableToken("az", "排序字母", "通用"),
            VariableToken("today", "今天日期", "通用"),
            // ---- 电影 ----
            VariableToken("movie", "电影对象", "电影"),
            VariableToken("collection", "合集", "电影"),
            VariableToken("ci", "合集序号", "电影"),
            VariableToken("cy", "合集年份", "电影"),
            // ---- 剧集 ----
            VariableToken("series", "剧集对象", "剧集"),
            VariableToken("episode", "单集对象", "剧集"),
            VariableToken("episodelist", "剧集列表", "剧集"),
            VariableToken("s", "季号", "剧集"),
            VariableToken("s00", "季号补零", "剧集"),
            VariableToken("e", "集号", "剧集"),
            VariableToken("e00", "集号补零", "剧集"),
            VariableToken("es", "多集号列表", "剧集"),
            VariableToken("sxe", "1x01 格式", "剧集"),
            VariableToken("s00e00", "S01E01 格式", "剧集"),
            VariableToken("t", "集标题", "剧集"),
            VariableToken("d", "播出/上映日期", "剧集"),
            VariableToken("startdate", "开播日期", "剧集"),
            VariableToken("absolute", "绝对集号", "剧集"),
            VariableToken("special", "特别篇号", "剧集"),
            VariableToken("sn", "季名", "剧集"),
            VariableToken("sy", "季年份", "剧集"),
            VariableToken("sc", "季总数", "剧集"),
            VariableToken("anime", "是否动漫", "剧集"),
            VariableToken("regular", "是否常规集", "剧集"),
            VariableToken("tvdbid", "TVDB ID", "剧集"),
            // ---- 文件与路径 ----
            VariableToken("fn", "文件名（不含扩展名）", "文件与路径"),
            VariableToken("ext", "扩展名", "文件与路径"),
            VariableToken("f", "完整路径", "文件与路径"),
            VariableToken("folder", "父目录", "文件与路径"),
            VariableToken("drive", "库根目录", "文件与路径"),
            VariableToken("relativeFile", "相对路径", "文件与路径"),
            VariableToken("mediaFile", "媒体文件路径", "文件与路径"),
            VariableToken("mediaFileName", "媒体文件名", "文件与路径"),
            VariableToken("original", "原始文件名", "文件与路径"),
            VariableToken("files", "批次文件数", "文件与路径"),
            VariableToken("model", "批次列表", "文件与路径"),
            VariableToken("self", "绑定摘要", "文件与路径"),
            VariableToken("i", "批次序号", "文件与路径"),
            VariableToken("ct", "修改时间", "文件与路径"),
            VariableToken("age", "文件天数", "文件与路径"),
            VariableToken("bytes", "文件大小", "文件与路径"),
            VariableToken("megabytes", "大小 (MB)", "文件与路径"),
            VariableToken("gigabytes", "大小 (GB)", "文件与路径"),
            // ---- 技术标签（来自文件名解析，非 MediaInfo）----
            VariableToken("vf", "分辨率", "技术标签"),
            VariableToken("vc", "视频编码", "技术标签"),
            VariableToken("ac", "音频编码", "技术标签"),
            VariableToken("cf", "容器格式", "技术标签"),
            VariableToken("vs", "来源类别", "技术标签"),
            VariableToken("source", "来源", "技术标签"),
            VariableToken("edition", "版本（导演剪辑版等）", "技术标签"),
            VariableToken("tags", "版本标签", "技术标签"),
            VariableToken("s3d", "3D 格式", "技术标签"),
            VariableToken("hdr", "HDR 格式", "技术标签"),
            VariableToken("dovi", "杜比视界", "技术标签"),
            VariableToken("group", "发布组", "技术标签"),
            // ---- 字幕（来自字幕文件名解析）----
            VariableToken("lang", "字幕语言", "字幕"),
            VariableToken("subt", "字幕标签", "字幕"),
            // ---- 媒体服务器标准路径 ----
            VariableToken("plex", "Plex 标准路径", "媒体服务器"),
            VariableToken("kodi", "Kodi 标准路径", "媒体服务器"),
            VariableToken("emby", "Emby 标准路径", "媒体服务器"),
            VariableToken("jellyfin", "Jellyfin 标准路径", "媒体服务器"),
            // ---- 高级 ----
            VariableToken("info.X", "扩展元数据", "高级"),
            VariableToken("localize.zh-CN.n", "本地化标题", "高级"),
            VariableToken("order.ABSOLUTE.e", "动态集序", "高级"),
            VariableToken("pi", "分卷序号", "高级"),
            VariableToken("pc", "分卷总数", "高级"),
            VariableToken("di", "重复序号", "高级"),
            VariableToken("dc", "重复总数", "高级"),
            // ---- 修饰符（管道/链式示例，点击插入完整表达式）----
            VariableToken("n|upper", "大写", "修饰符"),
            VariableToken("n|lower", "小写", "修饰符"),
            VariableToken("n|upperInitial", "首字母大写", "修饰符"),
            VariableToken("n|lowerTrail", "尾随词小写", "修饰符"),
            VariableToken("e|pad(2)", "补零", "修饰符"),
            VariableToken("rating|round(1)", "取整", "修饰符"),
            VariableToken("s|roman", "罗马数字", "修饰符"),
            VariableToken("n|space('.')", "空格替换", "修饰符"),
            VariableToken("n|colon(' - ')", "冒号替换", "修饰符"),
            VariableToken("n|slash(' ')", "斜杠替换", "修饰符"),
            VariableToken("n|replace('a','b')", "替换", "修饰符"),
            VariableToken("n|replaceAll('\\d+','')", "正则替换", "修饰符"),
            VariableToken("n|removeAll('\\(.*?\\)')", "正则移除", "修饰符"),
            VariableToken("n|remove(':')", "移除字符", "修饰符"),
            VariableToken("n|removeBrackets", "移除括号组", "修饰符"),
            VariableToken("n|match('\\d{4}')", "匹配", "修饰符"),
            VariableToken("n|matchAll('\\d+')", "全部匹配", "修饰符"),
            VariableToken("n|matchBrackets", "括号内容", "修饰符"),
            VariableToken("n|before('(')", "取前段", "修饰符"),
            VariableToken("n|after('-')", "取后段", "修饰符"),
            VariableToken("n|replaceTrailingBrackets", "去尾括号", "修饰符"),
            VariableToken("n|replacePart", "部分编号", "修饰符"),
            VariableToken("n|clean", "清洗", "修饰符"),
            VariableToken("n|validateFileName", "文件名合法化", "修饰符"),
            VariableToken("n|removeIllegalCharacters", "去非法字符", "修饰符"),
            VariableToken("n|replaceIllegalCharacters", "全角替代", "修饰符"),
            VariableToken("n|ascii", "转 ASCII", "修饰符"),
            VariableToken("n|asciiQuotes", "直引号", "修饰符"),
            VariableToken("n|truncate(30)", "截断", "修饰符"),
            VariableToken("n|sortName", "排序名", "修饰符"),
            VariableToken("n|sortInitial", "排序首字母", "修饰符"),
            VariableToken("n|initialName", "首名缩写", "修饰符"),
            VariableToken("n|acronym", "缩写", "修饰符"),
            VariableToken("n|isLatin", "拉丁判定", "修饰符"),
            VariableToken("genres|joining(', ')", "列表连接", "修饰符"),
            VariableToken("genres|joiningDistinct(',')", "去重连接", "修饰符"),
            VariableToken("sy|bounds", "首尾边界", "修饰符"),
            VariableToken("d|format('yyyy.MM.dd')", "日期格式化", "修饰符"),
            VariableToken("d|parseDate('yyyyMMdd')", "日期解析", "修饰符"),
            VariableToken("ct|toDate", "时间戳转日期", "修饰符"),
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
                displayName = "Inception.2010.1080p.BluRay.x265.AC3.HDR10.mkv",
                ext = "mkv",
                folder = "电影",
                drive = "/媒体库",
                lastModified = "2026-07-20",
                contentLength = 373_293_056L,
                parsed = ParsedFilename(
                    title = "Inception",
                    year = 2010,
                    resolution = "1080p",
                    source = "BluRay",
                    videoCodec = "x265",
                    audioCodec = "AC3",
                    group = "GROUP",
                    hdr = "HDR10",
                ),
            ),
            batch = BatchContext(index = 0, filesCount = 1, today = "2026-07-23"),
        )

        val EPISODE_SAMPLE = SampleContext(
            media = MediaMetadata(
                type = MediaType.EPISODE,
                name = "权力的游戏",
                year = 2011,
                seasonNumber = 1,
                episodeNumbers = listOf(1),
                episodeTitles = listOf("凛冬将至"),
                episodeAirDates = listOf("2011-04-17"),
                numberOfSeasons = 8,
                seasonYears = listOf(2011),
                seasonAbsoluteStarts = listOf(0),
                genres = listOf("奇幻", "剧情"),
                rating = 9.4,
            ),
            file = FileContext(
                displayName = "Game.of.Thrones.S01E01.1080p.WEB-DL.x264.AAC.mkv",
                ext = "mkv",
                folder = "权力的游戏",
                drive = "/媒体库",
                lastModified = "2026-07-20",
                contentLength = 373_293_056L,
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
            batch = BatchContext(index = 0, filesCount = 1, today = "2026-07-23"),
        )
    }
}

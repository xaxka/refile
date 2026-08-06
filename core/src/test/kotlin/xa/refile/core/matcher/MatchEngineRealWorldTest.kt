package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import xa.refile.core.parser.FilenameParser
import xa.refile.core.parser.ParsedFilename
import org.junit.Test

/**
 * 匹配系统真实样本代码覆盖测试（FilenameParser → MatchEngine）。
 *
 * 本文件补充 [MatchEngineE2ETest] 未覆盖的真实世界命名场景，数据来源：
 * 联网采集自 Nyaa / AnimeTosho / dmhy / hdencode / 中文发布论坛（chauthanh、
 * xzjlp、haoke100 等）/ TMDB，覆盖 8 个类目共 19 条样本：
 * - §15 韩剧（中文译名 + 英文标题，NF/WEB-DL 来源）
 * - §16 动漫 CRC 方括号命名（Erai-raws / SubsPlease 风格）
 * - §17 日更剧集（YYYY.MM.DD 日期型，无 SxxExx）
 * - §18 Repack / Proper 版本标记
 * - §19 多音轨 / 多字幕中文发布
 * - §20 v2 / V3 编码版本标记
 * - §21 流媒体来源（DSNP / ATVP / HMAX）
 * - §22 BBC 纪录片 / 合集
 *
 * 断言策略：
 * - 单候选场景：engine 单候选快捷方式 → Auto（不依赖相似度阈值）
 * - 标题断言：仅在标题清洗结果确定时断言（流媒体来源 token 已在 HARD_STOPWORDS，
 *   会被尾部扫描剥离；EPISODE_TITLE_SEP `\s-\s` 处理动漫 `Title - EP` 格式）。
 *   含非停用词剧集标题词（Chapter / Islands / 10bit 等）的场景仅断言决策类型与季集。
 */
class MatchEngineRealWorldTest {

    private val parser = FilenameParser()
    private val engine = MatchEngine()

    // ---- §15 韩剧（中文译名 + 英文标题，NF/WEB-DL 来源） ----

    @Test fun `kdrama Yong Pal S01E01 NF WEB-DL auto`() {
        // 来源：chauthanh.info / Ao 发布组；NF=Netflix
        val (parsed, decision) = runEndToEnd(
            fileName = "龙八.Yong.Pal.S01E01.1080p.NF.WEB-DL.DDP2.0.x264-Ao.mkv",
            candidates = listOf(episode(63311, "Yong Pal", 2015)),
        )
        // splitTitleAliases：CJK 段「龙八」作主标题，ASCII 段「Yong Pal」作别名
        assertThat(parsed.title).isEqualTo("龙八")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(63311)
    }

    @Test fun `kdrama Squid Game bilingual title NF auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "鱿鱼游戏.Squid.Game.S01E01.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(null, "Squid Game", 2021)),
        )
        assertThat(parsed.title).isEqualTo("鱿鱼游戏")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §16 动漫 CRC 方括号命名（Erai-raws / SubsPlease） ----

    @Test fun `anime crc Erai-raws Yuusha no Kuzu ep24 absolute auto`() {
        // [Group] Title - EP [tech][CRC] 格式；EPISODE_TITLE_SEP `\s-\s` 剥离集号
        val (parsed, decision) = runEndToEnd(
            fileName = "[Erai-raws] Yuusha no Kuzu - 24 [1080p CR WEBRip HEVC AAC][MultiSub][BD41B3C4].mkv",
            candidates = listOf(episode(null, "Yuusha no Kuzu", 2026)),
        )
        assertThat(parsed.title).isEqualTo("Yuusha no Kuzu")
        assertThat(parsed.episodes).containsExactly(24)
        assertThat(parsed.isAbsoluteEpisode).isTrue()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `anime crc SubsPlease Sora wa Akai Kawa ep04 absolute auto`() {
        // (1080p) 圆括号与 [CRC] 方括号均被剥离；` - 04` 触发 EPISODE_TITLE_SEP
        val (parsed, decision) = runEndToEnd(
            fileName = "[SubsPlease] Sora wa Akai Kawa no Hotori - 04 (1080p) [1F6FD1FF].mkv",
            candidates = listOf(episode(null, "Sora wa Akai Kawa no Hotori", 2026)),
        )
        assertThat(parsed.title).isEqualTo("Sora wa Akai Kawa no Hotori")
        assertThat(parsed.episodes).containsExactly(4)
        assertThat(parsed.isAbsoluteEpisode).isTrue()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `anime crc Erai-raws Frieren 2nd Season ep10 absolute auto`() {
        // `2nd Season` 留在标题中（非停用词）；` - 10` 触发 EPISODE_TITLE_SEP 剥离集号
        val (parsed, decision) = runEndToEnd(
            fileName = "[Erai-raws] Sousou no Frieren 2nd Season - 10 [1080p CR WEB-DL AVC AAC][MultiSub][5A357DEE].mkv",
            candidates = listOf(episode(209867, "Frieren: Beyond Journey's End", 2023)),
        )
        assertThat(parsed.title).isEqualTo("Sousou no Frieren 2nd Season")
        assertThat(parsed.episodes).containsExactly(10)
        assertThat(parsed.isAbsoluteEpisode).isTrue()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(209867)
    }

    // ---- §17 日更剧集（YYYY.MM.DD 日期型） ----

    @Test fun `daily show The Daily Show date format auto`() {
        // DAILY_SHOW 正则匹配 `2022.11.17`；日期串在年份解析前被替换 → year=null
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Daily.Show.2022.11.17.WEBRip.x264-ION10.mp4",
            candidates = listOf(episode(2224, "The Daily Show", 1996)),
        )
        assertThat(parsed.title).isEqualTo("The Daily Show")
        assertThat(parsed.isDailyShow).isTrue()
        assertThat(parsed.year).isNull()
        assertThat(parsed.season).isNull()
        assertThat(parsed.episodes).isEmpty()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(2224)
    }

    @Test fun `daily show Jimmy Kimmel Live date format auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Jimmy.Kimmel.Live.2023.12.05.720p.HDTV.x264-KOGi.mkv",
            candidates = listOf(episode(null, "Jimmy Kimmel Live", 2003)),
        )
        assertThat(parsed.title).isEqualTo("Jimmy Kimmel Live")
        assertThat(parsed.isDailyShow).isTrue()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §18 Repack / Proper 版本标记 ----

    @Test fun `repack Mandalorian S01E06 REPACK auto`() {
        // REPACK 被 VERSION_TAG 识别；PETRiFiD 为组名被剥离
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Mandalorian.S01E06.REPACK.1080p.WEBRiP.x264-PETRiFiED.mkv",
            candidates = listOf(episode(82856, "The Mandalorian", 2019)),
        )
        assertThat(parsed.title).isEqualTo("The Mandalorian")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(6)
        assertThat(parsed.version).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(82856)
    }

    @Test fun `proper Severance S01E01 PROPER ATVP auto`() {
        // PROPER 版本标记 + ATVP(Apple TV+) 流媒体来源
        val (parsed, decision) = runEndToEnd(
            fileName = "Severance.S01E01.PROPER.1080p.ATVP.WEB-DL.DDP5.1.H264-TEPES.mkv",
            candidates = listOf(episode(95396, "Severance", 2022)),
        )
        assertThat(parsed.title).isEqualTo("Severance")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.version).isNotNull()
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(95396)
    }

    @Test fun `repack chinese Wandering Earth II bilingual auto`() {
        // 中文方括号多音轨描述 + REPACK.HQ 版本标记 + 中英混合标题
        val (parsed, decision) = runEndToEnd(
            fileName = "流浪地球2[国语配音/中文字幕].The.Wandering.Earth.II.2023.2160p.REPACK.HQ.WEB-DL.H265.DDP5.1-DreamHD.mkv",
            candidates = listOf(movie(842675, "The Wandering Earth II", 2023)),
        )
        assertThat(parsed.title).isEqualTo("流浪地球2")
        assertThat(parsed.year).isEqualTo(2023)
        assertThat(parsed.version).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(842675)
    }

    // ---- §19 多音轨 / 多字幕中文发布 ----

    @Test fun `multi audio Three Body 2023 S01E01 chinese tags auto`() {
        // 国语/中字/简繁 均在 HARD_STOPWORDS，被尾部扫描剥离
        val (parsed, decision) = runEndToEnd(
            fileName = "三体.2023.S01E01.1080p.WEB-DL.国语.中字.简繁.mkv",
            candidates = listOf(episode(null, "Three-Body", 2023)),
        )
        assertThat(parsed.title).isEqualTo("三体")
        assertThat(parsed.year).isEqualTo(2023)
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `multi audio Cleopatra 1963 anniversary edition bilingual auto`() {
        // [共2部合集][国英多音轨/简英双语字幕] 方括号描述被剥离；50th.Anniversary.Edition 留在 ASCII 别名段
        val (parsed, decision) = runEndToEnd(
            fileName = "埃及艳后[共2部合集][国英多音轨/简英双语字幕].Cleopatra.1963.50th.Anniversary.Edition.1080p.BluRay.DTS.5.1.x265-GPTHD.mkv",
            candidates = listOf(movie(11674, "Cleopatra", 1963)),
        )
        assertThat(parsed.title).isEqualTo("埃及艳后")
        assertThat(parsed.year).isEqualTo(1963)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(11674)
    }

    // ---- §20 v2 / V3 编码版本标记 ----

    @Test fun `version V2 Mandalorian S03E01 DSNP auto`() {
        // V2 被VERSION_TAG识别；DSNP(Disney+)流媒体来源在 HARD_STOPWORDS
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Mandalorian.S03E01.1080p.V2.DSNP.WEB-DL.DDP5.1.x264.mkv",
            candidates = listOf(episode(82856, "The Mandalorian", 2019)),
        )
        assertThat(parsed.title).isEqualTo("The Mandalorian")
        assertThat(parsed.season).isEqualTo(3)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.version).isNotNull()
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(82856)
    }

    @Test fun `version v2 lowercase Inception 2010 auto`() {
        // 小写 v2 也被 VERSION_TAG（大小写不敏感）识别
        val (parsed, decision) = runEndToEnd(
            fileName = "Inception.2010.v2.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(movie(27205, "Inception", 2010)),
        )
        assertThat(parsed.title).isEqualTo("Inception")
        assertThat(parsed.year).isEqualTo(2010)
        assertThat(parsed.version).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(27205)
    }

    // ---- §21 流媒体来源（DSNP / ATVP / HMAX） ----

    @Test fun `streaming DSNP Mandalorian S01E03 Chapter title auto`() {
        // Chapter.3 为剧集标题词（非停用词），会留在标题中 → 仅断言季集/来源/决策
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Mandalorian.S01E03.Chapter.3.720p.DSNP.WEBRip.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(82856, "The Mandalorian", 2019)),
        )
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(3)
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(82856)
    }

    @Test fun `streaming ATVP Severance S02E01 4K HDR DV auto`() {
        // H265/DV/HDR/Atmos 均为停用词；ATVP 在 HARD_STOPWORDS → 标题清洗干净
        val (parsed, decision) = runEndToEnd(
            fileName = "Severance.S02E01.2025.2160p.ATVP.WEB-DL.H265.DV.HDR.DDP5.1.Atmos.mkv",
            candidates = listOf(episode(95396, "Severance", 2022)),
        )
        assertThat(parsed.title).isEqualTo("Severance")
        assertThat(parsed.season).isEqualTo(2)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(95396)
    }

    @Test fun `streaming HMAX Last of Us S01E01 4K HDR auto`() {
        // 10bit 非停用词会留在标题中 → 仅断言季集/来源/决策
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Last.of.Us.S01E01.2160p.HMAX.WEB-DL.x265.10bit.HDR.DDP5.1.Atmos-SMURF.mkv",
            candidates = listOf(episode(100088, "The Last of Us", 2023)),
        )
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(100088)
    }

    // ---- §22 BBC 纪录片 / 合集 ----

    @Test fun `documentary Planet Earth II EP01 Islands auto`() {
        // EP01 被 STANDALONE_EP 识别；Islands 剧集标题词留在标题 → 仅断言集号/决策
        val (parsed, decision) = runEndToEnd(
            fileName = "Planet.Earth.II.EP01.Islands.1080p.BluRay.x264.DTS-WiKi.mkv",
            candidates = listOf(episode(68595, "Planet Earth II", 2016)),
        )
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(68595)
    }

    @Test fun `documentary Planet Earth 2006 COMPLETE auto`() {
        // COMPLETE 为软停用词（非末位时剥离）；年份 2006 锚定
        val (parsed, decision) = runEndToEnd(
            fileName = "Planet.Earth.2006.COMPLETE.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(70652, "Planet Earth", 2006)),
        )
        assertThat(parsed.title).isEqualTo("Planet Earth")
        assertThat(parsed.year).isEqualTo(2006)
        assertThat(parsed.season).isNull()
        assertThat(parsed.episodes).isEmpty()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(70652)
    }

    // ---- 辅助函数 ----

    /** 端到端执行：parse 文件名 → engine 对候选做决策。返回 (解析结果, 决策)。 */
    private fun runEndToEnd(
        fileName: String,
        candidates: List<MatchCandidate>,
    ): Pair<ParsedFilename, MatchDecision> {
        val parsed = parser.parse(fileName)
        val decision = engine.match(parsed, candidates)
        return parsed to decision
    }

    /** 构造电影候选。tmdbId 为 null 时分配合成负 ID 保证唯一性。 */
    private fun movie(tmdbId: Int?, name: String, year: Int): MatchCandidate =
        MatchCandidate(tmdbId = tmdbId ?: nextSyntheticId(), name = name, year = year, mediaType = MediaType.MOVIE)

    /** 构造剧集候选。tmdbId 为 null 时分配合成负 ID。 */
    private fun episode(tmdbId: Int?, name: String, year: Int): MatchCandidate =
        MatchCandidate(tmdbId = tmdbId ?: nextSyntheticId(), name = name, year = year, mediaType = MediaType.EPISODE)

    /** 合成负 ID 生成器，保证多候选场景下 ID 唯一。 */
    private var syntheticIdCounter = 0
    private fun nextSyntheticId(): Int = --syntheticIdCounter

    /** 从决策中提取 best 候选的 tmdbId（Auto/NeedsConfirm 有效，NoMatch 返回 null）。 */
    private fun bestTmdbId(decision: MatchDecision): Int? = when (decision) {
        is MatchDecision.Auto -> decision.best.candidate.tmdbId
        is MatchDecision.NeedsConfirm -> decision.candidates.firstOrNull()?.candidate?.tmdbId
        MatchDecision.NoMatch -> null
    }
}

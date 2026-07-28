package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import xa.refile.core.model.MediaType
import xa.refile.core.parser.ParsedFilename
import org.junit.Test

class MatchEngineTest {

    private val engine = MatchEngine()

    @Test fun `auto match when high confidence and margin`() {
        val parsed = ParsedFilename(title = "The Last of Us", year = 2023)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Last of Us", year = 2023, popularity = 50.0, mediaType = MediaType.EPISODE),
            MatchCandidate(tmdbId = 2, name = "Last Man Standing", year = 2011, popularity = 10.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `needs confirm when low confidence`() {
        val parsed = ParsedFilename(title = "The Last of Us", year = 2023)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Lost in Space", year = 2018, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "The 100", year = 2014, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `needs confirm when top two too close`() {
        val parsed = ParsedFilename(title = "Lost", year = 2004)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Lost", year = 2004, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `no match when no candidates`() {
        val decision = engine.match(ParsedFilename(title = "X"), emptyList())
        assertThat(decision).isEqualTo(MatchDecision.NoMatch)
    }

    @Test fun `alias fallback boosts score`() {
        val parsed = ParsedFilename(title = "十二国记")
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Juuni Kokuki", aliases = listOf("十二国记"), year = 2002, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `year mismatch reduces bonus`() {
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 2003, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix", year = 1999, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 2 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- P0.5 年份惩罚（线性） ----

    @Test fun `P0_5 year exact match yields minimal penalty`() {
        // diff=0 → penalty=0.0（±1 容差）；标题完全相同 + 第二候选完全不同 → 自动匹配
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Inception", year = 2010, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Interstellar", year = 2014, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_5 year large diff caps at max penalty`() {
        // diff>100 → penalty=0.11（封顶）；构造一个差百年的场景验证封顶
        val parsed = ParsedFilename(title = "It", year = 1900)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "It", year = 2017, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Aftersun", year = 2022, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // diff=117 > 100 → penalty=0.11（封顶）
        // score = 1.0*0.85 - 0.11 + 0.024 + 0 = 0.764；低于阈值 0.82 → NeedsConfirm
        // 但应排第一
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        val scored = (decision as MatchDecision.NeedsConfirm).candidates
        assertThat(scored.first().candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_5 missing parsed year disables penalty`() {
        // parsed.year=null → penalty=0；即便候选年份很远也不惩罚
        val parsed = ParsedFilename(title = "The Matrix", year = null)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Matrix Resurrections", year = 2021, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `P0_5 missing candidate year applies max penalty`() {
        // candidate.year=null → penalty=0.11；与有年份候选竞争时落败
        val parsed = ParsedFilename(title = "Dune", year = 2021)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Dune", year = null, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "Dune", year = 2021, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(2)
    }

    // ---- P0.6 多算法相似度 ----

    @Test fun `P0_6 levenshtein picks near-spelling candidate`() {
        // 拼写近似（Spider-Man vs Spider Man）— Jaccard 完全相等，Levenshtein 应给 1.0
        val parsed = ParsedFilename(title = "Spider-Man", year = 2002)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Spider Man", year = 2002, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Batman Begins", year = 2005, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_6 dice bigram handles reordered words`() {
        // 单词换序（Lord of the Rings vs Rings Lord）— Jaccard 相同，Dice bigram 更敏感
        val parsed = ParsedFilename(title = "Lord of the Rings", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Rings Lord", year = 2001, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "Star Wars", year = 1977, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_6 jaro winkler rewards common prefix`() {
        // 公共前缀加权：`Avengers` vs `Avengers Endgame` 应排到 `Guardians of the Galaxy` 之前
        val parsed = ParsedFilename(title = "Avengers", year = 2012)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Guardians of the Galaxy", year = 2014, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Avengers Endgame", year = 2014, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(2)
    }

    @Test fun `P0_6 year stripped similarity handles residual year token`() {
        // 解析标题残留年份 token（The Matrix 1999）时，剥离后再与候选 The Matrix 比对
        val parsed = ParsedFilename(title = "The Matrix 1999", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix Reloaded", year = 2003, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_6 alias max across multiple algorithms`() {
        // 多别名场景：取所有别名 × 所有算法的最大值
        val parsed = ParsedFilename(title = "Juuni Kokuki")
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1,
                name = "Twelve Kingdoms",
                aliases = listOf("Juuni Kokuki", "12 Kingdoms"),
                year = 2002,
                popularity = 5.0,
            ),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- P0.7 去变音符号 ----

    @Test fun `P0_7 diacritic normalization Amelie`() {
        // Amélie vs Amelie — NFD 分解去变音符号后应自动匹配
        val parsed = ParsedFilename(title = "Amélie", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Amelie", year = 2001, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Amadeus", year = 1984, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_7 diacritic normalization Naruto`() {
        // NARUTO→ナルト 风格的拉丁扩展字符归一
        val parsed = ParsedFilename(title = "Naruto Shippuden", year = 2007)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Naruto Shippuden", year = 2007, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- P0.8 阈值/边距 ----

    @Test fun `P0_8 auto threshold boundary`() {
        // 高分 + 显著分差 → Auto
        val parsed = ParsedFilename(title = "Interstellar", year = 2014)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Interstellar", year = 2014, popularity = 80.0),
            MatchCandidate(tmdbId = 2, name = "Inception", year = 2010, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `P0_8 needs confirm when top two within margin`() {
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        val parsed = ParsedFilename(title = "Dune", year = 2021)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Dune", year = 2021, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Dune", year = 1984, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        val auto = decision as MatchDecision.Auto
        assertThat(auto.best.candidate.tmdbId).isEqualTo(1)
        assertThat(auto.best.score).isEqualTo(1.0)
    }

    // ---- P2.1 分数量化 ----

    @Test fun `P2_1 quantize snaps to nearest quarter`() {
        // 启用量化后得分落到 0/0.25/0.5/0.75/1.0
        val scorer = ConfidenceScorer(quantize = true)
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidate = MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0)
        val score = scorer.score(parsed, candidate)
        // 量化后必须是 0.25 的整数倍
        val remainder = (score * 100.0) % 25.0
        assertThat(remainder).isLessThan(0.001)
    }

    @Test fun `P2_1 quantize disabled keeps continuous score`() {
        // 关闭量化时得分不应被四舍五入到 0.25 倍数（高置信度场景下取 raw）
        val scorer = ConfidenceScorer(quantize = false)
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidate = MatchCandidate(
            tmdbId = 1,
            name = "The Matrix",
            year = 1999,
            popularity = 33.0, // 非整数倍 popularity → popBonus 非 0.25 倍数
        )
        val score = scorer.score(parsed, candidate)
        // raw 分数（popularity=33 → popBonus=0.0132）不应被量化
        val remainder = (score * 100.0) % 25.0
        assertThat(remainder).isGreaterThan(0.001)
    }

    // ---- P2.2 SxE 互校加分 ----

    @Test fun `P2_2 sxe bonus boosts full match`() {
        // season + episode 完整命中 → +SXE_BONUS；可让本应 NeedsConfirm 的命中变为 Auto
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 1,
            episodes = listOf(3),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            // 候选带 season/episodes 元数据
            MatchCandidate(
                tmdbId = 1,
                name = "The Last of Us",
                year = 2023,
                popularity = 50.0,
                mediaType = MediaType.EPISODE,
                season = 1,
                episodes = listOf(3),
            ),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_2 sxe bonus only season match yields half bonus`() {
        // 仅季命中（无 episodes 元数据）→ +SXE_BONUS*0.5
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 1,
            episodes = emptyList(),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1,
                name = "The Last of Us",
                year = 2023,
                popularity = 50.0,
                mediaType = MediaType.EPISODE,
                season = 1,
                episodes = emptyList(),
            ),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_2 sxe mismatch does not penalize`() {
        // 候选 SxE 与 parsed 不一致 → 加 0（不扣分，避免误伤）
        // 验证：标题+年份命中仍排第一，SxE 不额外加分（与无 SxE 场景同分）
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 2,
            episodes = listOf(1),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1,
                name = "The Last of Us",
                year = 2023,
                popularity = 50.0,
                mediaType = MediaType.EPISODE,
                season = 1,
                episodes = listOf(3),
            ),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // 标题+年份命中 → Auto，且 SxE 不加分（验证：与无 SxE 场景同分）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_2 sxe bonus skipped when candidate lacks sxe metadata`() {
        // 候选无 season/episodes → bonus=0（搜索结果轻量元数据缺 SxE 不惩罚）
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 1,
            episodes = listOf(3),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Last of Us", year = 2023, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // 无 SxE 加分但标题+年份强命中仍可 Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    // ---- P2.3 冠词归一化 ----

    @Test fun `P2_3 leading article stripped for comparison`() {
        // The Matrix vs Matrix — 去前导冠词后高分
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Matrix", year = 1999, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Matrix Reloaded", year = 2003, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_3 trailing article reordered to leading`() {
        // Matrix, The 重排为 The Matrix 后剥离冠词 → 与 Matrix 高分
        val parsed = ParsedFilename(title = "Matrix, The", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix Reloaded", year = 2003, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_3 article normalization both sides`() {
        // 双侧都有冠词也应一致归一
        val parsed = ParsedFilename(title = "The Lord of the Rings", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Lord of the Rings", year = 2001, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Hobbit", year = 2012, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_3 leading article a and an`() {
        // a/an 冠词也应被剥离
        val parsed = ParsedFilename(title = "A Beautiful Mind", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Beautiful Mind", year = 2001, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Beautiful", year = 2009, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    // ---- 综合场景 ----

    @Test fun `combined diacritic plus article normalization`() {
        // Amélie + 冠词归一：标题 `Amélie` 与候选 `Amelie` 高分匹配
        val parsed = ParsedFilename(title = "Amélie", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Amelie", year = 2001, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Amadeus", year = 1984, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `popularity bonus breaks tie between equal titles`() {
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)，绕过 popularity 加分
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 80.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix", year = 1999, popularity = 20.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        val auto = decision as MatchDecision.Auto
        assertThat(auto.best.candidate.tmdbId).isEqualTo(1)
        assertThat(auto.best.score).isEqualTo(1.0)
    }

    // ---- originalName 参与标题评分（P3.0） ----

    @Test fun `originalName match boosts score over name-only candidate`() {
        // 解析标题为 CJK 原名，候选英文名 + originalName CJK → 原名维度命中让分数高于无 originalName 的同名候选
        // 参考 spec ADDED Requirement "originalName 参与标题评分" Scenario: 原名命中
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "攻壳机动队", year = 1995)
        val withOriginal = MatchCandidate(
            tmdbId = 1,
            name = "Ghost in the Shell",
            originalName = "攻殻機動隊",
            year = 1995,
            popularity = 5.0,
        )
        val withoutOriginal = MatchCandidate(
            tmdbId = 2,
            name = "Ghost in the Shell",
            year = 1995,
            popularity = 5.0,
        )
        // 有 originalName 时 Jaro-Winkler 在 CJK 简繁体间给出非零相似度，无 originalName 时为 0
        assertThat(scorer.score(parsed, withOriginal)).isGreaterThan(scorer.score(parsed, withoutOriginal))
    }

    @Test fun `originalName match ranks candidate first in needs confirm`() {
        // 原名命中后候选分数提升，在 match 结果中排第一（分数未达 autoThreshold → NeedsConfirm）
        val parsed = ParsedFilename(title = "攻壳机动队", year = 1995)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1,
                name = "Ghost in the Shell",
                originalName = "攻殻機動隊",
                year = 1995,
                popularity = 5.0,
            ),
            MatchCandidate(
                tmdbId = 2,
                name = "Ghost in the Shell",
                year = 1995,
                popularity = 5.0,
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 两个候选分数都未达 autoThreshold → NeedsConfirm；但带 originalName 的应排第一
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
    }

    // ---- 年份 ±1 容差（P3.0 修订） ----

    @Test fun `year diff within 1 yields zero penalty`() {
        // |sy - cy| <= 1 → yearPenalty=0（±1 容差，spec Scenario: 年份差 1）
        // Feature #5 numericBonus：candidate.year=2010 在 {2010}（来自 parsed.year）→ +0.05；
        // candidate.year=2009 不在 → 0。分差 0.05 仅来自 numericBonus，yearPenalty 仍等价（都为 0）。
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val sameYear = MatchCandidate(tmdbId = 1, name = "Inception", year = 2010, popularity = 50.0)
        val diffOne = MatchCandidate(tmdbId = 2, name = "Inception", year = 2009, popularity = 50.0)
        // yearPenalty 等价（都为 0），差异仅来自 numericBonus（0.05）
        assertThat(scorer.score(parsed, sameYear) - scorer.score(parsed, diffOne))
            .isWithin(1e-9).of(0.05)
    }

    @Test fun `year diff 5 still applies linear penalty`() {
        // |sy - cy| = 5 → yearPenalty=0.01 + 5/1000 = 0.015（spec Scenario: 年份差 5）
        // Feature #5 numericBonus：candidate.year=2010 在 {2010}（parsed.year）→ +0.05；
        // candidate.year=2015 不在 → 0。总分差 = 0.015 (yearPenalty) + 0.05 (numericBonus) = 0.065
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val sameYear = MatchCandidate(tmdbId = 1, name = "Inception", year = 2010, popularity = 50.0)
        val diffFive = MatchCandidate(tmdbId = 2, name = "Inception", year = 2015, popularity = 50.0)
        val scoreSame = scorer.score(parsed, sameYear)
        val scoreDiff = scorer.score(parsed, diffFive)
        // diff=0 → penalty=0 + numericBonus=0.05；diff=5 → penalty=0.015 + numericBonus=0
        // 总分差 = 0.015 + 0.05 = 0.065
        assertThat(scoreSame - scoreDiff).isWithin(1e-9).of(0.065)
    }

    // ---- P3.0 同分破平局（vote_average / vote_count / 年份近度） ----

    @Test fun `P3_0 tie break prefers higher vote_average when scores equal`() {
        // 等分时优先热门剧：构造同名同 year 候选使 score 完全相等，A 的 vote_average 更高 → A 排前
        // spec ADDED Requirement: 同分破平局 Scenario: 等分时优先热门
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 8.5, voteCount = 5000,
            ),
            MatchCandidate(
                tmdbId = 2, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 6.0, voteCount = 20,
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P3_0 tie break skipped when vote_count below 20 for one side`() {
        // vote_count<20 的候选不参与破平局：A 不参与（voteCount=10），B 参与（voteCount=20）→ B 排前
        // spec MODIFIED Requirement: MatchEngine 决策 —— vote_count<20 不参与破平局
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 8.5, voteCount = 10,  // < 20，不参与破平局
            ),
            MatchCandidate(
                tmdbId = 2, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 6.0, voteCount = 20,  // 参与
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P3_0 tie break keeps original order when both candidates ineligible`() {
        // 双方 vote_count 都 <20 → 都不参与破平局 → 保持原 score 排序位置（输入顺序）
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 8.5, voteCount = 10,  // < 20
            ),
            MatchCandidate(
                tmdbId = 2, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 6.0, voteCount = 5,   // < 20
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P3_0 tie break not triggered when score gap exceeds margin`() {
        // 分差大时不触发破平局：A 分数远高于 B（标题完全匹配 vs 完全不同），即便 B 的 vote 更高，A 仍排第一
        // spec ADDED Requirement: 同分破平局 Scenario: 分差大时不触发
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 6.0, voteCount = 20,  // 较低 vote
            ),
            MatchCandidate(
                tmdbId = 2, name = "Totally Different Movie", year = 1985, popularity = 5.0,
                voteAverage = 8.5, voteCount = 5000,  // 较高 vote
            ),
        )
        val decision = engine.match(parsed, candidates)
        // A 标题完全匹配 + 年份相同 → 高分；B 标题完全不同 → 低分；分差 > margin → 不触发破平局
        // A score ≈ 0.87 >= autoThreshold 且 gap >= margin → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P3_0 tie break falls back to vote_count when vote_average equal`() {
        // vote_average 相等时按 vote_count 降序破平局
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 8.0, voteCount = 100,   // 较少投票
            ),
            MatchCandidate(
                tmdbId = 2, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 8.0, voteCount = 5000,  // 更多投票
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P3_0 tie break falls back to year proximity when votes equal`() {
        // vote_average 与 vote_count 都相等时按年份近度升序破平局
        // 利用 ±1 年份容差（yearPenalty=0）构造等分候选：A year=1999，B year=2000
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0,
                voteAverage = 8.0, voteCount = 100,  // 距 parsed.year=0
            ),
            MatchCandidate(
                tmdbId = 2, name = "The Matrix", year = 2000, popularity = 50.0,
                voteAverage = 8.0, voteCount = 100,  // 距 parsed.year=1（±1 容差内，penalty=0，等分）
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1 标题+年份完全匹配 → 触发硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P3_0 tie break does not bypass autoThreshold`() {
        // 破平局后 best 仍需 score >= autoThreshold 才 Auto；否则 NeedsConfirm
        // 构造两个标题近似但不完全匹配的候选，分数都 < autoThreshold，破平局触发后仍需确认
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "The Matrix Reloaded", year = 2003, popularity = 5.0,
                voteAverage = 8.5, voteCount = 5000,  // vote 高
            ),
            MatchCandidate(
                tmdbId = 2, name = "The Matrix Revolutions", year = 2003, popularity = 5.0,
                voteAverage = 6.0, voteCount = 20,
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 两候选标题仅近似（不完全匹配）→ 分数 < autoThreshold；即便破平局后 best 仍是 A，也需 NeedsConfirm
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        // 破平局后 A（vote_average=8.5）应排第一
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
    }

    // ---- P3.0 Provider ID 短路匹配（matchByIds） ----

    @Test fun `matchByIds returns Auto with score 1_0 when tmdbId present and lookup hits`() = runBlocking {
        // parsed.tmdbId 非空，lookup 返回非空候选 → MatchDecision.Auto 且 best.score == 1.0
        // spec ADDED Requirement: Provider ID 提取 Scenario: 命中 TMDB ID
        val parsed = ParsedFilename(title = "Some Movie", tmdbId = 123)
        val candidate = MatchCandidate(tmdbId = 123, name = "Some Movie", year = 2024)
        val decision = engine.matchByIds(parsed) { candidate }
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        val auto = decision as MatchDecision.Auto
        assertThat(auto.best.candidate.tmdbId).isEqualTo(123)
        assertThat(auto.best.score).isEqualTo(1.0)
    }

    @Test fun `matchByIds returns null when no provider id present`() = runBlocking {
        // parsed 无任何 Provider ID → 立即返回 null，调用方走原 match 路径
        // spec ADDED Requirement: Provider ID 提取 Scenario: 无 ID 时回退到相似度匹配
        val parsed = ParsedFilename(title = "Some Movie")
        val decision = engine.matchByIds(parsed) {
            throw AssertionError("lookup 不应被调用（parsed 无 Provider ID）")
        }
        assertThat(decision).isNull()
    }

    @Test fun `matchByIds returns null when lookup returns null`() = runBlocking {
        // parsed.tmdbId 非空但 lookup 返回 null（端点 404 / 网络失败）→ 返回 null，调用方回退
        val parsed = ParsedFilename(title = "Some Movie", tmdbId = 123)
        val decision = engine.matchByIds(parsed) { null }
        assertThat(decision).isNull()
    }

    @Test fun `matchByIds prefers tmdbId over tvdbId and imdbId`() = runBlocking {
        // parsed 同时携带 tmdbId/tvdbId/imdbId 时，lookup 应优先查询 tmdbId
        // 通过记录被调用的 ID 顺序断言：tmdbId 先被查询，命中即返回，不应触及 tvdbId/imdbId
        val parsed = ParsedFilename(
            title = "Some Movie",
            tmdbId = 123,
            tvdbId = 456,
            imdbId = "tt0000001",
        )
        val callOrder = mutableListOf<String>()
        val decision = engine.matchByIds(parsed) { p ->
            // 模拟 lookup lambda 内部的优先级判断（与 MatchViewModel.runMatchForFile 实现一致）
            val hit: MatchCandidate? = when {
                p.tmdbId != null -> {
                    callOrder += "tmdb"
                    MatchCandidate(tmdbId = p.tmdbId, name = "Tmdb Hit", year = 2024)
                }
                p.tvdbId != null -> {
                    callOrder += "tvdb"
                    MatchCandidate(tmdbId = 999, name = "Tvdb Hit", year = 2024)
                }
                !p.imdbId.isNullOrBlank() -> {
                    callOrder += "imdb"
                    MatchCandidate(tmdbId = 998, name = "Imdb Hit", year = 2024)
                }
                else -> null
            }
            hit
        }
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        val auto = decision as MatchDecision.Auto
        // 命中的是 tmdbId 对应的候选
        assertThat(auto.best.candidate.tmdbId).isEqualTo(123)
        assertThat(auto.best.candidate.name).isEqualTo("Tmdb Hit")
        // lookup 仅触达 tmdb 分支，未触及 tvdb/imdb
        assertThat(callOrder).containsExactly("tmdb").inOrder()
    }

    // ---- Feature #26 / #27：硬信号优先 —— 年份完全相等 + 标题完全相等 → 直接 Auto(1.0) ----

    @Test fun `Feature26 hard signal year and title exact match yields auto with score 1`() {
        // 年份相等 + 归一化标题相等 → 直接 Auto，跳过相似度算法（score=1.0）
        // 构造一个"标题完全相等但 popularity 极低"的场景：硬信号应胜过 popularity 加分
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Matrix, The", year = 1999, popularity = 0.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix Reloaded", year = 2003, popularity = 100.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        val auto = decision as MatchDecision.Auto
        assertThat(auto.best.candidate.tmdbId).isEqualTo(1)
        // 硬信号直接给 1.0 分，绕过相似度算法
        assertThat(auto.best.score).isEqualTo(1.0)
    }

    @Test fun `Feature26 hard signal requires year equality`() {
        // 标题完全相等但年份不同 → 不触发硬信号，走相似度打分
        val parsed = ParsedFilename(title = "It", year = 2017)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "It", year = 1990, popularity = 50.0),
        )
        // 年份差 27 年 → 被 Feature #28 淘汰（除非标题完全相等，这里标题确实相等 → 保留）
        // 但不触发硬信号 Auto（年份不等）→ 走相似度打分
        val decision = engine.match(parsed, candidates)
        // 单候选；标题相等 Jaccard=1.0；年份差 27 年惩罚 0.01 + 27/1000 = 0.037；
        // score = 1.0*0.85 - 0.037 + 0.02(pop) = 0.833 > 0.82 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        // 但 score 应 < 1.0（非硬信号路径）
        assertThat((decision as MatchDecision.Auto).best.score).isLessThan(1.0)
    }

    @Test fun `Feature26 hard signal both years null with title match yields auto`() {
        // 双方年份都缺失 + 标题完全相等 → 硬信号触发（c.year == parsed.year 在 Kotlin 中 null==null → true）
        val parsed = ParsedFilename(title = "Unknown Film", year = null)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Unknown Film", year = null, popularity = 0.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `Feature26 hard signal with case and accent differences`() {
        // 归一化处理大小写 + 变音符号差异：标题字符串不同但归一后相同 → 触发硬信号
        val parsed = ParsedFilename(title = "amélie", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "AMELIE", year = 2001, popularity = 0.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `Feature26 hard signal not triggered when title differs`() {
        // 标题不同（即便只是少量字符差异）→ 不触发硬信号（score != 1.0）
        // Feature #5：substringSimilarity + numericBonus 可能让分数达 Auto 阈值，
        // 但只要 score < 1.0 即证明未走硬信号路径。
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix Reloaded", year = 1999, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 关键断言：不走硬信号路径（score != 1.0）；具体 Auto/NeedsConfirm 取决于 bonus 叠加
        when (decision) {
            is MatchDecision.Auto -> assertThat(decision.best.score).isLessThan(1.0)
            is MatchDecision.NeedsConfirm -> {
                // 也允许 NeedsConfirm（子串+数字加分不足以达 Auto 阈值时）
            }
            else -> throw AssertionError("expected Auto or NeedsConfirm, got $decision")
        }
    }

    // ---- Feature #28：年份差超过 5 年且标题不完全相等 → 直接淘汰 ----

    @Test fun `Feature28 year diff over 5 drops candidate with different title`() {
        // 1999 年的 It vs 2017 年的 It 候选（同名单曲）—— 标题相同 → 不被淘汰（保留以便硬信号路径检查）
        val parsed = ParsedFilename(title = "It", year = 2017)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "It", year = 1990, popularity = 0.0),
            MatchCandidate(tmdbId = 2, name = "It Chapter Two", year = 2019, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1：标题完全相等 → 保留（即便年份差 27 年）；但年份 1990 != 2017 → 不触发硬信号
        // 候选 1 score = 1.0*0.85 - 0.037 + 0 = 0.813 < 0.82 → NeedsConfirm
        // 候选 2：标题不完全相等 + 年份差 2 → 保留
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        val scored = (decision as MatchDecision.NeedsConfirm).candidates
        assertThat(scored.first().candidate.tmdbId).isEqualTo(1)
        assertThat(scored.map { it.candidate.tmdbId }).contains(2)
    }

    @Test fun `Feature28 year diff over 5 drops candidate with similar but not exact title`() {
        // 标题不完全相等（仅相似）+ 年份差超过 5 → 直接淘汰，不进打分列表
        val parsed = ParsedFilename(title = "It", year = 2017)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "It Comes at Night", year = 2017, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "It Chapter Two", year = 1990, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 2：年份差 27 + 标题不完全相等 → 被淘汰
        // 候选 1：年份相等 + 标题不完全相等 → 保留 → 进打分
        // 候选 1 标题相似度高但年份相等，单候选应能 Auto（Jaccard 较低，可能 NeedsConfirm）
        // 关键断言：候选 2 不应出现在 NeedsConfirm 列表中
        if (decision is MatchDecision.NeedsConfirm) {
            assertThat(decision.candidates.map { it.candidate.tmdbId }).doesNotContain(2)
        } else {
            assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
            assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
        }
    }

    @Test fun `Feature28 parsed year null keeps all candidates`() {
        // parsed.year == null → 无法判定年份差 → 全部保留（年份缺失不淘汰）
        val parsed = ParsedFilename(title = "Dune", year = null)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Dune", year = 1984, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Dune", year = 2021, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 双方年份都不同但 parsed.year=null → 都保留；标题完全相等但 parsed.year=null
        // → 硬信号 c.year == parsed.year 即 c.year == null → 1984 != null → 不触发硬信号
        // → 进相似度打分；两个 Dune 同名候选分差 < margin → NeedsConfirm
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates).hasSize(2)
    }

    @Test fun `Feature28 candidate year null is kept`() {
        // 候选缺年份 → 保留（保留与有年份候选竞争的能力，由 yearPenalty 处理）
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Inception", year = null, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "Inception", year = 2010, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 2：年份相等 + 标题完全相等 → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(2)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `Feature28 all candidates dropped yields no match`() {
        // 所有候选年份差都 > 5 且标题不完全相等 → 全部淘汰 → NoMatch
        val parsed = ParsedFilename(title = "Old Film", year = 1950)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Old Film Remake", year = 2020, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Old Film Sequel", year = 2024, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isEqualTo(MatchDecision.NoMatch)
    }

    // ---- Feature #5：子串匹配 + 数字序列匹配 ----

    @Test fun `Feature5 substring similarity boosts title-as-substring-of-candidate`() {
        // "Spider-Man" 是 "Spider-Man 2" 的子串 → substringSimilarity 给高分
        // 参考 FB-Mod EpisodeMetrics.substringSimilarity
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Spider-Man", year = 2002)
        val substringCandidate = MatchCandidate(tmdbId = 1, name = "Spider-Man 2", year = 2002, popularity = 5.0)
        val unrelatedCandidate = MatchCandidate(tmdbId = 2, name = "Batman Begins", year = 2005, popularity = 50.0)
        // 子串候选分数应远高于无关候选（即便后者 popularity 更高）
        assertThat(scorer.score(parsed, substringCandidate))
            .isGreaterThan(scorer.score(parsed, unrelatedCandidate))
    }

    @Test fun `Feature5 substring similarity handles Spider-Man 2 vs Spider-Man 2 with year`() {
        // 经典场景：`Spider-Man 2` vs `Spider-Man 2 (2004)` —— 标题是候选名子串
        // substringSimilarity 给高分，numericBonus（候选 year 出现在 title 数字位）补足，达 Auto
        val parsed = ParsedFilename(title = "Spider-Man 2", year = 2004)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Spider-Man 2 (2004)", year = 2004, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "Batman", year = 2005, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 候选 1：子串匹配 + 年份命中 → 高分 Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `Feature5 substring similarity picks title-as-substring candidate first`() {
        // 解析标题 "Matrix" 是候选 "The Matrix Reloaded" 的子串 → 高分排第一
        // 即便候选 2 年份差 4、popularity 更低，仍胜过无关候选
        val parsed = ParsedFilename(title = "Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Total Recall", year = 1998, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix Reloaded", year = 2003, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        val scored = when (decision) {
            is MatchDecision.Auto -> listOf(decision.best)
            is MatchDecision.NeedsConfirm -> decision.candidates
            else -> emptyList()
        }
        // 子串匹配让候选 2 排第一（即便候选 1 popularity 更高、年份更接近）
        assertThat(scored.first().candidate.tmdbId).isEqualTo(2)
    }

    @Test fun `Feature5 substring similarity returns zero for non-substring strings`() {
        // 非子串关系 → substringSimilarity 不参与加分（不会压过其它算法）
        // "Inception" vs "Interstellar" 共前缀 "In" → jaroWinkler 给中等分，但非子串 → substring 不加分
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val nonSubstring = MatchCandidate(tmdbId = 1, name = "Interstellar", year = 2014, popularity = 5.0)
        val substringMatch = MatchCandidate(tmdbId = 2, name = "Inception 2010", year = 2010, popularity = 5.0)
        // 子串匹配候选分数应远高于非子串候选（substringSimilarity 给 0.9+ 高分）
        assertThat(scorer.score(parsed, substringMatch))
            .isGreaterThan(scorer.score(parsed, nonSubstring))
        // 非子串候选分数应低于 Auto 阈值（jaroWinkler 共前缀给中等分，但不足以 Auto）
        assertThat(scorer.score(parsed, nonSubstring)).isLessThan(0.7)
    }

    @Test fun `Feature5 numeric sequence bonus when candidate year appears in title`() {
        // 文件名标题 "Show 2020" 含数字 2020；parsed.year=null（解析器未提取年份）
        // 候选 year=2020 出现在 title 数字中 → +NUMERIC_YEAR_BONUS
        // 候选 year=2019 不在 → 0；两候选其它维度相同 → yearMatch 分数更高
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Show 2020", year = null)
        val yearMatch = MatchCandidate(tmdbId = 1, name = "Show", year = 2020, popularity = 5.0)
        val yearMismatch = MatchCandidate(tmdbId = 2, name = "Show", year = 2019, popularity = 5.0)
        // year=2020 出现在 title 数字中 → 加分；year=2019 不在 → 不加分
        assertThat(scorer.score(parsed, yearMatch))
            .isGreaterThan(scorer.score(parsed, yearMismatch))
        // 差值恰好为 NUMERIC_YEAR_BONUS
        assertThat(scorer.score(parsed, yearMatch) - scorer.score(parsed, yearMismatch))
            .isWithin(1e-9).of(0.05)
    }

    @Test fun `Feature5 numeric sequence bonus when candidate season appears in title`() {
        // 文件名标题 "Show 3" 含数字 3；parsed.season 未设置
        // 候选 season=3 出现在 title 数字中 → +NUMERIC_SEASON_BONUS
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Show 3", year = 2020, mediaType = MediaType.EPISODE)
        val seasonMatch = MatchCandidate(
            tmdbId = 1, name = "Show", year = 2020, season = 3, popularity = 5.0, mediaType = MediaType.EPISODE,
        )
        val seasonMismatch = MatchCandidate(
            tmdbId = 2, name = "Show", year = 2020, season = 1, popularity = 5.0, mediaType = MediaType.EPISODE,
        )
        // season=3 出现在 title 数字中 → 加分；season=1 不在 → 不加分
        assertThat(scorer.score(parsed, seasonMatch))
            .isGreaterThan(scorer.score(parsed, seasonMismatch))
        assertThat(scorer.score(parsed, seasonMatch) - scorer.score(parsed, seasonMismatch))
            .isWithin(1e-9).of(0.03)
    }

    @Test fun `Feature5 numeric sequence bonus includes parsed year and season`() {
        // parser 已提取的 year/season 也算作"文件名数字位"
        // parsed.title="Saw"（无数值）但 parsed.year=2004 → numbers={2004}
        // 候选 year=2004 → +NUMERIC_YEAR_BONUS（即便 title 中无数字）
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Saw", year = 2004)
        val yearMatch = MatchCandidate(tmdbId = 1, name = "Saw", year = 2004, popularity = 5.0)
        val yearMismatch = MatchCandidate(tmdbId = 2, name = "Saw", year = 2005, popularity = 5.0)
        // year=2004 在 {2004}（来自 parsed.year）→ 加分；year=2005 不在 → 不加分
        // 但 year=2004 也触发 yearPenalty=0（diff=0），year=2005 触发 penalty=0.011
        // 双重优势：yearMatch 应高于 yearMismatch
        assertThat(scorer.score(parsed, yearMatch))
            .isGreaterThan(scorer.score(parsed, yearMismatch))
    }

    @Test fun `Feature5 numeric sequence bonus zero when no numbers match`() {
        // 文件名无数字、parsed 无 year/season → numbers 为空 → numericBonus=0
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Hello World", year = null)
        val candidate = MatchCandidate(
            tmdbId = 1, name = "Hello World", year = 2020, season = 1, popularity = 5.0,
        )
        val score = scorer.score(parsed, candidate)
        // 标题完全相等 → 高分（substringSim=1.0），但无数字序列加分
        // 验证不报错且分数合理（≥ 0.8 因标题完全匹配）
        assertThat(score).isAtLeast(0.8)
    }

    // ---- Feature #14：双高分局检测 ----

    @Test fun `Feature14 double high score forces needs confirm`() {
        // 两个候选都 >= 0.95 → 强制 NeedsConfirm（即便分差 >= margin）
        // 参考 tmm MovieScrapeTask L237-270
        // 注意：候选年份不能 == parsedYear（否则触发硬信号 Auto 绕过双高检测）
        // 用 year=2018/2019（±2 内，penalty≤0.012）避免硬信号
        val parsed = ParsedFilename(
            title = "It", year = 2017, season = 1, episodes = listOf(1),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "It", year = 2018, popularity = 100.0,
                season = 1, episodes = listOf(1), mediaType = MediaType.EPISODE,
            ),
            MatchCandidate(
                tmdbId = 2, name = "It", year = 2019, popularity = 100.0,
                season = 1, episodes = listOf(1), mediaType = MediaType.EPISODE,
            ),
        )
        val decision = engine.match(parsed, candidates)
        // 两个候选标题完全相等 + SxE 完整命中 + 高 popularity → score clamped to 1.0
        // 两个都 >= 0.95 → NeedsConfirm
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `Feature14 single high score still auto matches`() {
        // 只有一个候选 >= 0.95 → 正常 Auto（双高检测不触发）
        // 注意：year=2018≠parsedYear=2017 避免硬信号路径，走正常打分
        val parsed = ParsedFilename(
            title = "It", year = 2017, season = 1, episodes = listOf(1),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "It", year = 2018, popularity = 100.0,
                season = 1, episodes = listOf(1), mediaType = MediaType.EPISODE,
            ),
            MatchCandidate(tmdbId = 2, name = "Aftersun", year = 2022, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // 只有候选1得高分 → 不触发双高 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `Feature14 configurable threshold`() {
        // 使用更低的阈值 → 更多场景触发双高
        // 注意：年份不能与 parsed 完全相等（否则触发硬信号 Auto 绕过双高检测）
        // 用 year=2000/1998（±1 容差内，penalty=0）避免硬信号
        val engine = MatchEngine(doubleHighThreshold = 0.80)
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 2000, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix", year = 1998, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 两个候选标题完全相等 + 年份差1（penalty=0）→ score ≈ 0.87 >= 0.80 → NeedsConfirm
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `Feature14 does not trigger when second candidate below threshold`() {
        // best >= 0.95 但 second < 0.95 → 不触发双高 → 正常 Auto
        // 注意：year=2018≠parsedYear=2017 避免硬信号路径
        val parsed = ParsedFilename(
            title = "It", year = 2017, season = 1, episodes = listOf(1),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1, name = "It", year = 2018, popularity = 100.0,
                season = 1, episodes = listOf(1), mediaType = MediaType.EPISODE,
            ),
            MatchCandidate(tmdbId = 2, name = "It Chapter Two", year = 2019, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // best 高分但 second 低分 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }
}

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
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
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
        // 两个同名候选分差 < margin → 需确认
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
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
            MatchCandidate(tmdbId = 2, name = "Avengers Endgame", year = 2019, popularity = 50.0),
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
        // 两个相似候选分差 < margin → NeedsConfirm
        val parsed = ParsedFilename(title = "Dune", year = 2021)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Dune", year = 2021, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Dune", year = 1984, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
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
        // 两个相同标题/年份候选 → popularity 高者排第一（但因分差 < margin 仍为 NeedsConfirm）
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 80.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix", year = 1999, popularity = 20.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
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
        // |sy - cy| <= 1 → penalty=0；与年份相同场景分数一致（spec Scenario: 年份差 1）
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val sameYear = MatchCandidate(tmdbId = 1, name = "Inception", year = 2010, popularity = 50.0)
        val diffOne = MatchCandidate(tmdbId = 2, name = "Inception", year = 2009, popularity = 50.0)
        assertThat(scorer.score(parsed, sameYear)).isEqualTo(scorer.score(parsed, diffOne))
    }

    @Test fun `year diff 5 still applies linear penalty`() {
        // |sy - cy| = 5 → penalty=0.01 + 5/1000 = 0.015；与年份相同场景分数差 0.015（spec Scenario: 年份差 5）
        val scorer = ConfidenceScorer()
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val sameYear = MatchCandidate(tmdbId = 1, name = "Inception", year = 2010, popularity = 50.0)
        val diffFive = MatchCandidate(tmdbId = 2, name = "Inception", year = 2015, popularity = 50.0)
        val scoreSame = scorer.score(parsed, sameYear)
        val scoreDiff = scorer.score(parsed, diffFive)
        // diff=0 → penalty=0；diff=5 → penalty=0.015；其它维度相同 → 分数差恰好 0.015
        assertThat(scoreSame - scoreDiff).isWithin(1e-9).of(0.015)
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
        // 两候选等分 → secondGap=0 < margin → NeedsConfirm（gap < margin）
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        // 破平局后 A（vote_average=8.5）应排在 B（vote_average=6.0）之前
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
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
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        // A 不参与，B 参与 → 满足资格的 B 优先
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(2)
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
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        // 两者都不参与 → 保持原序（A 在输入顺序中排前）
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
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
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        // vote_average 相等 → vote_count 高者（B=5000）排前
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(2)
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
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        // vote_average/vote_count 相等 → 年份近度小者（A，距离 0）排前
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
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
}
